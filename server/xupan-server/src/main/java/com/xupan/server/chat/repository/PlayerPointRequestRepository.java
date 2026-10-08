package com.xupan.server.chat.repository;

import com.xupan.server.system.service.PlayerDeskScopeService.Scope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PlayerPointRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlayerPointRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 下分申请**预扣**流水的幂等键（BY220 {@code common.php:1874-1876} 的 {@code setDec}）。
     *
     * <p>键绑定申请编号，因此"客户端消息重发 / 创建流程重试"只会命中同一幂等键重放，
     * 不会第二次扣玩家余额（表级 {@code uk_demo_balance_ledger_idempotency} 兜底）。</p>
     */
    public static String holdIdempotencyKey(long requestId) {
        return "POINT-REQUEST-HOLD:" + requestId;
    }

    /** 下分申请**拒绝退回**流水的幂等键（BY220 {@code rbt/controller/Index.php:312-314} 的 {@code setInc}）。 */
    public static String refundIdempotencyKey(long requestId) {
        return "POINT-REQUEST-REFUND:" + requestId;
    }

    public Request insertPending(long userId, String requestType, BigDecimal amount,
                                 String clientMessageId, long sourceMessageId, Instant requestedAt) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO player_point_request
                        (user_id, request_type, amount, status, client_message_id, source_message_id, requested_at)
                    VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                    """, userId, requestType, amount, clientMessageId, sourceMessageId,
                    java.sql.Timestamp.from(requestedAt));
        } catch (DuplicateKeyException duplicate) {
            // A retried chat message returns the original pending request below.
        }
        return jdbcTemplate.query("""
                SELECT r.id, r.user_id, r.request_type, r.amount, r.status, r.client_message_id,
                       r.source_message_id, r.requested_at, u.display_name,
                       a.member_code, a.player_kind
                  FROM player_point_request r
                  JOIN sys_user u ON u.id = r.user_id
                  LEFT JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE r.user_id = ? AND r.client_message_id = ?
                """, this::mapRequest, userId, clientMessageId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("创建积分申请后未找到申请记录"));
    }

    public List<Request> findPending(int limit, Scope scope) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("积分申请查询数量必须在 1 到 200 之间");
        }
        List<Object> args = new java.util.ArrayList<>(scope.agentIdParameters());
        args.add(limit);
        return jdbcTemplate.query("""
                SELECT r.id, r.user_id, r.request_type, r.amount, r.status, r.client_message_id,
                       r.source_message_id, r.requested_at, u.display_name,
                       a.member_code, a.player_kind
                  FROM player_point_request r
                  JOIN sys_user u ON u.id = r.user_id
                  LEFT JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE r.status = 'PENDING'
                """ + scope.agentIdCondition() + """
                 ORDER BY r.requested_at, r.id
                 LIMIT ?
                """, this::mapRequest, args.toArray());
    }

    public Optional<Request> findByIdForUpdate(long requestId, Scope scope) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("积分申请编号无效");
        }
        List<Object> args = new java.util.ArrayList<>();
        args.add(requestId);
        args.addAll(scope.agentIdParameters());
        return jdbcTemplate.query("""
                SELECT r.id, r.user_id, r.request_type, r.amount, r.status, r.client_message_id,
                       r.source_message_id, r.requested_at, u.display_name,
                       a.member_code, a.player_kind
                  FROM player_point_request r
                  JOIN sys_user u ON u.id = r.user_id
                  LEFT JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE r.id = ?
                """ + scope.agentIdCondition() + """
                  FOR UPDATE
                """, this::mapRequest, args.toArray()).stream().findFirst();
    }

    public boolean markReviewed(long requestId, String status, long reviewerUserId,
                                String reason, Instant reviewedAt) {
        return jdbcTemplate.update("""
                UPDATE player_point_request
                   SET status = ?, reviewed_at = ?, reviewer_user_id = ?, review_reason = ?
                 WHERE id = ? AND status = 'PENDING'
                """, status, java.sql.Timestamp.from(reviewedAt), reviewerUserId, reason, requestId) == 1;
    }

    private Request mapRequest(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Request(rs.getLong("id"), rs.getLong("user_id"), rs.getString("request_type"),
                rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("client_message_id"),
                rs.getLong("source_message_id"), rs.getTimestamp("requested_at").toInstant(),
                rs.getString("display_name"), rs.getString("member_code"), rs.getString("player_kind"));
    }

    public record Request(long id, long userId, String requestType, BigDecimal amount,
                          String status, String clientMessageId, long sourceMessageId,
                          Instant requestedAt, String displayName, String memberCode,
                          String playerKind) {
    }
}
