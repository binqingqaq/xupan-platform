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
                   play_type, play_text, odds, amount, status, reject_code, reject_reason, frozen_amount,
                   settle_result, settle_amount, draw_numbers, accepted_at, settled_at, created_at, updated_at
              FROM center_order
            """;

    private final JdbcTemplate jdbc;

    public CenterOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long centerAgentId, String agentOrderNo, Long localBetId, Long localAgentId,
                       String gameCode, String issueNumber, String playType, String playText, BigDecimal odds,
                       BigDecimal amount, String status, String rejectCode, String rejectReason,
                       BigDecimal frozenAmount, Instant acceptedAt) {
        jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, local_bet_id, local_agent_id, game_code, issue_number,
                     play_type, play_text, odds, amount, status, reject_code, reject_reason, frozen_amount,
                     accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, centerAgentId, agentOrderNo, localBetId, localAgentId, gameCode, issueNumber,
                playType, playText, odds, amount, status, rejectCode, rejectReason, frozenAmount,
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
                SELECT COALESCE(SUM(amount), 0) FROM center_order
                 WHERE center_agent_id = ? AND game_code = ? AND issue_number = ? AND status = 'ACCEPTED'
                """, BigDecimal.class, centerAgentId, gameCode, issueNumber);
        return total == null ? BigDecimal.ZERO.setScale(2) : total;
    }

    private static OrderRow orderRow(ResultSet rs) throws SQLException {
        return new OrderRow(rs.getLong("id"), rs.getLong("center_agent_id"), rs.getString("agent_order_no"),
                rs.getObject("local_bet_id", Long.class), rs.getObject("local_agent_id", Long.class),
                rs.getString("game_code"), rs.getString("issue_number"), rs.getString("play_type"),
                rs.getString("play_text"), rs.getBigDecimal("odds"), rs.getBigDecimal("amount"),
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
                           String gameCode, String issueNumber, String playType, String playText,
                           BigDecimal odds, BigDecimal amount, String status, String rejectCode,
                           String rejectReason, BigDecimal frozenAmount, String settleResult,
                           BigDecimal settleAmount, String drawNumbers, Instant acceptedAt, Instant settledAt,
                           Instant createdAt, Instant updatedAt) {
    }

    /** 按状态的统计行。 */
    public record StatusCount(String status, long total, BigDecimal amount) {
    }
}
