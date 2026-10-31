package com.xupan.server.chat.realtime;

import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessageStatus;
import com.xupan.server.chat.domain.ChatMessageType;
import com.xupan.server.chat.domain.ChatSenderType;
import com.xupan.server.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageCreatedListenerTest {

    @Test
    void broadcastsThePersistedMessageProjectionOnce() {
        ChatRealtimeBroadcaster broadcaster = mock(ChatRealtimeBroadcaster.class);
        ChatMessageRepository repository = mock(ChatMessageRepository.class);
        when(repository.isLegacyEightBallMessage(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        ChatMessageCreatedListener listener = new ChatMessageCreatedListener(broadcaster, repository);
        ChatMessage message = new ChatMessage(1L, 1L, "main", 1L, "client-1", null, null,
                ChatMessageType.USER_CHAT, ChatSenderType.USER, 7L, "用户甲", "hello", null,
                ChatMessageStatus.ACTIVE, Instant.parse("2026-09-11T00:00:00Z"),
                Instant.parse("2026-09-11T00:00:00Z"));

        listener.onMessageCreated(new ChatMessageCreatedEvent(message));

        verify(broadcaster).broadcast(message);
    }

    @Test
    void doesNotBroadcastRetainedEightBallMessageAfterSevenBallCutover() {
        ChatRealtimeBroadcaster broadcaster = mock(ChatRealtimeBroadcaster.class);
        ChatMessageRepository repository = mock(ChatMessageRepository.class);
        when(repository.isLegacyEightBallMessage(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        ChatMessageCreatedListener listener = new ChatMessageCreatedListener(broadcaster, repository);
        ChatMessage legacyResult = new ChatMessage(2L, 1L, "main", 2L, null, null, "3000000",
                ChatMessageType.ROBOT, ChatSenderType.ROBOT, 99L, "机器人", "旧八球结果", null,
                ChatMessageStatus.ACTIVE, Instant.parse("2026-10-31T00:00:00Z"),
                Instant.parse("2026-10-31T00:00:00Z"));

        listener.onMessageCreated(new ChatMessageCreatedEvent(legacyResult));

        verify(broadcaster, never()).broadcast(legacyResult);
    }
}
