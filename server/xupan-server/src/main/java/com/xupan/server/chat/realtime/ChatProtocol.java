package com.xupan.server.chat.realtime;

import com.xupan.server.chat.web.ChatMessageResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/** Structured wire contract shared by the WebSocket handler and its tests. */
public final class ChatProtocol {

    public static final int MAX_FRAME_BYTES = 4096;

    private ChatProtocol() {
    }

    public enum ClientEventType {
        SUBSCRIBE("subscribe"),
        MESSAGE_SEND("message.send"),
        CURSOR_ACK("cursor.ack"),
        PING("ping");

        private final String wireName;

        ClientEventType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static ClientEventType fromWire(String value) {
            for (ClientEventType type : values()) {
                if (type.wireName.equals(value)) {
                    return type;
                }
            }
            throw new ProtocolException(ErrorCode.UNKNOWN_EVENT, "不支持的聊天室事件");
        }
    }

    public enum ServerEventType {
        CONNECTED("connected"),
        SYNC_REQUIRED("sync.required"),
        MESSAGE_CREATED("message.created"),
        MESSAGE_ACK("message.ack"),
        CURSOR_ACK("cursor.ack"),
        PONG("pong"),
        ERROR("error"),
        SYNC_COMPLETE("sync.complete");

        private final String wireName;

        ServerEventType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum ErrorCode {
        INVALID_JSON("CHAT_PROTOCOL_INVALID_JSON"),
        FRAME_TOO_LARGE("CHAT_PROTOCOL_FRAME_TOO_LARGE"),
        MISSING_EVENT_TYPE("CHAT_PROTOCOL_EVENT_TYPE_REQUIRED"),
        MISSING_FIELD("CHAT_PROTOCOL_FIELD_REQUIRED"),
        UNKNOWN_EVENT("CHAT_PROTOCOL_UNKNOWN_EVENT"),
        UNSUPPORTED_EVENT("CHAT_PROTOCOL_EVENT_NOT_READY"),
        CONNECTION_LIMIT("CHAT_CONNECTION_LIMIT"),
        ROOM_FORBIDDEN("CHAT_ROOM_FORBIDDEN"),
        SUBSCRIPTION_REQUIRED("CHAT_SUBSCRIPTION_REQUIRED"),
        MESSAGE_RATE_LIMITED("CHAT_MESSAGE_RATE_LIMITED"),
        MESSAGE_REJECTED("CHAT_MESSAGE_REJECTED");

        private final String code;

        ErrorCode(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public enum ConnectionState {
        CONNECTED,
        READY,
        CLOSING,
        CLOSED
    }

    public record ClientEvent(String type, String roomCode, String clientMessageId,
                              String content, Long afterSequence, Long sequence, String nonce) {
    }

    public record ServerEvent(String type, String connectionId, String roomCode,
                              String serverTime, Long afterSequence, Long latestSequence,
                              Long sequence, String nonce, String code, Object message,
                              Boolean deduplicated,
                              String clientMessageId) {
    }

    public static ClientEvent parseClientEvent(ObjectMapper objectMapper, String payload) {
        return parseClientEvent(objectMapper, payload, MAX_FRAME_BYTES);
    }

    public static ClientEvent parseClientEvent(ObjectMapper objectMapper, String payload, int maxFrameBytes) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes 必须大于 0");
        }
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE, "聊天室消息帧过大");
        }
        final ClientEvent event;
        try {
            event = objectMapper.readValue(payload, ClientEvent.class);
        } catch (JacksonException exception) {
            throw new ProtocolException(ErrorCode.INVALID_JSON, "聊天室消息格式无效");
        }
        if (event == null || event.type() == null || event.type().isBlank()) {
            throw new ProtocolException(ErrorCode.MISSING_EVENT_TYPE, "聊天室事件类型不能为空");
        }
        ClientEventType eventType = ClientEventType.fromWire(event.type());
        if (eventType == ClientEventType.MESSAGE_SEND
                && (event.clientMessageId() == null || event.clientMessageId().isBlank()
                || event.content() == null)) {
            throw new ProtocolException(ErrorCode.MISSING_FIELD, "消息发送缺少必要字段");
        }
        if (eventType == ClientEventType.SUBSCRIBE
                && (event.roomCode() == null || event.roomCode().isBlank())) {
            throw new ProtocolException(ErrorCode.MISSING_FIELD, "聊天室订阅缺少房间编码");
        }
        if (eventType == ClientEventType.SUBSCRIBE
                && event.afterSequence() != null && event.afterSequence() < 0) {
            throw new ProtocolException(ErrorCode.MISSING_FIELD, "聊天室同步游标无效");
        }
        if (eventType == ClientEventType.CURSOR_ACK
                && (event.sequence() == null || event.sequence() < 0)) {
            throw new ProtocolException(ErrorCode.MISSING_FIELD, "已读游标缺少必要字段");
        }
        return event;
    }

    public static String encode(ObjectMapper objectMapper, ServerEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("聊天室事件序列化失败", exception);
        }
    }

    public static ServerEvent connected(String connectionId, String roomCode, Instant serverTime) {
        return new ServerEvent(ServerEventType.CONNECTED.wireName(), connectionId, roomCode,
                serverTime.toString(), null, null, null, null, null, null, null, null);
    }

    public static ServerEvent messageCreated(ChatMessageResponse message) {
        return new ServerEvent(ServerEventType.MESSAGE_CREATED.wireName(), null, null,
                message.createdAt().toString(), null, null, message.sequenceNo(), null,
                null, message, null, null);
    }

    public static ServerEvent messageAck(String clientMessageId, ChatMessageResponse message,
                                         boolean deduplicated) {
        return new ServerEvent(ServerEventType.MESSAGE_ACK.wireName(), null, null,
                message.createdAt().toString(), null, null, message.sequenceNo(), null,
                null, message, deduplicated, clientMessageId);
    }

    public static ServerEvent syncRequired(long afterSequence) {
        return new ServerEvent(ServerEventType.SYNC_REQUIRED.wireName(), null, null,
                Instant.now().toString(), afterSequence, null, null, null, null, null,
                null, null);
    }

    public static ServerEvent syncComplete(long afterSequence, long latestSequence) {
        return new ServerEvent(ServerEventType.SYNC_COMPLETE.wireName(), null, null,
                Instant.now().toString(), afterSequence, latestSequence, null, null, null, null,
                null, null);
    }

    public static ServerEvent cursorAck(long sequence) {
        return new ServerEvent(ServerEventType.CURSOR_ACK.wireName(), null, null,
                Instant.now().toString(), null, null, sequence, null, null, null,
                null, null);
    }

    public static ServerEvent pong(String nonce, Instant serverTime) {
        return new ServerEvent(ServerEventType.PONG.wireName(), null, null, serverTime.toString(),
                null, null, null, nonce, null, null, null, null);
    }

    public static ServerEvent error(ErrorCode code, String message) {
        return error(code.code(), message, null);
    }

    public static ServerEvent error(ErrorCode code, String message, String clientMessageId) {
        return error(code.code(), message, clientMessageId);
    }

    public static ServerEvent error(String code, String message, String clientMessageId) {
        return new ServerEvent(ServerEventType.ERROR.wireName(), null, null, Instant.now().toString(),
                null, null, null, null, code, message, null, clientMessageId);
    }

    public static final class ProtocolException extends IllegalArgumentException {
        private final ErrorCode code;

        public ProtocolException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }
}
