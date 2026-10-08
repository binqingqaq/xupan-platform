package com.xupan.server.auth.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 操作审计写入。
 *
 * <p>审计必须独立于业务事务提交：业务回滚时审计仍要留下失败记录。但审计表的
 * {@code operator_user_id} 外键会在插入时对 {@code sys_user} 对应行加共享锁，
 * 如果业务事务此刻正持有该行的排他锁（例如管理员重置自己的密码、上传自己的头像、
 * 修改自己的角色），独立事务就会等待自己，最终以 50 秒锁等待超时失败并回滚业务。
 *
 * <p>因此：业务事务存在时，审计写入登记到事务完成回调，等业务事务提交或回滚、锁释放后
 * 再用独立事务写入；没有业务事务时直接写入。
 */
@Repository
public class OperationAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate auditTransaction;

    public OperationAuditRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditTransaction = new TransactionTemplate(transactionManager);
        this.auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(Long operatorUserId, String permissionCode, String httpMethod, String requestPath,
                       String resourceId, String result, String errorCode, String requestSummary,
                       String ip, Instant createdAt) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    write(operatorUserId, permissionCode, httpMethod, requestPath, resourceId, result,
                            errorCode, requestSummary, ip, createdAt);
                }
            });
            return;
        }
        write(operatorUserId, permissionCode, httpMethod, requestPath, resourceId, result,
                errorCode, requestSummary, ip, createdAt);
    }

    private void write(Long operatorUserId, String permissionCode, String httpMethod, String requestPath,
                       String resourceId, String result, String errorCode, String requestSummary,
                       String ip, Instant createdAt) {
        String safeSummary = LoginAuditRepository.rejectSensitive(
                LoginAuditRepository.truncate(requestSummary, 2000), "requestSummary");
        String safeResourceId = LoginAuditRepository.rejectSensitive(
                LoginAuditRepository.truncate(resourceId, 128), "resourceId");
        try {
            insert(operatorUserId, permissionCode, httpMethod, requestPath, safeResourceId, result,
                    errorCode, safeSummary, ip, createdAt);
        } catch (DataIntegrityViolationException exception) {
            // 账号已被物理删除（2026-10-03 起机器/子账号/全量账号删除都是物理删除）时，
            // 待写审计的操作者外键会失效。审计行本身仍要留下，只是把操作者置空，
            // 避免"删除账号"这类操作反而把自己的审计记录丢掉。
            if (operatorUserId == null) {
                throw exception;
            }
            log.warn("操作审计的操作者账号已不存在，退化为无操作者写入 path={} operatorUserId={}",
                    requestPath, operatorUserId);
            insert(null, permissionCode, httpMethod, requestPath, safeResourceId, result,
                    errorCode, safeSummary, ip, createdAt);
        }
    }

    private void insert(Long operatorUserId, String permissionCode, String httpMethod, String requestPath,
                        String safeResourceId, String result, String errorCode, String safeSummary,
                        String ip, Instant createdAt) {
        auditTransaction.executeWithoutResult(status -> jdbcTemplate.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operatorUserId, LoginAuditRepository.truncate(permissionCode, 128),
                LoginAuditRepository.truncate(Objects.requireNonNull(httpMethod, "httpMethod"), 16),
                LoginAuditRepository.truncate(Objects.requireNonNull(requestPath, "requestPath"), 255),
                safeResourceId, requiredResult(result), LoginAuditRepository.truncate(errorCode, 64),
                safeSummary, LoginAuditRepository.digest(ip), timestamp(createdAt)));
    }

    private static String requiredResult(String result) {
        if (!"SUCCESS".equals(result) && !"FAILURE".equals(result)) {
            throw new IllegalArgumentException("非法操作审计结果");
        }
        return result;
    }

    /**
     * 分页查询操作审计（账本原则 3：日志不只可写，还要可查）。
     *
     * <p>过滤条件都可空：{@code operatorUserId} 按操作者、{@code permissionCode} 按权限码、
     * {@code result} 按成功/失败、{@code keyword} 在请求路径与资源 id 上模糊匹配。</p>
     */
    public List<AuditRow> findEntries(Long operatorUserId, String permissionCode, String result,
                                                String keyword, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, operator_user_id, permission_code, http_method, request_path, resource_id,
                       result, error_code, request_summary, created_at
                  FROM sys_operation_log
                """);
        ListOfArgs args = filterArgs(operatorUserId, permissionCode, result, keyword, sql);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.values.add(pageSize);
        args.values.add((long) (page - 1) * pageSize);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Timestamp created = rs.getTimestamp("created_at");
            return new AuditRow(rs.getLong("id"), rs.getObject("operator_user_id", Long.class),
                    rs.getString("permission_code"), rs.getString("http_method"), rs.getString("request_path"),
                    rs.getString("resource_id"), rs.getString("result"), rs.getString("error_code"),
                    rs.getString("request_summary"), created == null ? null : created.toInstant());
        }, args.values.toArray());
    }

    /** 与 {@link #findEntries} 同条件的总数。 */
    public long countEntries(Long operatorUserId, String permissionCode, String result, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sys_operation_log");
        ListOfArgs args = filterArgs(operatorUserId, permissionCode, result, keyword, sql);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.values.toArray());
        return total == null ? 0 : total;
    }

    /**
     * 拼装过滤条件。
     *
     * <p>注意审计里的 {@code request_summary} 可能与资源 id 无关，因此关键词只匹配
     * 请求路径与资源 id，避免把摘要里可能出现的凭据字样当查询条件暴露出去。</p>
     */
    private static ListOfArgs filterArgs(Long operatorUserId, String permissionCode, String result,
                                         String keyword, StringBuilder sql) {
        ListOfArgs args = new ListOfArgs();
        if (operatorUserId != null) {
            sql.append(" WHERE operator_user_id = ?");
            args.values.add(operatorUserId);
        }
        if (permissionCode != null && !permissionCode.isBlank()) {
            sql.append(args.values.isEmpty() ? " WHERE " : " AND ").append("permission_code = ?");
            args.values.add(permissionCode.trim());
        }
        if (result != null && !result.isBlank()) {
            sql.append(args.values.isEmpty() ? " WHERE " : " AND ").append("result = ?");
            args.values.add(requiredResult(result.trim().toUpperCase(Locale.ROOT)));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(args.values.isEmpty() ? " WHERE " : " AND ")
                    .append("(request_path LIKE ? OR resource_id LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.values.add(pattern);
            args.values.add(pattern);
        }
        return args;
    }

    private static final class ListOfArgs {
        private final List<Object> values = new java.util.ArrayList<>();
    }

    /** 一行操作审计。 */
    public record AuditRow(long id, Long operatorUserId, String permissionCode, String httpMethod,
                           String requestPath, String resourceId, String result, String errorCode,
                           String requestSummary, Instant createdAt) {
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? new Timestamp(System.currentTimeMillis()) : Timestamp.from(value);
    }
}
