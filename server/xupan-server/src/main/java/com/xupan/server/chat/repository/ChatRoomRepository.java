package com.xupan.server.chat.repository;

import com.xupan.server.chat.domain.ChatRoom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

@Repository
public class ChatRoomRepository {

    private static final String COLUMNS = """
            SELECT id, room_code, display_name, status, message_retention_days, next_sequence_no
              FROM chat_room
            """;

    private final JdbcTemplate jdbcTemplate;

    public ChatRoomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ChatRoom> findByCode(String roomCode) {
        return jdbcTemplate.query(COLUMNS + " WHERE room_code = ?", this::mapRoom, roomCode)
                .stream().findFirst();
    }

    public Optional<ChatRoom> findByCodeForUpdate(String roomCode) {
        requireTransaction("findByCodeForUpdate");
        return jdbcTemplate.query(COLUMNS + " WHERE room_code = ? FOR UPDATE", this::mapRoom, roomCode)
                .stream().findFirst();
    }

    public long allocateNextSequence(long roomId, long currentSequenceNo) {
        requireTransaction("allocateNextSequence");
        if (roomId <= 0 || currentSequenceNo < 0) {
            throw new IllegalArgumentException("CHAT_SEQUENCE_INVALID: 房间序号无效");
        }
        int updated = jdbcTemplate.update("""
                UPDATE chat_room
                   SET next_sequence_no = next_sequence_no + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND next_sequence_no = ?
                """, roomId, currentSequenceNo);
        if (updated != 1) {
            throw new IllegalStateException("CHAT_SEQUENCE_CONCURRENT: 房间序号已变化");
        }
        return currentSequenceNo + 1;
    }

    private ChatRoom mapRoom(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChatRoom(rs.getLong("id"), rs.getString("room_code"),
                rs.getString("display_name"), rs.getString("status"),
                rs.getInt("message_retention_days"), rs.getLong("next_sequence_no"));
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }
}
