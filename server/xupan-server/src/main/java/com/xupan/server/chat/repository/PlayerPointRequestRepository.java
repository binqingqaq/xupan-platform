package com.xupan.server.chat.repository;

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

    public List<Request> findPending(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("积分申请查询数量必须在 1 到 200 之间");
        }
        return jdbcTemplate.query("""
                SELECT r.id, r.user_id, r.request_type, r.amount, r.status, r.client_message_id,
                       r.source_message_id, r.requested_at, u.display_name,
                       a.member_code, a.player_kind
                  FROM player_point_request r
                  JOIN sys_user u ON u.id = r.user_id
                  LEFT JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE r.status = 'PENDING'
                 ORDER BY r.requested_at, r.id
                 LIMIT ?
                """, this::mapRequest, limit);
    }

    public Optional<Request> findByIdForUpdate(long requestId) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("积分申请编号无效");
        }
        return jdbcTemplate.query("""
                SELECT r.id, r.user_id, r.request_type, r.amount, r.status, r.client_message_id,
                       r.source_message_id, r.requested_at, u.display_name,
                       a.member_code, a.player_kind
                  FROM player_point_request r
                  JOIN sys_user u ON u.id = r.user_id
                  LEFT JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE r.id = ?
                  FOR UPDATE
                """, this::mapRequest, requestId).stream().findFirst();
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
