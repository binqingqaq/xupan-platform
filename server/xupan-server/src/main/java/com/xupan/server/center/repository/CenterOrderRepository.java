package com.xupan.server.center.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 中心订单仓储（Flyway V63 {@code center_order}）。
 *
 * <p>一次上报只对应一行：{@code (center_agent_id, agent_order_no)} 唯一，
 * 重复上报必须命中已有行而不是新建，这是"重复上报只接一次"的数据库根据（原则 6）。</p>
 */
@Repository
public class CenterOrderRepository {

    private static final String COLUMNS = """
            SELECT id, center_agent_id, agent_order_no, local_bet_id, local_agent_id, game_code, issue_number,
                   ball_number, play_type, play_text, odds, amount, risk_amount, risk_policy_version, frozen_amount,
                   machine_funded_amount, machine_odds_rate_snapshot, status, reject_code, reject_reason,
                   settle_result, settle_amount, draw_numbers, accepted_at, settled_at, created_at, updated_at
              FROM center_order
            """;

    private final JdbcTemplate jdbc;

    public CenterOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long centerAgentId, String agentOrderNo, Long localBetId, Long localAgentId,
                       String gameCode, String issueNumber, int ballNumber, String playType, String playText,
                       BigDecimal odds, BigDecimal amount, BigDecimal riskAmount, int riskPolicyVersion,
                       BigDecimal frozenAmount,
                       BigDecimal machineFundedAmount, BigDecimal machineOddsRateSnapshot,
                       String status, String rejectCode, String rejectReason, Instant acceptedAt) {
        jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, local_bet_id, local_agent_id, game_code, issue_number,
                     ball_number, play_type, play_text, odds, amount, risk_amount, risk_policy_version, frozen_amount,
                     machine_funded_amount, machine_odds_rate_snapshot, status, reject_code, reject_reason,
                     accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, centerAgentId, agentOrderNo, localBetId, localAgentId, gameCode, issueNumber,
                ballNumber, playType, playText == null ? "" : playText, odds, amount, riskAmount, riskPolicyVersion,
                frozenAmount,
                machineFundedAmount, machineOddsRateSnapshot, status, rejectCode, rejectReason,
                acceptedAt == null ? null : Timestamp.from(acceptedAt));
        Long id = jdbc.queryForObject("""
                SELECT id FROM center_order WHERE center_agent_id = ? AND agent_order_no = ?
                """, Long.class, centerAgentId, agentOrderNo);
        if (id == null) {
            throw new IllegalStateException("写入中心订单后未找到记录");
        }
        return id;
    }

    public Optional<OrderRow> findById(long id) {
        return jdbc.query(COLUMNS + " WHERE id = ?", (rs, rowNum) -> orderRow(rs), id).stream().findFirst();
    }

    public Optional<OrderRow> findByAgentAndOrderNo(long centerAgentId, String agentOrderNo) {
        return jdbc.query(COLUMNS + " WHERE center_agent_id = ? AND agent_order_no = ?",
                (rs, rowNum) -> orderRow(rs), centerAgentId, agentOrderNo).stream().findFirst();
    }

    /**
     * 某代理某期已接受的订单（开奖后做第二层结算时使用）。
     *
     * <p>只取 {@code ACCEPTED}：{@code PENDING/REJECTED/TIMEOUT} 不构成对代理的敞口，
     * {@code SETTLED} 已结算过不能被重复结算（幂等）。</p>
     */
    public List<OrderRow> findAcceptedByIssue(String gameCode, String issueNumber, int limit) {
        return jdbc.query(COLUMNS + """
                 WHERE game_code = ? AND issue_number = ? AND status = 'ACCEPTED'
                 ORDER BY id LIMIT ?
                """, (rs, rowNum) -> orderRow(rs), gameCode, issueNumber, limit);
    }

    public boolean hasConfirmedDraw(String gameCode, String issueNumber) {
        return findConfirmedDrawNumbers(gameCode, issueNumber).isPresent();
    }

    public Optional<List<Integer>> findConfirmedDrawNumbers(String gameCode, String issueNumber) {
        return jdbc.query("""
                SELECT number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8
                  FROM game_issue
                 WHERE game_code = ? AND issue_number = ? AND draw_confirmed_at IS NOT NULL
                   AND number_1 BETWEEN 1 AND 20 AND number_2 BETWEEN 1 AND 20
                   AND number_3 BETWEEN 1 AND 20 AND number_4 BETWEEN 1 AND 20
                   AND number_5 BETWEEN 1 AND 20 AND number_6 BETWEEN 1 AND 20
                   AND number_7 BETWEEN 1 AND 20 AND number_8 BETWEEN 1 AND 20
                 FOR UPDATE
                """, (rs, rowNum) -> List.of(
                rs.getInt("number_1"), rs.getInt("number_2"), rs.getInt("number_3"),
                rs.getInt("number_4"), rs.getInt("number_5"), rs.getInt("number_6"),
                rs.getInt("number_7"), rs.getInt("number_8")), gameCode, issueNumber)
                .stream().findFirst();
    }

    /** 按状态分页（中心管理端用）。 */
    public List<OrderRow> findByStatus(Long centerAgentId, String status, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(COLUMNS);
        List<Object> args = new ArrayList<>();
        if (centerAgentId != null) {
            sql.append(" WHERE center_agent_id = ?");
            args.add(centerAgentId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(args.isEmpty() ? " WHERE " : " AND ").append("status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbc.query(sql.toString(), (rs, rowNum) -> orderRow(rs), args.toArray());
    }

    public long countByStatus(Long centerAgentId, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM center_order");
        List<Object> args = new ArrayList<>();
        if (centerAgentId != null) {
            sql.append(" WHERE center_agent_id = ?");
            args.add(centerAgentId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(args.isEmpty() ? " WHERE " : " AND ").append("status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 中心管理端组合查询订单；过滤条件只用于读取，不参与接单或结算状态机。 */
    public List<OrderRow> searchOrders(OrderFilter filter, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT o.* FROM center_order o "
                + "JOIN center_agent c ON c.id = o.center_agent_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendOrderFilters(sql, args, filter);
        sql.append(" ORDER BY o.id DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbc.query(sql.toString(), (rs, rowNum) -> orderRow(rs), args.toArray());
    }

    public long countOrders(OrderFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM center_order o "
                + "JOIN center_agent c ON c.id = o.center_agent_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendOrderFilters(sql, args, filter);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private static void appendOrderFilters(StringBuilder sql, List<Object> args, OrderFilter filter) {
        if (filter.centerAgentId() != null) {
            sql.append(" AND o.center_agent_id = ?");
            args.add(filter.centerAgentId());
        }
        if (filter.groupId() != null) {
            sql.append(" AND c.agent_group_id = ?");
            args.add(filter.groupId());
        }
        if (filter.machineId() != null) {
            sql.append(" AND o.local_agent_id = ?");
            args.add(filter.machineId());
        }
        if (filter.localBetId() != null) {
            sql.append(" AND o.local_bet_id = ?");
            args.add(filter.localBetId());
        }
        if (filter.centerOrderNo() != null && !filter.centerOrderNo().isBlank()) {
            sql.append(" AND o.agent_order_no LIKE ?");
            args.add(filter.centerOrderNo().trim() + "%");
        }
        if (filter.gameCode() != null && !filter.gameCode().isBlank()) {
            sql.append(" AND o.game_code = ?");
            args.add(filter.gameCode().trim());
        }
        if (filter.issueNumber() != null && !filter.issueNumber().isBlank()) {
            sql.append(" AND o.issue_number = ?");
            args.add(filter.issueNumber().trim());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND o.status = ?");
            args.add(filter.status().trim());
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND o.created_at >= ?");
            args.add(Timestamp.from(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            sql.append(" AND o.created_at <= ?");
            args.add(Timestamp.from(filter.createdTo()));
        }
    }

    /** 按状态统计（中心总览用：待处理/已接受/已拒绝/超时/已结算…）。 */
    public List<StatusCount> countsByStatus() {
        return jdbc.query("""
                SELECT status, COUNT(*) AS total, COALESCE(SUM(amount), 0) AS amount
                  FROM center_order GROUP BY status ORDER BY status
                """, (rs, rowNum) -> new StatusCount(rs.getString("status"), rs.getLong("total"),
                rs.getBigDecimal("amount")));
    }

    /**
     * 状态迁移（带前置状态约束，保证并发与重复调用都改不动已结算的订单）。
     *
     * @return 受影响行数；0 表示状态已变化
     */
    public int updateStatus(long id, String newStatus, List<String> allowedFrom) {
        if (allowedFrom == null || allowedFrom.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(allowedFrom.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(newStatus);
        args.add(id);
        args.addAll(allowedFrom);
        return jdbc.update("UPDATE center_order SET status = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status IN (" + placeholders + ")", args.toArray());
    }

    /** 确认原待处理订单时一并固化风险分摊。 */
    public int acceptWithRiskReservation(long id, BigDecimal riskAmount, BigDecimal frozenAmount,
                                         BigDecimal machineFundedAmount) {
        return jdbc.update("""
                UPDATE center_order
                   SET status = 'ACCEPTED', risk_amount = ?, risk_policy_version = 1,
                       frozen_amount = ?, machine_funded_amount = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status IN ('TIMEOUT', 'PENDING')
                """, riskAmount, frozenAmount, machineFundedAmount, id);
    }

    /** 写入第二层结算结果（只允许从 {@code ACCEPTED} 迁移到 {@code SETTLED}）。 */
    public int settle(long id, String settleResult, BigDecimal settleAmount, String drawNumbers,
                      Instant settledAt) {
        return jdbc.update("""
                UPDATE center_order
                   SET status = 'SETTLED', settle_result = ?, settle_amount = ?, draw_numbers = ?,
                       settled_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'ACCEPTED'
                """, settleResult, settleAmount, drawNumbers,
                settledAt == null ? null : Timestamp.from(settledAt), id);
    }

    /** 该代理在本期已接受的敞口合计（风控用）。 */
    public BigDecimal acceptedExposure(long centerAgentId, String gameCode, String issueNumber) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(risk_amount), 0) FROM center_order
                 WHERE center_agent_id = ? AND game_code = ? AND issue_number = ? AND status = 'ACCEPTED'
                """, BigDecimal.class, centerAgentId, gameCode, issueNumber);
        return total == null ? BigDecimal.ZERO.setScale(2) : total;
    }

    /** 读取本地已落库投注快照，并验证注单当时所属机器及中心账号子账号归属。 */
    public Optional<LocalBetSnapshot> findLocalBetSnapshot(long centerAgentId, long localBetId,
                                                            long localAgentId) {
        return jdbc.query("""
                SELECT b.id, b.game_code, b.issue_number, b.ball_number, b.play_type, b.parameters_text,
                       b.stake, b.odds_snapshot, b.machine_odds_rate_snapshot, b.settlement_status,
                       m.id AS machine_id
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                  JOIN agent m ON m.id = COALESCE(b.machine_id_snapshot, b.agent_id_snapshot, a.agent_id)
                  JOIN center_agent c ON c.id = ? AND c.agent_group_id = m.group_id
                 WHERE b.id = ? AND m.id = ? AND m.status = 'ACTIVE' AND m.system_owned = FALSE
                   AND m.deleted_at IS NULL
                """, (rs, rowNum) -> new LocalBetSnapshot(rs.getLong("id"), rs.getString("game_code"),
                rs.getString("issue_number"), rs.getInt("ball_number"), rs.getString("play_type"),
                rs.getString("parameters_text"), rs.getBigDecimal("stake"), rs.getBigDecimal("odds_snapshot"),
                rs.getBigDecimal("machine_odds_rate_snapshot"), rs.getString("settlement_status"),
                rs.getLong("machine_id")), centerAgentId, localBetId, localAgentId).stream().findFirst();
    }

    // ---------------------------------------------------------------------
    // C-2 / C-3：订单处置（超时确认、失败确认、退款、撤单）
    // ---------------------------------------------------------------------

    /** 追加一条处置流水。幂等键唯一，重复提交抛唯一键冲突而不是写第二行。 */
    public long insertDisposition(long centerOrderId, String action, String fromStatus, String toStatus,
                                  BigDecimal amount, String reason, String idempotencyKey,
                                  Long operatorUserId) {
        jdbc.update("""
                INSERT INTO center_order_disposition
                    (center_order_id, action, from_status, to_status, amount, reason,
                     idempotency_key, operator_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, centerOrderId, action, fromStatus, toStatus,
                amount == null ? BigDecimal.ZERO.setScale(2) : amount, reason, idempotencyKey,
                operatorUserId);
        Long id = jdbc.queryForObject(
                "SELECT id FROM center_order_disposition WHERE idempotency_key = ?", Long.class, idempotencyKey);
        if (id == null) {
            throw new IllegalStateException("写入中心订单处置后未找到记录");
        }
        return id;
    }

    public Optional<DispositionRow> findDispositionByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
                SELECT id, center_order_id, action, from_status, to_status, amount, reason,
                       idempotency_key, operator_user_id, created_at
                  FROM center_order_disposition WHERE idempotency_key = ?
                """, (rs, rowNum) -> dispositionRow(rs), idempotencyKey).stream().findFirst();
    }

    /** Current read used after a failed order CAS to replay a concurrent identical disposition. */
    public Optional<DispositionRow> findDispositionByIdempotencyKeyForUpdate(String idempotencyKey) {
        return jdbc.query("""
                SELECT id, center_order_id, action, from_status, to_status, amount, reason,
                       idempotency_key, operator_user_id, created_at
                  FROM center_order_disposition WHERE idempotency_key = ? FOR UPDATE
                """, (rs, rowNum) -> dispositionRow(rs), idempotencyKey).stream().findFirst();
    }

    public List<DispositionRow> findDispositions(long centerOrderId) {
        return jdbc.query("""
                SELECT id, center_order_id, action, from_status, to_status, amount, reason,
                       idempotency_key, operator_user_id, created_at
                  FROM center_order_disposition WHERE center_order_id = ?
                 ORDER BY id
                """, (rs, rowNum) -> dispositionRow(rs), centerOrderId);
    }

    /** 按状态列出订单（管理端"待处置"列表：TIMEOUT / PENDING 需要人工介入）。 */
    public List<OrderRow> findByStatuses(List<String> statuses, int limit) {
        if (statuses == null || statuses.isEmpty() || limit <= 0) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        List<Object> args = new ArrayList<>(statuses);
        args.add(limit);
        return jdbc.query(COLUMNS + " WHERE status IN (" + placeholders + ") ORDER BY id LIMIT ?",
                (rs, rowNum) -> orderRow(rs), args.toArray());
    }

    private static DispositionRow dispositionRow(ResultSet rs) throws SQLException {
        return new DispositionRow(rs.getLong("id"), rs.getLong("center_order_id"), rs.getString("action"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getBigDecimal("amount"),
                rs.getString("reason"), rs.getObject("operator_user_id", Long.class),
                instant(rs.getTimestamp("created_at")));
    }

    private static OrderRow orderRow(ResultSet rs) throws SQLException {
        return new OrderRow(rs.getLong("id"), rs.getLong("center_agent_id"), rs.getString("agent_order_no"),
                rs.getObject("local_bet_id", Long.class), rs.getObject("local_agent_id", Long.class),
                rs.getString("game_code"), rs.getString("issue_number"),
                rs.getObject("ball_number", Integer.class), rs.getString("play_type"),
                rs.getString("play_text"), rs.getBigDecimal("odds"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("risk_amount"), rs.getInt("risk_policy_version"),
                rs.getBigDecimal("machine_funded_amount"),
                rs.getBigDecimal("machine_odds_rate_snapshot"),
                rs.getString("status"), rs.getString("reject_code"), rs.getString("reject_reason"),
                rs.getBigDecimal("frozen_amount"), rs.getString("settle_result"),
                rs.getBigDecimal("settle_amount"), rs.getString("draw_numbers"),
                instant(rs.getTimestamp("accepted_at")), instant(rs.getTimestamp("settled_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 一行中心订单。 */
    public record OrderRow(long id, long centerAgentId, String agentOrderNo, Long localBetId, Long localAgentId,
                           String gameCode, String issueNumber, Integer ballNumber, String playType, String playText,
                           BigDecimal odds, BigDecimal amount, BigDecimal riskAmount, int riskPolicyVersion,
                           BigDecimal machineFundedAmount, BigDecimal machineOddsRateSnapshot,
                           String status, String rejectCode, String rejectReason, BigDecimal frozenAmount, String settleResult,
                           BigDecimal settleAmount, String drawNumbers, Instant acceptedAt, Instant settledAt,
                           Instant createdAt, Instant updatedAt) {
    }

    public record LocalBetSnapshot(long betId, String gameCode, String issueNumber, int ballNumber, String playType,
                                   String parametersText, BigDecimal stake, BigDecimal odds,
                                   BigDecimal machineOddsRateSnapshot, String settlementStatus, long machineId) {
    }

    /** 按状态的统计行。 */
    public record StatusCount(String status, long total, BigDecimal amount) {
    }

    public record OrderFilter(Long centerAgentId, Long groupId, Long machineId, Long localBetId,
                              String centerOrderNo, String gameCode, String issueNumber, String status,
                              Instant createdFrom, Instant createdTo) {
    }

    /** 一条中心订单处置流水。 */
    public record DispositionRow(long id, long centerOrderId, String action, String fromStatus,
                                 String toStatus, BigDecimal amount, String reason,
                                 Long operatorUserId, Instant createdAt) {
    }
}
