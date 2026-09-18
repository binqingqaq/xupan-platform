package com.xupan.server.chat.realtime;

import com.xupan.server.chat.domain.ChatMessageStatus;
import com.xupan.server.chat.domain.ChatMessageType;
import com.xupan.server.chat.domain.ChatSenderType;
import com.xupan.server.chat.web.ChatMessageResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesKnownClientEventWithoutTrustingIdentityFields() {
        ChatProtocol.ClientEvent event = ChatProtocol.parseClientEvent(objectMapper,
                "{\"type\":\"message.send\",\"clientMessageId\":\"m-1\","
                        + "\"content\":\"hello\",\"userId\":999}");

        assertThat(event.type()).isEqualTo("message.send");
        assertThat(event.clientMessageId()).isEqualTo("m-1");
        assertThat(event.content()).isEqualTo("hello");
    }

    @Test
    void rejectsInvalidJsonUnknownEventsAndOversizedFrames() {
        assertThatThrownBy(() -> ChatProtocol.parseClientEvent(objectMapper, "{"))
                .isInstanceOf(ChatProtocol.ProtocolException.class)
                .extracting(exception -> ((ChatProtocol.ProtocolException) exception).code())
                .isEqualTo(ChatProtocol.ErrorCode.INVALID_JSON);
        assertThatThrownBy(() -> ChatProtocol.parseClientEvent(objectMapper,
                "{\"type\":\"robot\"}"))
                .isInstanceOf(ChatProtocol.ProtocolException.class)
                .extracting(exception -> ((ChatProtocol.ProtocolException) exception).code())
                .isEqualTo(ChatProtocol.ErrorCode.UNKNOWN_EVENT);
        assertThatThrownBy(() -> ChatProtocol.parseClientEvent(objectMapper,
                "{\"type\":\"message.send\",\"content\":\"hello\"}"))
                .isInstanceOf(ChatProtocol.ProtocolException.class)
                .extracting(exception -> ((ChatProtocol.ProtocolException) exception).code())
                .isEqualTo(ChatProtocol.ErrorCode.MISSING_FIELD);
        assertThatThrownBy(() -> ChatProtocol.parseClientEvent(objectMapper,
                "x".repeat(ChatProtocol.MAX_FRAME_BYTES + 1)))
                .isInstanceOf(ChatProtocol.ProtocolException.class)
                .extracting(exception -> ((ChatProtocol.ProtocolException) exception).code())
                .isEqualTo(ChatProtocol.ErrorCode.FRAME_TOO_LARGE);
    }

    @Test
    void encodesPersistedMessageProjectionAsServerEvent() {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        ChatMessageResponse message = new ChatMessageResponse(1L, 2L, "m-1", ChatMessageType.USER_CHAT.name(),
                ChatSenderType.USER.name(), 7L, "用户甲", "hello", null,
                ChatMessageStatus.ACTIVE.name(), now, now);

        String json = ChatProtocol.encode(objectMapper, ChatProtocol.messageCreated(message));

        assertThat(json).contains("\"type\":\"message.created\"", "\"sequenceNo\":2",
                "\"senderId\":7", "\"content\":\"hello\"", "\"message\":{");
    }

    @Test
    void measuresWirePayloadInUtf8BytesAndRejectsOversizedServerEvents() {
        assertThat(ChatProtocol.utf8Bytes("中文😀")).isEqualTo(10);

        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        ChatMessageResponse message = new ChatMessageResponse(1L, 2L, "m-1", ChatMessageType.USER_CHAT.name(),
                ChatSenderType.USER.name(), 7L, "用户甲", "x".repeat(200), null,
                ChatMessageStatus.ACTIVE.name(), now, now);

        assertThatThrownBy(() -> ChatProtocol.encode(objectMapper, ChatProtocol.messageCreated(message), 100))
                .isInstanceOf(ChatProtocol.ProtocolException.class)
                .extracting(exception -> ((ChatProtocol.ProtocolException) exception).code())
                .isEqualTo(ChatProtocol.ErrorCode.FRAME_TOO_LARGE);
    }
}
