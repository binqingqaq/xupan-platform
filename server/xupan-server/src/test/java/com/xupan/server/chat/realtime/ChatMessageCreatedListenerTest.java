package com.xupan.server.chat.realtime;

import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessageStatus;
import com.xupan.server.chat.domain.ChatMessageType;
import com.xupan.server.chat.domain.ChatSenderType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatMessageCreatedListenerTest {

    @Test
    void broadcastsThePersistedMessageProjectionOnce() {
        ChatRealtimeBroadcaster broadcaster = mock(ChatRealtimeBroadcaster.class);
        ChatMessageCreatedListener listener = new ChatMessageCreatedListener(broadcaster);
        ChatMessage message = new ChatMessage(1L, 1L, "main", 1L, "client-1", null, null,
                ChatMessageType.USER_CHAT, ChatSenderType.USER, 7L, "用户甲", "hello", null,
                ChatMessageStatus.ACTIVE, Instant.parse("2026-09-11T00:00:00Z"),
                Instant.parse("2026-09-11T00:00:00Z"));

        listener.onMessageCreated(new ChatMessageCreatedEvent(message));

        verify(broadcaster).broadcast(message);
    }
}
