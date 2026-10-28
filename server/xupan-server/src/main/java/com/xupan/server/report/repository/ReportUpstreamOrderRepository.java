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
 * <p>状态机：未发送 {@code NOT_SENT} / 待处理 {@code PENDING} / 已接受 {@code ACCEPTED} /
 * 已拒绝 {@code REJECTED} / 超时待核 {@code TIMEOUT} / 人工待处置 {@code MANUAL} /
 * 已撤单 {@code CANCELLED} / 已退款 {@code REFUNDED} / 已结算 {@code SETTLED}。
 * 中心订单处于 {@code PENDING/TIMEOUT} 时由恢复扫描按原中心账号和幂等单核验，不作为新尝试重报。</p>
 */
@Repository
public class ReportUpstreamOrderRepository {

    /** 派生状态：取该注单最新一行上报订单的状态；没有行即"还没报"。 */
    private static final String DERIVED_STATUS =
            "CASE WHEN b.center_report_deleted_at IS NOT NULL THEN 'NOT_SENT' ELSE COALESCE(o.status, 'PENDING') END";

    private static final String LATEST_ORDER_JOIN = """
            LEFT JOIN report_upstream_order o
                   ON o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2 WHERE o2.bet_id = b.id)
            """;

    private static final String BET_COLUMNS = """
            SELECT b.id, b.user_id, a.sys_user_id,
                   COALESCE(a.agent_id, b.agent_id_snapshot) AS agent_id,
                   b.issue_number, b.play_type, b.parameters_text,
                   b.stake, b.odds_snapshot, b.settlement_status, %s AS report_status,
                   o.id AS order_id, o.response_code
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
                   %s AS report_status,
                   CASE WHEN b.center_report_deleted_at IS NOT NULL THEN 'CENTER_ACCOUNT_DELETED' ELSE o.response_code END AS response_code,
                   CASE WHEN b.center_report_deleted_at IS NOT NULL THEN '中心账号删除后不再报送此注单' ELSE o.response_message END AS response_message,
                   d.center_outcome AS local_center_outcome,
                   d.decision AS local_disposition,
                   d.reason AS local_disposition_reason,
                   d.operator_user_id_snapshot AS local_disposition_operator_id,
                   d.created_at AS local_disposition_at,
                   b.created_at
              FROM game_bet b
              LEFT JOIN demo_user_account a ON a.id = b.user_id
            """.formatted(DERIVED_STATUS);

    private static final String LOCAL_DISPOSITION_JOIN = """
            LEFT JOIN report_local_disposition d ON d.bet_id = b.id
            """;

    private final JdbcTemplate jdbc;

