package com.xupan.server.chat.repository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class ChatReadCursorRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatReadCursorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long currentReadSequence(long roomId, long userId) {
        Long value = jdbcTemplate.query("""
                SELECT last_read_sequence
                  FROM chat_read_cursor
                 WHERE room_id = ? AND user_id = ?
                """, (rs, rowNum) -> rs.getLong(1), roomId, userId)
                .stream().findFirst().orElse(null);
        return value == null ? 0 : value;
    }

    public void saveReadSequence(long roomId, long userId, long sequenceNo, Instant updatedAt) {
        requireTransaction("saveReadSequence");
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("CHAT_CURSOR_INVALID: 已读游标不能为负数");
        }
        int updated = jdbcTemplate.update("""
                UPDATE chat_read_cursor
                   SET last_read_sequence = ?, updated_at = ?
                 WHERE room_id = ? AND user_id = ? AND last_read_sequence < ?
                """, sequenceNo, Timestamp.from(updatedAt), roomId, userId, sequenceNo);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO chat_read_cursor
                        (room_id, user_id, last_read_sequence, updated_at)
                    SELECT ?, ?, ?, ?
                     WHERE NOT EXISTS (
                         SELECT 1 FROM chat_read_cursor WHERE room_id = ? AND user_id = ?
                     )
                    """, roomId, userId, sequenceNo, Timestamp.from(updatedAt), roomId, userId);
        } catch (DataIntegrityViolationException duplicate) {
            jdbcTemplate.update("""
                    UPDATE chat_read_cursor
                       SET last_read_sequence = GREATEST(last_read_sequence, ?), updated_at = ?
                     WHERE room_id = ? AND user_id = ?
                    """, sequenceNo, Timestamp.from(updatedAt), roomId, userId);
        }
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }
}
