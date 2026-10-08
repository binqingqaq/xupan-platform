package com.xupan.server.report.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 上游上报订单仓储（第二层：代理 ↔ 上游）。
 *
 * <p>模型依据 Flyway V58 与
 * {@code docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md} §3 原则 3、4：</p>
 * <ul>
 *   <li>本地玩家注单（{@code game_bet}）与上游上报订单（{@code report_upstream_order}）是**不同实体**；</li>
 *   <li>上报订单**追加保留**：一次上报尝试一行，不覆盖历史；</li>
 *   <li>注单当前的上报状态 = 该注单**最新一行**上报订单的状态（读时派生），
 *       没有上报订单行表示"还没报"，派生为 {@code PENDING}。</li>
 * </ul>
 *
 * <p>状态机：待处理 {@code PENDING} / 已接受 {@code ACCEPTED} / 已拒绝 {@code REJECTED} /
 * 超时 {@code TIMEOUT} / 已撤单 {@code CANCELLED} / 已退款 {@code REFUNDED} / 已结算 {@code SETTLED}。
 * 本阶段（本地模拟上报）写入前四个；后三个由中心上游第二层结算写入。</p>
 */
@Repository
public class ReportUpstreamOrderRepository {

    /** 派生状态：取该注单最新一行上报订单的状态；没有行即"还没报"。 */
    private static final String DERIVED_STATUS = "COALESCE(o.status, 'PENDING')";

    private static final String LATEST_ORDER_JOIN = """
            LEFT JOIN report_upstream_order o
                   ON o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2 WHERE o2.bet_id = b.id)
            """;

    private static final String BET_COLUMNS = """
            SELECT b.id, b.user_id, a.sys_user_id,
                   COALESCE(a.agent_id, b.agent_id_snapshot) AS agent_id,
                   b.issue_number, b.play_type, b.parameters_text,
                   b.stake, b.odds_snapshot, b.settlement_status, %s AS report_status, o.id AS order_id
              FROM game_bet b
              LEFT JOIN demo_user_account a ON a.id = b.user_id
            """.formatted(DERIVED_STATUS);

    /** 上报订单列表用的列集（含展示字段）。 */
    private static final String ORDER_COLUMNS = """
            SELECT b.id, b.bet_code, b.user_id,
                   COALESCE(a.member_code, b.player_code_snapshot) AS member_code,
                   COALESCE(a.display_name, b.player_name_snapshot) AS display_name,
                   b.issue_number, b.ball_number,
                   b.play_type, b.parameters_text, b.stake, b.odds_snapshot, b.settlement_status,
                   %s AS report_status, b.created_at
              FROM game_bet b
              LEFT JOIN demo_user_account a ON a.id = b.user_id
            """.formatted(DERIVED_STATUS);

    private final JdbcTemplate jdbc;

