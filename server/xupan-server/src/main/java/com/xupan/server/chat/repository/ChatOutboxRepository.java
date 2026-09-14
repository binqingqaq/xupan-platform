package com.xupan.server.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class ChatOutboxRepository {

    private static final String PENDING_STATUS = "PENDING";

    private final JdbcTemplate jdbcTemplate;

    public ChatOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertMessageCreatedOutbox(long messageId, String payloadJson, Instant createdAt) {
        requireTransaction("insertMessageCreatedOutbox");
        if (messageId <= 0 || payloadJson == null || payloadJson.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("CHAT_OUTBOX_INVALID: outbox 参数无效");
        }
        jdbcTemplate.update("""
                INSERT INTO chat_outbox
                    (message_id, event_type, payload_json, status, attempt_count, created_at, updated_at)
                VALUES (?, 'MESSAGE_CREATED', ?, 'PENDING', 0, ?, ?)
                """, messageId, payloadJson, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    public long countPending() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM chat_outbox
                 WHERE status = ?
                """, Long.class, PENDING_STATUS);
        return count == null ? 0L : count;
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }
}
