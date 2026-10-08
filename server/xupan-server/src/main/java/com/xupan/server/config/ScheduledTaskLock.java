package com.xupan.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务互斥锁（MySQL 命名锁）。
 *
 * <p><b>为什么必须有</b>：{@code @Scheduled} 在多进程并存时（滚动重启的新旧进程重叠、
 * 或将来部署第二实例）会**同时执行**。1 秒一次的自动轮期与托派发一旦双跑，
 * 直接产生重复开奖和重复派发，属于错账级事故。这与"要不要扩容"无关——
 * 单实例滚动重启时新旧进程并存数十秒，同样会双跑。</p>
 *
 * <p><b>实现选择</b>：使用 MySQL 的 {@code GET_LOCK(name, timeout)}
 * 会话级命名锁，而不是新建锁表或引入 ShedLock。
 * 理由是：不新增表结构、不依赖额外组件、语义清晰（锁随连接释放自动消失，
 * 进程崩溃不会留下死锁），并且完全满足"同一数据库上的多进程互斥"这一唯一需求。</p>
 *
 * <p><b>关键约束（必须遵守）</b>：{@code GET_LOCK} 是**连接级**的，
 * 锁的持有者是那条连接。因此这里**必须独占一条专用连接**：
 * 从 {@link DataSource} 直接借出 {@link Connection}，整个任务执行期间不归还，
 * 任务结束（含异常）时在 finally 里 {@code RELEASE_LOCK} 并关闭连接。
 * <b>不能用 {@link JdbcTemplate}</b>——它每次调用都可能拿不同连接，
 * 会导致"在 A 连接上加锁、在 B 连接上释放"，锁永远解不掉。</p>
 *
 * <p><b>H2 兼容</b>：测试环境用的是 H2（{@code MODE=MySQL}），H2 没有
 * {@code GET_LOCK}。这里用 {@link #supported} 探测一次数据库产品名，
 * 非 MySQL 时退化为**进程内单机锁**，保证测试可跑且语义在单进程下依然正确。</p>
 */
@Component
public class ScheduledTaskLock {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskLock.class);

    /** 锁等待秒数：拿不到就跳过本轮，不排队堆积。 */
    private static final int LOCK_TIMEOUT_SECONDS = 1;

    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;
    private final boolean mysqlNamedLockSupported;
    /** 被其它进程占锁而跳过的轮次计数（按锁名）。运维用它判断"是不是有两个进程在跑"。 */
    private final Map<String, Long> skippedRounds = new ConcurrentHashMap<>();

    public ScheduledTaskLock(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.dataSource = dataSource;
        this.transactionTemplate = transactionTemplate;
        this.mysqlNamedLockSupported = detectMysql(dataSource);
    }

    /**
     * 在互斥保护下执行任务。
     *
     * <p>拿不到锁时**静默跳过本轮**（返回 {@code false}），不抛异常、不排队：
     * 定时任务下一轮还会来，堆积反而会把线程池占满。</p>
     *
     * @param lockName 锁名，同一把锁的任务互斥；建议格式 {@code xupan:<task>}
     * @param task     任务体
     * @return 是否真正执行了任务（{@code false} = 本轮被其它进程/线程持有）
     */
    public boolean runExclusively(String lockName, Runnable task) {
        if (lockName == null || lockName.isBlank() || task == null) {
            throw new IllegalArgumentException("锁名与任务不能为空");
        }
        if (!mysqlNamedLockSupported) {
            // H2 / 非 MySQL：退化为单机锁。测试环境下语义等价。
            synchronized (lockName.intern()) {
                task.run();
            }
            return true;
        }
        return runWithMysqlNamedLock(lockName, task);
    }

    /**
     * 各锁被跳过的累计轮次。
     *
     * <p>运维含义：单实例正常运行时这些计数应**恒为 0**（同进程内只有一条调度线程）；
     * 一旦持续增长，说明同一数据库上有第二个进程在跑同一批任务——那就是重复开奖的前兆，
     * 必须立刻排查，而不是等业务方发现账不对。</p>
     */
    public Map<String, Long> skippedRounds() {
        return Map.copyOf(skippedRounds);
    }

    private boolean runWithMysqlNamedLock(String lockName, Runnable task) {
        Connection connection = null;
        boolean locked = false;
        try {
            connection = dataSource.getConnection();
            locked = tryAcquire(connection, lockName);
            if (!locked) {
                // 另一个进程正在跑同一任务：本轮跳过。
                // 不打 WARN 避免双进程时日志刷屏，但**必须计数**——持续增长就是"有两个进程"的证据。
                skippedRounds.merge(lockName, 1L, Long::sum);
                if (skippedRounds.get(lockName) % 100 == 1) {
                    log.warn("定时任务锁被其它进程占用已跳过 {} 轮，请确认是否有多余实例在运行 lock={}",
                            skippedRounds.get(lockName), lockName);
                }
                return false;
            }
            task.run();
            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException("获取定时任务锁失败 lock=" + lockName, exception);
        } finally {
            releaseQuietly(connection, lockName, locked);
        }
    }

    private boolean tryAcquire(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lockName);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private void releaseQuietly(Connection connection, String lockName, boolean locked) {
        if (connection == null) {
            return;
        }
        try {
            if (locked) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, lockName);
                    statement.executeQuery();
                }
            }
        } catch (SQLException exception) {
            // 连接可能已断；连接关闭本身也会释放锁，因此只记录不抛。
            log.warn("释放定时任务锁失败（连接关闭会兜底释放） lock={}", lockName, exception);
        } finally {
            try {
                connection.close();
            } catch (SQLException exception) {
                log.debug("关闭定时任务锁连接失败 lock={}", lockName, exception);
            }
        }
    }

    /**
     * 探测是否支持 MySQL 命名锁。
     *
     * <p>用 {@code TransactionTemplate} 借一次连接读产品名；失败时保守地按"不支持"处理，
     * 退化为单机锁而不是让应用起不来。</p>
     */
    private static boolean detectMysql(DataSource dataSource) {
        try {
            String product = new TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource))
                    .execute(status -> {
                        try (Connection connection = dataSource.getConnection()) {
                            return connection.getMetaData().getDatabaseProductName();
                        } catch (SQLException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
            boolean mysql = product != null && product.toLowerCase().contains("mysql");
            if (!mysql) {
                log.info("当前数据库不支持 MySQL 命名锁（product={}），定时任务互斥退化为单机锁", product);
            }
            return mysql;
        } catch (RuntimeException exception) {
            log.warn("探测数据库产品名失败，定时任务互斥退化为单机锁", exception);
            return false;
        }
    }

    /** 供测试断言当前是否走 MySQL 命名锁分支。 */
    boolean mysqlNamedLockSupported() {
        return mysqlNamedLockSupported;
    }
}
