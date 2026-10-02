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
            SELECT b.id, b.user_id, a.sys_user_id, a.agent_id, b.issue_number, b.play_type, b.parameters_text,
                   b.stake, b.odds_snapshot, b.settlement_status, %s AS report_status, o.id AS order_id
              FROM game_bet b
              JOIN demo_user_account a ON a.id = b.user_id
            """.formatted(DERIVED_STATUS);

    /** 上报订单列表用的列集（含展示字段）。 */
    private static final String ORDER_COLUMNS = """
            SELECT b.id, b.bet_code, b.user_id, a.member_code, a.display_name, b.issue_number, b.ball_number,
                   b.play_type, b.parameters_text, b.stake, b.odds_snapshot, b.settlement_status,
                   %s AS report_status, b.created_at
              FROM game_bet b
              JOIN demo_user_account a ON a.id = b.user_id
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
                  JOIN demo_user_account a ON a.id = b.user_id
                %s
                 WHERE a.agent_id = ?
                """.formatted(DERIVED_STATUS, DERIVED_STATUS, LATEST_ORDER_JOIN),
                (rs, rowNum) -> new StatusCounts(rs.getLong("timeout_count"),
                        rs.getLong("unreported_count"), rs.getLong("failed_count")), agentId);
    }

    /** 列出本机器范围内的注单；{@code status} 为 {@code null} 时不过滤派生状态。 */
    public List<OrderRow> findOrders(long agentId, String status, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(ORDER_COLUMNS)
                .append(LATEST_ORDER_JOIN)
                .append(" WHERE a.agent_id = ?");
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
                  JOIN demo_user_account a ON a.id = b.user_id
                """).append(LATEST_ORDER_JOIN).append(" WHERE a.agent_id = ?");
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
        return jdbc.query(BET_COLUMNS + LATEST_ORDER_JOIN + " WHERE b.id = ? AND a.agent_id = ?",
                (rs, rowNum) -> betRow(rs), betId, agentId).stream().findFirst();
    }

    /**
     * 捞出需要上报的注单（BY220 {@code Websocket.php:658-673} 的飞单轮询）。
     *
     * <p>只取"已开启自动上报、当前在线、报网类型已配置"的机器名下的注单，且派生状态在
     * {@code statuses} 内：从未上报（{@code PENDING} 且没有上报订单行）或上次结果为
     * {@code REJECTED/TIMEOUT}。</p>
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
                + "  JOIN agent ag ON ag.id = a.agent_id\n"
                + " WHERE " + DERIVED_STATUS + " IN (" + placeholders + ")\n"
                + "   AND ag.deleted_at IS NULL\n"
                + "   AND ag.report_auto = TRUE\n"
                + "   AND ag.report_online = TRUE\n"
                + "   AND ag.report_type IS NOT NULL\n"
                + "   AND ag.report_type <> 'NONE'\n"
                + " ORDER BY b.id\n"
                + " LIMIT ?\n";
        return jdbc.query(sql, (rs, rowNum) -> betRow(rs), args.toArray());
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
