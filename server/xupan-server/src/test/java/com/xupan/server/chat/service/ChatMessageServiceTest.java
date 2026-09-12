package com.xupan.server.chat.service;

import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessagePage;
import com.xupan.server.chat.domain.ChatMessageStatus;
import com.xupan.server.chat.domain.ChatMessageType;
import com.xupan.server.chat.domain.ChatRoom;
import com.xupan.server.chat.domain.ChatSenderType;
import com.xupan.server.chat.repository.ChatMessageRepository;
import com.xupan.server.chat.repository.ChatMuteRepository;
import com.xupan.server.chat.repository.ChatOutboxRepository;
import com.xupan.server.chat.repository.ChatReadCursorRepository;
import com.xupan.server.chat.repository.ChatRoomRepository;
import com.xupan.server.chat.realtime.ChatMessageCreatedEvent;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final long USER_ID = 7L;
    private static final ChatRoom MAIN = new ChatRoom(1L, "main", "公开大厅", "OPEN", 30, 0);

    @Mock
    private UserRepository userRepository;
    @Mock
    private ChatRoomRepository roomRepository;
    @Mock
    private ChatMessageRepository messageRepository;
    @Mock
    private ChatOutboxRepository outboxRepository;
    @Mock
    private ChatMuteRepository muteRepository;
    @Mock
    private ChatReadCursorRepository readCursorRepository;
    @Mock
    private GameDataRepository gameDataRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChatMessageService service;

    @BeforeEach
    void setUp() {
        service = new ChatMessageService(userRepository, roomRepository, messageRepository,
                outboxRepository, muteRepository, readCursorRepository, new ChatContentPolicy(),
                gameDataRepository, new ObjectMapper(), eventPublisher);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
    }

    @Test
    void sendsServerOwnedMessageAndCreatesOutboxPayload() {
        when(roomRepository.findByCodeForUpdate("main")).thenReturn(Optional.of(MAIN));
        when(muteRepository.isMuted(1L, USER_ID, NOW)).thenReturn(false);
        when(messageRepository.findByClientMessageId(1L, USER_ID, "client-1"))
                .thenReturn(Optional.empty());
        when(roomRepository.allocateNextSequence(1L, 0L)).thenReturn(1L);
        ChatMessage message = message(1L, 1L, "hello");
        when(messageRepository.insertUserMessage(1L, 1L, USER_ID, "用户甲", "client-1", "hello", NOW))
                .thenReturn(message);

        assertThat(service.sendUserMessage(USER_ID, "main", "client-1", "  hello  ", NOW))
                .isEqualTo(message);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).insertMessageCreatedOutbox(eq(1L), payload.capture(), eq(NOW));
        verify(eventPublisher).publishEvent(any(ChatMessageCreatedEvent.class));
        assertThat(payload.getValue()).contains("\"roomCode\":\"main\"", "\"sequenceNo\":1",
                "\"senderId\":7", "\"content\":\"hello\"");
    }

    @Test
    void replaysSameIdempotentMessageButRejectsDifferent正文() {
        ChatMessage existing = message(9L, 4L, "原正文");
        when(roomRepository.findByCodeForUpdate("main")).thenReturn(Optional.of(
                new ChatRoom(1L, "main", "公开大厅", "OPEN", 30, 4)));
        when(muteRepository.isMuted(1L, USER_ID, NOW)).thenReturn(false);
        when(messageRepository.findByClientMessageId(1L, USER_ID, "client-1"))
                .thenReturn(Optional.of(existing));

        assertThat(service.sendUserMessage(USER_ID, "main", "client-1", "原正文", NOW))
                .isEqualTo(existing);
        verify(roomRepository, never()).allocateNextSequence(anyLong(), anyLong());
        verify(outboxRepository, never()).insertMessageCreatedOutbox(anyLong(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());

        assertThatThrownBy(() -> service.sendUserMessage(USER_ID, "main", "client-1", "新正文", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void rejectsMutedUserBeforeAnyWrite() {
        when(roomRepository.findByCodeForUpdate("main")).thenReturn(Optional.of(MAIN));
        when(muteRepository.isMuted(1L, USER_ID, NOW)).thenReturn(true);

        assertThatThrownBy(() -> service.sendUserMessage(USER_ID, "main", "client-1", "hello", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_USER_MUTED");
        verify(messageRepository, never()).insertUserMessage(anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void historyUsesAfterCursorAndRejectsConflictingCursors() {
        ChatMessage first = message(1L, 2L, "a");
        ChatMessage second = message(2L, 3L, "b");
        when(roomRepository.findByCode("main")).thenReturn(Optional.of(
                new ChatRoom(1L, "main", "公开大厅", "OPEN", 30, 3)));
        when(messageRepository.findAfterSequence(1L, 1L, 50)).thenReturn(List.of(first, second));

        ChatMessagePage page = service.history(USER_ID, "main", null, 1L, 50, NOW);

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.nextAfterSequence()).isEqualTo(3L);
        assertThatThrownBy(() -> service.history(USER_ID, "main", 1L, 2L, 50, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_CURSOR_INVALID");
    }

    @Test
    void readCursorCannotExceedRoomSequence() {
        when(roomRepository.findByCodeForUpdate("main")).thenReturn(Optional.of(MAIN));

        assertThatThrownBy(() -> service.saveReadCursor(USER_ID, "main", 1L, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_CURSOR_INVALID");
        verify(readCursorRepository, never()).saveReadSequence(anyLong(), anyLong(), anyLong(), any());
    }

    private UserAccount activeUser() {
        return new UserAccount(USER_ID, "user-a", "用户甲", null, "hash", "ACTIVE",
                0, null, 0, null, null);
    }

    private ChatMessage message(long id, long sequence, String content) {
        return new ChatMessage(id, 1L, "main", sequence, "client-1", null, null,
                ChatMessageType.USER_CHAT, ChatSenderType.USER, USER_ID, "用户甲", content, null,
                ChatMessageStatus.ACTIVE, NOW, NOW);
    }
}
