package com.xupan.server.chat.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessageStatus;
import com.xupan.server.chat.domain.ChatMessageType;
import com.xupan.server.chat.domain.ChatSenderType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatMessageRepository {

    private static final String COLUMNS = """
            SELECT m.id, m.room_id, r.room_code, m.sequence_no, m.client_message_id,
                   m.idempotency_key, m.issue_number, m.message_type, m.sender_type,
                   m.sender_id, m.sender_name, m.content, m.payload_json, m.status,
                   m.created_at, m.updated_at
              FROM chat_message m
              JOIN chat_room r ON r.id = m.room_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatMessageRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<ChatMessage> findByClientMessageId(long roomId, long userId, String clientMessageId) {
        return jdbcTemplate.query(COLUMNS + """
                 WHERE m.room_id = ? AND m.sender_id = ? AND m.client_message_id = ?
                """, this::mapMessage, roomId, userId, clientMessageId).stream().findFirst();
    }

    public Optional<ChatMessage> findByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query(COLUMNS + " WHERE m.idempotency_key = ?", this::mapMessage,
                        idempotencyKey).stream().findFirst();
    }

    public ChatMessage insertUserMessage(long roomId, long sequenceNo, long userId,
                                         String senderName, String clientMessageId,
                                         String content, Instant createdAt) {
        return insertUserMessage(roomId, sequenceNo, userId, senderName, clientMessageId,
                null, ChatMessageType.USER_CHAT, content, null, createdAt);
    }

    public ChatMessage insertUserBetMessage(long roomId, long sequenceNo, long userId,
                                            String senderName, String clientMessageId,
                                            String issueNumber, String content,
                                            String payloadJson, Instant createdAt) {
        return insertUserMessage(roomId, sequenceNo, userId, senderName, clientMessageId,
                issueNumber, ChatMessageType.USER_BET, content, payloadJson, createdAt);
    }

    private ChatMessage insertUserMessage(long roomId, long sequenceNo, long userId,
                                          String senderName, String clientMessageId,
                                          String issueNumber, ChatMessageType messageType,
                                          String content, String payloadJson, Instant createdAt) {
        requireTransaction("insertUserMessage");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_message
                        (room_id, sequence_no, client_message_id, idempotency_key,
                         issue_number, message_type, sender_type, sender_id, sender_name,
                         content, payload_json, status, created_at, updated_at)
                    VALUES (?, ?, ?, NULL, ?, ?, 'USER', ?, ?, ?,
                            CASE WHEN ? IS NULL THEN NULL ELSE CAST(? AS JSON) END,
                            'ACTIVE', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, roomId);
            statement.setLong(2, sequenceNo);
            statement.setString(3, clientMessageId);
            statement.setString(4, issueNumber);
            statement.setString(5, messageType.name());
            statement.setLong(6, userId);
            statement.setString(7, senderName);
            statement.setString(8, content);
            statement.setString(9, payloadJson);
            statement.setString(10, payloadJson);
            statement.setTimestamp(11, Timestamp.from(createdAt));
            statement.setTimestamp(12, Timestamp.from(createdAt));
            return statement;
        }, keyHolder);
        Number key = generatedMessageId(keyHolder);
        if (key == null) {
            throw new IllegalStateException("写入聊天消息后未取得消息 ID");
        }
        return findById(key.longValue()).orElseThrow(() ->
                new IllegalStateException("写入聊天消息后未找到消息"));
    }

    public ChatMessage insertRobotMessage(long roomId, long sequenceNo, long robotId,
                                          String robotName, String issueNumber,
                                          String idempotencyKey, String content,
                                          String payloadJson, Instant createdAt) {
        requireTransaction("insertRobotMessage");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_message
                        (room_id, sequence_no, client_message_id, idempotency_key,
                         issue_number, message_type, sender_type, sender_id, sender_name,
                         content, payload_json, status, created_at, updated_at)
                    VALUES (?, ?, NULL, ?, ?, 'ROBOT', 'ROBOT', ?, ?, ?, CAST(? AS JSON),
                            'ACTIVE', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, roomId);
            statement.setLong(2, sequenceNo);
            statement.setString(3, idempotencyKey);
            statement.setString(4, issueNumber);
            statement.setLong(5, robotId);
            statement.setString(6, robotName);
            statement.setString(7, content);
            statement.setString(8, payloadJson);
            statement.setTimestamp(9, Timestamp.from(createdAt));
            statement.setTimestamp(10, Timestamp.from(createdAt));
            return statement;
        }, keyHolder);
        Number key = generatedMessageId(keyHolder);
        if (key == null) {
            throw new IllegalStateException("写入机器人聊天消息后未取得消息 ID");
        }
        return findById(key.longValue()).orElseThrow(() ->
                new IllegalStateException("写入机器人聊天消息后未找到消息"));
    }

    public int updateRobotSenderName(long robotId, String senderName) {
        if (robotId <= 0 || senderName == null || senderName.isBlank()) {
            throw new IllegalArgumentException("机器人消息名称参数无效");
        }
        return jdbcTemplate.update("""
                UPDATE chat_message
                 SET sender_name = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE sender_type = 'ROBOT' AND sender_id = ? AND sender_name <> ?
                """, senderName, robotId, senderName);
    }

    private static Number generatedMessageId(KeyHolder keyHolder) {
        if (keyHolder.getKeys() != null) {
            for (var entry : keyHolder.getKeys().entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                    return number;
                }
            }
        }
        return keyHolder.getKey();
    }

    public List<ChatMessage> findBeforeSequence(long roomId, long beforeSequence, int limit) {
        validateLimit(limit);
        return jdbcTemplate.query(COLUMNS + """
                 WHERE m.room_id = ? AND m.sequence_no < ?
                 ORDER BY m.sequence_no DESC
                 LIMIT ?
                """, this::mapMessage, roomId, beforeSequence, limit + 1);
    }

    public List<ChatMessage> findAfterSequence(long roomId, long afterSequence, int limit) {
        validateLimit(limit);
        return jdbcTemplate.query(COLUMNS + """
                 WHERE m.room_id = ? AND m.sequence_no > ?
                 ORDER BY m.sequence_no ASC
                 LIMIT ?
                """, this::mapMessage, roomId, afterSequence, limit + 1);
    }

    private Optional<ChatMessage> findById(long id) {
        return jdbcTemplate.query(COLUMNS + " WHERE m.id = ?", this::mapMessage, id)
                .stream().findFirst();
    }

    private ChatMessage mapMessage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChatMessage(rs.getLong("id"), rs.getLong("room_id"),
                rs.getString("room_code"), rs.getLong("sequence_no"),
                rs.getString("client_message_id"), rs.getString("idempotency_key"),
                rs.getString("issue_number"), ChatMessageType.valueOf(rs.getString("message_type")),
                ChatSenderType.valueOf(rs.getString("sender_type")), nullableLong(rs, "sender_id"),
                rs.getString("sender_name"), rs.getString("content"), jsonText(rs, "payload_json"),
                ChatMessageStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private String jsonText(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        String text = value instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : value.toString();
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node != null && node.isTextual()) {
                String nestedJson = node.textValue();
                if (nestedJson != null && (nestedJson.trim().startsWith("{")
                        || nestedJson.trim().startsWith("["))) {
                    return nestedJson;
                }
            }
        } catch (JacksonException ignored) {
            // 保留数据库原始值，由上层 JSON 校验决定如何处理异常数据。
        }
        return text;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("CHAT_MESSAGE_LIMIT_INVALID: limit 必须在 1 到 100 之间");
        }
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }
}