    public ReportUpstreamOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一次上报尝试。{@code idempotencyKey} 唯一，重复提交会抛唯一键冲突而不是写第二行。 */
    public long insertAttempt(long betId, long agentId, int attemptNo, String status, String reportType,
                              String routeCode, String networkCode, String idempotencyKey,
                              String requestSummary, String responseCode, String responseMessage,
                              Instant sentAt, Instant resolvedAt) {
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, attempt_no, status, report_type, route_code, network_code,
                     idempotency_key, request_summary, response_code, response_message,
                     sent_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, betId, agentId, attemptNo, status, reportType, routeCode, networkCode,
                idempotencyKey, requestSummary, responseCode, responseMessage,
                sentAt == null ? null : Timestamp.from(sentAt),
                resolvedAt == null ? null : Timestamp.from(resolvedAt));
        return latestIdByBet(betId);
    }

    /** 标记一次上报尝试的最终结果（只允许从 {@code PENDING} 迁移，保证幂等）。 */
    public int resolveAttempt(long orderId, String newStatus, String responseCode, String responseMessage,
                              String upstreamOrderNo, Instant resolvedAt) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET status = ?, response_code = ?, response_message = ?, upstream_order_no = ?,
                       resolved_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'PENDING'
                """, newStatus, responseCode, responseMessage, upstreamOrderNo,
                resolvedAt == null ? null : Timestamp.from(resolvedAt), orderId);
    }

    /** 还挂在 {@code PENDING} 的上报尝试（进程中断后的恢复入口）。 */
    public List<AttemptRow> findPendingAttempts(int limit) {
        return jdbc.query("""
                SELECT id, bet_id, agent_id, attempt_no, status, upstream_order_no,
                       response_code, response_message, sent_at, resolved_at, created_at
                  FROM report_upstream_order
                 WHERE status = 'PENDING'
                 ORDER BY id
                 LIMIT ?
                """, (rs, rowNum) -> attemptRow(rs), limit);
    }

    public Optional<AttemptRow> findLatestAttempt(long betId) {
        return jdbc.query("""
                SELECT id, bet_id, agent_id, attempt_no, status, upstream_order_no,
                       response_code, response_message, sent_at, resolved_at, created_at
                  FROM report_upstream_order
                 WHERE bet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, rowNum) -> attemptRow(rs), betId).stream().findFirst();
    }

    /** 该注单的下一次尝试序号（从未上报过为 1）。 */
    public int nextAttemptNo(long betId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(attempt_no) FROM report_upstream_order WHERE bet_id = ?", Integer.class, betId);
        return max == null ? 1 : max + 1;
    }

    public long latestIdByBet(long betId) {
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM report_upstream_order WHERE bet_id = ?", Long.class, betId);
        if (id == null) {
            throw new IllegalStateException("写入上报订单后未找到记录");
        }
        return id;
    }

    /** 本机器范围内按**派生状态**统计上报情况：超时 / 未报 / 失败。 */
    public StatusCounts counts(long agentId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN %s = 'TIMEOUT' THEN 1 ELSE 0 END), 0) AS timeout_count,
                       COALESCE(SUM(CASE WHEN o.id IS NULL THEN 1 ELSE 0 END), 0) AS unreported_count,
                       COALESCE(SUM(CASE WHEN %s = 'REJECTED' THEN 1 ELSE 0 END), 0) AS failed_count
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                %s
                 WHERE COALESCE(a.agent_id, b.agent_id_snapshot) = ?
                """.formatted(DERIVED_STATUS, DERIVED_STATUS, LATEST_ORDER_JOIN),
                (rs, rowNum) -> new StatusCounts(rs.getLong("timeout_count"),
                        rs.getLong("unreported_count"), rs.getLong("failed_count")), agentId);
    }

    /** 列出本机器范围内的注单；{@code status} 为 {@code null} 时不过滤派生状态。 */
    public List<OrderRow> findOrders(long agentId, String status, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(ORDER_COLUMNS)
                .append(LATEST_ORDER_JOIN)
                .append(" WHERE COALESCE(a.agent_id, b.agent_id_snapshot) = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND ").append(DERIVED_STATUS).append(" = ?");
            args.add(status);
        }
        sql.append(" ORDER BY b.created_at DESC, b.id DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbc.query(sql.toString(), (rs, rowNum) -> new OrderRow(
                rs.getLong("id"), rs.getString("bet_code"), rs.getLong("user_id"), rs.getString("member_code"),
                rs.getString("display_name"), rs.getString("issue_number"), rs.getInt("ball_number"),
                rs.getString("play_type"), rs.getString("parameters_text"), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), rs.getString("settlement_status"), rs.getString("report_status"),
                instant(rs.getTimestamp("created_at"))), args.toArray());
    }

    /** 统计本机器范围内指定派生状态的注单数。 */
    public long countOrders(long agentId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                """).append(LATEST_ORDER_JOIN).append(" WHERE COALESCE(a.agent_id, b.agent_id_snapshot) = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND ").append(DERIVED_STATUS).append(" = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 按注单 id 读取快照（含派生上报状态）。 */
    public Optional<BetRow> findBet(long betId) {
        return jdbc.query(BET_COLUMNS + LATEST_ORDER_JOIN + " WHERE b.id = ?",
                (rs, rowNum) -> betRow(rs), betId).stream().findFirst();
    }

    /** 按注单 id + 机器范围读取快照；范围外的注单按"不存在"处理。 */
    public Optional<BetRow> findBetForAgent(long betId, long agentId) {
        return jdbc.query(BET_COLUMNS + LATEST_ORDER_JOIN
                        + " WHERE b.id = ? AND COALESCE(a.agent_id, b.agent_id_snapshot) = ?",
                (rs, rowNum) -> betRow(rs), betId, agentId).stream().findFirst();
    }

    /**
     * 捞出需要上报的注单（BY220 {@code Websocket.php:658-673} 的飞单轮询）。
     *
     * <p>只取"已开启自动上报、当前在线、报网类型已配置"的机器名下的注单，且派生状态在
     * {@code statuses} 内：从未上报（{@code PENDING} 且没有上报订单行）或上次结果为
     * {@code REJECTED/TIMEOUT}。</p>
     *
     * <p><b>退避与上限（P0-2 修复）</b>：这是原实现的缺陷点。
     * 原来用 {@code ORDER BY b.id LIMIT ?}，注单按 id 升序取，最老的 50 笔一旦长期停留在
     * {@code REJECTED/TIMEOUT}，批次会被它们**永久占满**，此后新注单再也排不上上报。
     * 现在改为：</p>
     * <ul>
     *   <li>新增注单（从未上报）优先，用 {@code order_id IS NULL} 判定，它们不受退避影响；</li>
     *   <li>重报单必须 {@code next_retry_at} 已到期才参与，避免失败单每轮都抢占批次；</li>
     *   <li>已转人工（{@code MANUAL}）的单不在 {@code statuses} 内，自然不再被捞取；</li>
     *   <li>排序改为"新单优先、重报按到点时间"，使批次总是先服务没报过的单。</li>
     * </ul>
     *
     * @param statuses 允许上报的派生状态集合
     * @param limit    单次上限
     */
    public List<BetRow> findSubmittableBets(List<String> statuses, int limit) {
        if (statuses == null || statuses.isEmpty() || limit <= 0) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        List<Object> args = new ArrayList<>(statuses);
        args.add(limit);
        String sql = BET_COLUMNS + LATEST_ORDER_JOIN
                + "  JOIN agent ag ON ag.id = COALESCE(a.agent_id, b.agent_id_snapshot)\n"
                + UNIFIED_REPORT_GROUP_JOIN
                + " WHERE " + DERIVED_STATUS + " IN (" + placeholders + ")\n"
                + "   AND ag.deleted_at IS NULL\n"
                + "   AND ag.report_auto = TRUE\n"
                + "   AND ag.report_online = TRUE\n"
                + "   AND " + EFFECTIVE_REPORT_TYPE + " IS NOT NULL\n"
                + "   AND " + EFFECTIVE_REPORT_TYPE + " <> 'NONE'\n"
                // 从未上报的注单立即可报；重报单必须等到退避时间点。
                + "   AND (o.id IS NULL OR o.next_retry_at IS NULL OR o.next_retry_at <= CURRENT_TIMESTAMP)\n"
                // 新单优先，其次按到点时间从早到晚；这样老失败单不会饿死新单。
                + " ORDER BY CASE WHEN o.id IS NULL THEN 0 ELSE 1 END, o.next_retry_at, b.id\n"
                + " LIMIT ?\n";
        return jdbc.query(sql, (rs, rowNum) -> betRow(rs), args.toArray());
    }

    /**
     * 统一飞单（子账号级）左连接：机器自身没有飞单账号、且所属子账号开启了统一飞单并配好账号时生效。
     *
     * <p>与 {@code ReportConfigRepository.findConfig} 的回退条件逐字一致，避免"读配置时能继承、
     * 轮询却捞不到"的两套口径。</p>
     */
    private static final String UNIFIED_REPORT_GROUP_JOIN = """
              LEFT JOIN agent_group g ON g.id = ag.group_id AND g.deleted_at IS NULL
            """;

    /**
     * 生效的报网类型（BY220：机器的 {@code flyers_type} 由所选网盘/统一飞单决定）。
     *
     * <p>继承态取子账号的 {@code report_type}，为空则回退机器自身；非继承态取机器自身。
     * 以前这里直接判 {@code ag.report_type}，会让"只靠子账号统一飞单类型"的机器被自动上报轮询漏掉。</p>
     */
    private static final String EFFECTIVE_REPORT_TYPE = """
            COALESCE(NULLIF(CASE
                WHEN (ag.report_username IS NULL OR ag.report_username = '')
                 AND g.unified_report_enabled = TRUE
                 AND g.report_username IS NOT NULL AND g.report_username <> ''
                THEN g.report_type END, ''), ag.report_type)
            """;

    /**
     * 安排一次重报时间点并累加尝试计数。
     *
     * <p>作用于该注单**最新**的那次上报尝试行。用 {@code (SELECT MAX(id) ...)} 定位而不是传入 orderId，
     * 是为了让调用方（轮询）不必关心当前是哪一行。</p>
     *
     * @param betId    注单 id
     * @param nextAt   下次可重报时间；{@code null} 表示立即可重报
     * @return 本次累加后的尝试次数
     */
    public int scheduleRetry(long betId, Instant nextAt) {
        jdbc.update("""
                UPDATE report_upstream_order
                   SET attempt_count = attempt_count + 1,
                       next_retry_at = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = (SELECT MAX(id) FROM report_upstream_order WHERE bet_id = ?)
                """, nextAt == null ? null : Timestamp.from(nextAt), betId);
        return currentAttemptCount(betId);
    }

    /** 把最新一次上报尝试转为人工待处置（不再自动重报）。 */
    public int markManual(long betId, String responseCode, String responseMessage) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET status = 'MANUAL', response_code = ?, response_message = ?,
                       next_retry_at = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE id = (SELECT MAX(id) FROM report_upstream_order WHERE bet_id = ?)
                """, responseCode, responseMessage, betId);
    }

    /**
     * 人工重报前清障：清零该注单最新尝试的计数与退避，并解除 {@code MANUAL} 标记。
     *
     * <p>状态回到 {@code REJECTED}（"上次失败、可再试"）而不是 {@code PENDING}
     * （{@code PENDING} 语义是"已发出但结果未回"，会让恢复扫描误以为有悬挂请求）。</p>
     */
    public int resetForManualResubmit(long betId) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET attempt_count = 0,
                       next_retry_at = NULL,
                       status = CASE WHEN status = 'MANUAL' THEN 'REJECTED' ELSE status END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = (SELECT MAX(id) FROM report_upstream_order WHERE bet_id = ?)
                """, betId);
    }

    /** 人工待处置（{@code MANUAL}）的注单数量，供监控与界面提示。 */
    public long manualCount(long agentId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                """ + LATEST_ORDER_JOIN + " WHERE COALESCE(a.agent_id, b.agent_id_snapshot) = ? AND " + DERIVED_STATUS + " = 'MANUAL'",
                Long.class, agentId);
        return total == null ? 0 : total;
    }

    /** 全量人工待处置注单数量（健康检查用，不限机器）。 */
    public long manualCountAll() {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                """ + LATEST_ORDER_JOIN + " WHERE " + DERIVED_STATUS + " = 'MANUAL'",
                Long.class);
        return total == null ? 0 : total;
    }

    /** 注单最新一次上报尝试已累计的尝试次数（无上报行为 0）。 */
    public int currentAttemptCount(long betId) {
        Integer count = jdbc.queryForObject("""
                SELECT COALESCE((SELECT attempt_count FROM report_upstream_order
                                  WHERE bet_id = ? ORDER BY id DESC LIMIT 1), 0)
                """, Integer.class, betId);
        return count == null ? 0 : count;
    }

    /**
     * 待上报积压量（未被退避推迟、且未被转人工的候选数量）。
     *
     * <p>用于 P0-2 的告警与监控：正常运行时该值应随时间下降；若长期不降，
     * 说明上报链路已经堵住，需要立刻排查，而不是等业务方发现"单没报上去"。</p>
     */
    public long backlogCount(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                """ + LATEST_ORDER_JOIN
                + "  JOIN agent ag ON ag.id = COALESCE(a.agent_id, b.agent_id_snapshot)\n"
                + UNIFIED_REPORT_GROUP_JOIN
                + " WHERE " + DERIVED_STATUS + " IN (" + placeholders + ")\n"
                + "   AND ag.deleted_at IS NULL\n"
                + "   AND ag.report_auto = TRUE\n"
                + "   AND ag.report_online = TRUE\n"
                + "   AND " + EFFECTIVE_REPORT_TYPE + " IS NOT NULL\n"
                + "   AND " + EFFECTIVE_REPORT_TYPE + " <> 'NONE'\n",
                Long.class, statuses.toArray());
        return total == null ? 0 : total;
    }

    /** 注单是否仍未结算（失败退单时用于审计留痕）。 */
    public String findSettlementStatus(long betId) {
        List<String> rows = jdbc.queryForList("SELECT settlement_status FROM game_bet WHERE id = ?",
                String.class, betId);
        return rows.stream().findFirst().orElse(null);
    }

    private static AttemptRow attemptRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AttemptRow(rs.getLong("id"), rs.getLong("bet_id"), rs.getLong("agent_id"),
                rs.getInt("attempt_no"), rs.getString("status"), rs.getString("upstream_order_no"),
                rs.getString("response_code"), rs.getString("response_message"),
                instant(rs.getTimestamp("sent_at")), instant(rs.getTimestamp("resolved_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private static BetRow betRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BetRow(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("sys_user_id"),
                rs.getLong("agent_id"), rs.getString("issue_number"), rs.getString("play_type"),
                rs.getString("parameters_text"), rs.getBigDecimal("stake"), rs.getBigDecimal("odds_snapshot"),
                rs.getString("report_status"), rs.getString("settlement_status"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 一次上报尝试。 */
    public record AttemptRow(long id, long betId, long agentId, int attemptNo, String status,
                             String upstreamOrderNo, String responseCode, String responseMessage,
                             Instant sentAt, Instant resolvedAt, Instant createdAt) {
    }

    /** 注单快照 + 派生上报状态。 */
    public record BetRow(long betId, long accountId, long sysUserId, long agentId, String issueNumber,
                         String playType, String parametersText, BigDecimal stake, BigDecimal odds,
                         String reportStatus, String settlementStatus) {
    }

    /** 上报情况统计。 */
    public record StatusCounts(long timeoutCount, long unreportedCount, long failedCount) {
    }

    /** 上报订单列表行。 */
    public record OrderRow(long betId, String betCode, long accountId, String memberCode, String displayName,
                           String issueNumber, int ballNumber, String playType, String parametersText,
                           BigDecimal stake, BigDecimal odds, String settlementStatus, String reportStatus,
                           Instant createdAt) {
    }
}