    public ReportUpstreamOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一次上报尝试。{@code idempotencyKey} 唯一，重复提交会抛唯一键冲突而不是写第二行。 */
    public long insertAttempt(long betId, long agentId, Long centerAgentId, int attemptNo, String status, String reportType,
                              String routeCode, String networkCode, String idempotencyKey,
                              String requestSummary, String responseCode, String responseMessage,
                              Instant sentAt, Instant resolvedAt) {
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, center_agent_id, attempt_no, status, report_type, route_code, network_code,
                     idempotency_key, request_summary, response_code, response_message,
                     sent_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, betId, agentId, centerAgentId, attemptNo, status, reportType, routeCode, networkCode,
                idempotencyKey, requestSummary, responseCode, responseMessage,
                sentAt == null ? null : Timestamp.from(sentAt),
                resolvedAt == null ? null : Timestamp.from(resolvedAt));
        return latestIdByBet(betId);
    }

    /** Mark a recovered attempt terminal; repeated resolution is harmless. */
    public int resolveAttempt(long orderId, int expectedAttemptCount, String newStatus,
                              String responseCode, String responseMessage,
                              String upstreamOrderNo, Instant resolvedAt) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET status = ?, response_code = ?, response_message = ?, upstream_order_no = ?,
                       resolved_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status IN ('PENDING', 'TIMEOUT') AND attempt_count = ?
                """, newStatus, responseCode, responseMessage, upstreamOrderNo,
                resolvedAt == null ? null : Timestamp.from(resolvedAt), orderId, expectedAttemptCount);
    }

    /** Keep an unresolved center attempt open and schedule its next idempotent status replay. */
    public int deferPendingAttempt(long orderId, int expectedAttemptCount, String status,
                                   String responseCode, String responseMessage, Instant nextRetryAt) {
        if (!"PENDING".equals(status) && !"TIMEOUT".equals(status)) {
            throw new IllegalArgumentException("未决上报状态只能为 PENDING 或 TIMEOUT");
        }
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET status = ?, response_code = ?, response_message = ?, resolved_at = NULL,
                       attempt_count = attempt_count + 1, next_retry_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status IN ('PENDING', 'TIMEOUT') AND attempt_count = ?
                """, status, responseCode, responseMessage, Timestamp.from(nextRetryAt),
                orderId, expectedAttemptCount);
    }

    /** 仍待核实的中心上报尝试（进程中断后的恢复入口）。 */
    public List<AttemptRow> findPendingAttempts(int limit) {
        return jdbc.query("""
                SELECT id, bet_id, agent_id, center_agent_id, report_type, attempt_no, attempt_count,
                       status, upstream_order_no, response_code, response_message,
                       sent_at, resolved_at, created_at, next_retry_at
                  FROM report_upstream_order
                 WHERE report_type = 'CENTER' AND status IN ('PENDING', 'TIMEOUT')
                   AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)
                 ORDER BY id
                 LIMIT ?
                """, (rs, rowNum) -> attemptRow(rs), limit);
    }

    public Optional<AttemptRow> findLatestAttempt(long betId) {
        return jdbc.query("""
                SELECT id, bet_id, agent_id, center_agent_id, report_type, attempt_no, attempt_count,
                       status, upstream_order_no, response_code, response_message,
                       sent_at, resolved_at, created_at, next_retry_at
                  FROM report_upstream_order
                 WHERE bet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, rowNum) -> attemptRow(rs), betId).stream().findFirst();
    }

    /** Locks and reads the latest attempt while a local disposition is being decided. */
    public Optional<AttemptRow> findLatestAttemptForUpdate(long betId) {
        return jdbc.query("""
                SELECT id, bet_id, agent_id, center_agent_id, report_type, attempt_no, attempt_count,
                       status, upstream_order_no, response_code, response_message,
                       sent_at, resolved_at, created_at, next_retry_at
                  FROM report_upstream_order
                 WHERE bet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                 FOR UPDATE
                """, (rs, rowNum) -> attemptRow(rs), betId).stream().findFirst();
    }

    /** Locks a player bet within its owning machine before a manual center-result decision. */
    public Optional<LocalDispositionBet> findDispositionBetForUpdate(long betId, long agentId) {
        return jdbc.query("""
                SELECT b.id, b.user_id, a.sys_user_id,
                       COALESCE(a.agent_id, b.agent_id_snapshot) AS agent_id,
                       b.issue_number, b.stake, b.settlement_status, b.settled_at
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.id = ? AND COALESCE(a.agent_id, b.agent_id_snapshot) = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new LocalDispositionBet(rs.getLong("id"), rs.getLong("user_id"),
                rs.getLong("sys_user_id"), rs.getLong("agent_id"), rs.getString("issue_number"),
                rs.getBigDecimal("stake"), rs.getString("settlement_status"),
                instant(rs.getTimestamp("settled_at"))), betId, agentId).stream().findFirst();
    }

    public Optional<LocalDispositionRow> findLocalDisposition(long betId) {
        return jdbc.query("""
                SELECT bet_id, report_attempt_id, agent_id_snapshot, center_agent_id_snapshot,
                       center_outcome, decision, reason, operator_user_id_snapshot, created_at
                  FROM report_local_disposition
                 WHERE bet_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> localDispositionRow(rs), betId).stream().findFirst();
    }

    public void insertLocalDisposition(long betId, long attemptId, long agentId, Long centerAgentId,
                                       String centerOutcome, String decision, String reason,
                                       long operatorUserId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO report_local_disposition
                    (bet_id, report_attempt_id, agent_id_snapshot, center_agent_id_snapshot,
                     center_outcome, decision, reason, idempotency_key, operator_user_id_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, betId, attemptId, agentId, centerAgentId, centerOutcome, decision, reason,
                "CENTER_LOCAL_DISPOSITION:" + betId, operatorUserId, Timestamp.from(createdAt));
    }

    /** Converts the original MANUAL marker to its resolved center-side terminal outcome. */
    public int resolveManualCenterAttempt(long attemptId, String centerOutcome, String expectedResponseCode,
                                          Instant resolvedAt) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET status = ?, resolved_at = ?, next_retry_at = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'MANUAL' AND report_type = 'CENTER' AND response_code = ?
                """, centerOutcome, Timestamp.from(resolvedAt), attemptId, expectedResponseCode);
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
                       COALESCE(SUM(CASE WHEN o.id IS NULL OR %s = 'NOT_SENT' THEN 1 ELSE 0 END), 0) AS unreported_count,
                       COALESCE(SUM(CASE WHEN %s IN ('REJECTED', 'MANUAL') THEN 1 ELSE 0 END), 0) AS failed_count
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                %s
                 WHERE COALESCE(a.agent_id, b.agent_id_snapshot) = ?
                """.formatted(DERIVED_STATUS, DERIVED_STATUS, DERIVED_STATUS, LATEST_ORDER_JOIN),
                (rs, rowNum) -> new StatusCounts(rs.getLong("timeout_count"),
                        rs.getLong("unreported_count"), rs.getLong("failed_count")), agentId);
    }

    /** 列出本机器范围内的注单；{@code status} 为 {@code null} 时不过滤派生状态。 */
    public List<OrderRow> findOrders(long agentId, String status, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(ORDER_COLUMNS)
                .append(LATEST_ORDER_JOIN)
                .append(LOCAL_DISPOSITION_JOIN)
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
                rs.getString("response_code"), rs.getString("response_message"),
                rs.getString("local_center_outcome"), rs.getString("local_disposition"),
                rs.getString("local_disposition_reason"),
                rs.getObject("local_disposition_operator_id", Long.class),
                instant(rs.getTimestamp("local_disposition_at")), instant(rs.getTimestamp("created_at"))), args.toArray());
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

    /** Current locking read used to serialize a manual resubmit with local disposition. */
    public Optional<BetRow> findBetForAgentForUpdate(long betId, long agentId) {
        return jdbc.query(BET_COLUMNS + LATEST_ORDER_JOIN
                        + " WHERE b.id = ? AND COALESCE(a.agent_id, b.agent_id_snapshot) = ? FOR UPDATE",
                (rs, rowNum) -> betRow(rs), betId, agentId).stream().findFirst();
    }

    /** Applies the machine and issue gates used when BY220 accepts a reportable bet. */
    public boolean isReportableBet(long betId, long agentId) {
        List<ReportEligibility> rows = jdbc.query("""
                SELECT b.settlement_status, b.settled_at, i.status AS issue_status,
                       i.phase AS issue_phase, i.draw_ends_at, a.board_open, a.close_seconds
                  FROM game_bet b
                  JOIN demo_user_account u ON u.id = b.user_id
                  JOIN agent a ON a.id = COALESCE(u.agent_id, b.agent_id_snapshot)
                  JOIN game_issue i ON i.game_code = b.game_code AND i.issue_number = b.issue_number
                 WHERE b.id = ? AND a.id = ? AND b.center_report_deleted_at IS NULL
                 FOR UPDATE
                """, (rs, rowNum) -> new ReportEligibility(rs.getString("settlement_status"),
                rs.getTimestamp("settled_at"), rs.getString("issue_status"), rs.getString("issue_phase"),
                rs.getTimestamp("draw_ends_at"), rs.getBoolean("board_open"), rs.getInt("close_seconds")),
                betId, agentId);
        if (rows.isEmpty()) {
            return false;
        }
        ReportEligibility row = rows.getFirst();
        if (!"PENDING".equals(row.settlementStatus()) || row.settledAt() != null
                || !"OPEN".equals(row.issueStatus()) || !"BETTING".equals(row.issuePhase())
                || !row.boardOpen()) {
            return false;
        }
        return row.closeSeconds() <= 0 || row.drawEndsAt() == null
                || !Instant.now().isAfter(row.drawEndsAt().toInstant().minusSeconds(row.closeSeconds()));
    }

    /**
     * 捞出需要上报的注单（BY220 {@code Websocket.php:658-673} 的飞单轮询）。
     *
     * <p>只取"已开启自动上报、中心账号会话有效"的机器名下的注单，且派生状态在
     * {@code statuses} 内：从未上报（{@code PENDING} 且没有上报订单行）或可安全重报的已拒单。
     * 中心 {@code PENDING/TIMEOUT} 由 {@link #findPendingAttempts(int)} 走同单恢复，不会创建新尝试。</p>
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
                + "  JOIN game_issue gi ON gi.game_code = b.game_code AND gi.issue_number = b.issue_number\n"
                + " WHERE " + DERIVED_STATUS + " IN (" + placeholders + ")\n"
                + "   AND ag.deleted_at IS NULL AND ag.status = 'ACTIVE' AND ag.system_owned = FALSE\n"
                + "   AND b.center_report_deleted_at IS NULL\n"
                + "   AND (o.id IS NULL OR NOT (o.report_type = 'CENTER' AND o.status IN ('PENDING', 'TIMEOUT')))\n"
                + "   AND ag.report_auto = TRUE AND ag.board_open = TRUE\n"
                + "   AND b.settlement_status = 'PENDING' AND b.settled_at IS NULL\n"
                + "   AND gi.status = 'OPEN' AND gi.phase = 'BETTING'\n"
                + "   AND EXISTS (SELECT 1 FROM center_machine_session s\n"
                + "                JOIN center_agent c ON c.id = s.center_agent_id\n"
                + "                JOIN agent_group cg ON cg.id = c.agent_group_id\n"
                + "               WHERE s.machine_agent_id = ag.id AND s.revoked_at IS NULL\n"
                + "                 AND s.expires_at > CURRENT_TIMESTAMP AND c.status = 'ACTIVE'\n"
                + "                 AND c.expires_at > CURRENT_TIMESTAMP AND c.agent_group_id = ag.group_id\n"
                + "                 AND cg.status = 'ACTIVE' AND cg.deleted_at IS NULL)\n"
                // 从未上报的注单立即可报；重报单必须等到退避时间点。
                + "   AND (o.id IS NULL OR o.next_retry_at IS NULL OR o.next_retry_at <= CURRENT_TIMESTAMP)\n"
                // 新单优先，其次按到点时间从早到晚；这样老失败单不会饿死新单。
                + " ORDER BY CASE WHEN o.id IS NULL THEN 0 ELSE 1 END, o.next_retry_at, b.id\n"
                + " LIMIT ?\n";
        return jdbc.query(sql, (rs, rowNum) -> betRow(rs), args.toArray());
    }

    /**
     * 安排一次重报时间点并累加尝试计数。
     *
     * <p>作用于该注单最新的那次上报尝试行。聚合派生表避免 MySQL 1093（更新目标表同时从目标表子查询）。</p>
     *
     * @param betId    注单 id
     * @param nextAt   下次可重报时间；{@code null} 表示立即可重报
     * @return 本次累加后的尝试次数
     */
    public int scheduleRetry(long attemptId, Instant nextAt) {
        int updated = jdbc.update("""
                UPDATE report_upstream_order
                   SET attempt_count = attempt_count + 1,
                       next_retry_at = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status IN ('REJECTED', 'NOT_SENT')
                """, nextAt == null ? null : Timestamp.from(nextAt), attemptId);
        if (updated == 0) {
            return -1;
        }
        Integer count = jdbc.queryForObject("SELECT attempt_count FROM report_upstream_order WHERE id = ?",
                Integer.class, attemptId);
        return count == null ? -1 : count;
    }

    /** Set the backoff time without incrementing the already-counted retry. */
    public int updateRetryAt(long attemptId, Instant nextAt) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET next_retry_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status IN ('REJECTED', 'NOT_SENT')
                """, nextAt == null ? null : Timestamp.from(nextAt), attemptId);
    }

    /** 把指定上报尝试转为人工待处置（不再自动重报）。 */
    public int markManual(long attemptId, String responseCode, String responseMessage) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET status = 'MANUAL', response_code = ?, response_message = ?,
                       next_retry_at = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status IN ('REJECTED', 'NOT_SENT')
                """, responseCode, responseMessage, attemptId);
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
                 WHERE id = (SELECT latest.id FROM
                             (SELECT MAX(id) AS id FROM report_upstream_order WHERE bet_id = ?) latest)
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
                + " WHERE " + DERIVED_STATUS + " IN (" + placeholders + ")\n"
                + "   AND ag.deleted_at IS NULL AND ag.status = 'ACTIVE' AND ag.system_owned = FALSE\n"
                + "   AND b.center_report_deleted_at IS NULL\n"
                + "   AND ag.report_auto = TRUE\n"
                + "   AND EXISTS (SELECT 1 FROM center_machine_session s\n"
                + "                JOIN center_agent c ON c.id = s.center_agent_id\n"
                + "                JOIN agent_group cg ON cg.id = c.agent_group_id\n"
                + "               WHERE s.machine_agent_id = ag.id AND s.revoked_at IS NULL\n"
                + "                 AND s.expires_at > CURRENT_TIMESTAMP AND c.status = 'ACTIVE'\n"
                + "                 AND c.expires_at > CURRENT_TIMESTAMP AND c.agent_group_id = ag.group_id\n"
                + "                 AND cg.status = 'ACTIVE' AND cg.deleted_at IS NULL)\n",
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
                rs.getObject("center_agent_id", Long.class), rs.getString("report_type"),
                rs.getInt("attempt_no"), rs.getInt("attempt_count"), rs.getString("status"),
                rs.getString("upstream_order_no"),
                rs.getString("response_code"), rs.getString("response_message"),
                instant(rs.getTimestamp("sent_at")), instant(rs.getTimestamp("resolved_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("next_retry_at")));
    }

    private static LocalDispositionRow localDispositionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LocalDispositionRow(rs.getLong("bet_id"), rs.getObject("report_attempt_id", Long.class),
                rs.getLong("agent_id_snapshot"), rs.getObject("center_agent_id_snapshot", Long.class),
                rs.getString("center_outcome"), rs.getString("decision"), rs.getString("reason"),
                rs.getLong("operator_user_id_snapshot"), instant(rs.getTimestamp("created_at")));
    }

    private static BetRow betRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BetRow(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("sys_user_id"),
                rs.getLong("agent_id"), rs.getString("issue_number"), rs.getString("play_type"),
                rs.getString("parameters_text"), rs.getBigDecimal("stake"), rs.getBigDecimal("odds_snapshot"),
                rs.getString("report_status"), rs.getString("settlement_status"), rs.getString("response_code"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 一次上报尝试。 */
    public record AttemptRow(long id, long betId, long agentId, Long centerAgentId, String reportType,
                             int attemptNo, int attemptCount, String status,
                             String upstreamOrderNo, String responseCode, String responseMessage,
                             Instant sentAt, Instant resolvedAt, Instant createdAt, Instant nextRetryAt) {
    }

    public record LocalDispositionBet(long betId, long accountId, long sysUserId, long agentId,
                                      String issueNumber, BigDecimal stake, String settlementStatus,
                                      Instant settledAt) {
    }

    public record LocalDispositionRow(long betId, Long reportAttemptId, long agentId, Long centerAgentId,
                                      String centerOutcome, String decision, String reason,
                                      long operatorUserId, Instant createdAt) {
    }

    /** 注单快照 + 派生上报状态。 */
    public record BetRow(long betId, long accountId, long sysUserId, long agentId, String issueNumber,
                         String playType, String parametersText, BigDecimal stake, BigDecimal odds,
                         String reportStatus, String settlementStatus, String responseCode) {
    }

    /** 上报情况统计。 */
    public record StatusCounts(long timeoutCount, long unreportedCount, long failedCount) {
    }

    private record ReportEligibility(String settlementStatus, Timestamp settledAt, String issueStatus,
                                     String issuePhase, Timestamp drawEndsAt, boolean boardOpen,
                                     int closeSeconds) {
    }

    /** 上报订单列表行。 */
    public record OrderRow(long betId, String betCode, long accountId, String memberCode, String displayName,
                           String issueNumber, int ballNumber, String playType, String parametersText,
                           BigDecimal stake, BigDecimal odds, String settlementStatus, String reportStatus,
                           String responseCode, String responseMessage, String localCenterOutcome,
                           String localDisposition, String localDispositionReason,
                           Long localDispositionOperatorId, Instant localDispositionAt, Instant createdAt) {
    }
}
