package com.xupan.server.chat.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.domain.ChatMessage;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRobotMessageServiceUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final long ROBOT_ID = 900001L;
    private static final ChatRoom MAIN = new ChatRoom(1L, "main", "公开大厅", "OPEN", 30, 8);
    private static final String KEY = "robot-unit-issue-started-3000000";
    private static final String CONTENT = "3000000期开始";
    private static final String PAYLOAD = "{\"eventType\":\"ISSUE_STARTED\",\"eventId\":1}";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PermissionService permissionService;
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
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private ChatMessageService service;

    @BeforeEach
    void setUp() {
        service = new ChatMessageService(userRepository, permissionService, roomRepository, messageRepository,
                outboxRepository, muteRepository, readCursorRepository, new ChatContentPolicy(),
                gameDataRepository, new ObjectMapper(), eventPublisher);
    }

    @Test
    void publishesRobotMessageThroughExistingChatPipelineWithoutUserDependencies() {
        ChatMessage message = robotMessage(21L, 9L);
        when(messageRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(roomRepository.findByCodeForUpdate("main")).thenReturn(Optional.of(MAIN));
        when(roomRepository.allocateNextSequence(1L, 8L)).thenReturn(9L);
        when(messageRepository.insertRobotMessage(1L, 9L, ROBOT_ID, "开奖助手", "3000000", KEY,
                CONTENT, PAYLOAD, NOW)).thenReturn(message);

        ChatMessage result = service.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000000",
                KEY, CONTENT, PAYLOAD, NOW);

        assertThat(result).isEqualTo(message);
        verify(messageRepository).insertRobotMessage(1L, 9L, ROBOT_ID, "开奖助手", "3000000", KEY,
                CONTENT, PAYLOAD, NOW);
        ArgumentCaptor<String> outboxPayload = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).insertMessageCreatedOutbox(eq(21L), outboxPayload.capture(), eq(NOW));
        assertThat(outboxPayload.getValue()).contains("\"messageType\":\"ROBOT\"",
                "\"senderType\":\"ROBOT\"", "\"senderId\":900001");
        verify(eventPublisher).publishEvent(any(ChatMessageCreatedEvent.class));
        verifyNoInteractions(userRepository, permissionService, muteRepository, readCursorRepository,
                gameDataRepository);
    }

    @Test
    void returnsExistingRobotMessageWithoutConsumingSequenceOrPublishingAgain() {
        ChatMessage existing = robotMessage(21L, 9L);
        when(messageRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThat(service.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000000",
                KEY, CONTENT, PAYLOAD, NOW)).isSameAs(existing);

        verify(messageRepository, never()).insertRobotMessage(anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(roomRepository, never()).findByCodeForUpdate(anyString());
        verify(roomRepository, never()).allocateNextSequence(anyLong(), anyLong());
        verify(outboxRepository, never()).insertMessageCreatedOutbox(anyLong(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsChangedPayloadForAnExistingIdempotencyKey() {
        when(messageRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(robotMessage(21L, 9L)));

        assertThatThrownBy(() -> service.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000000",
                KEY, "不同正文", PAYLOAD, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_ROBOT_IDEMPOTENCY_CONFLICT");
        verify(roomRepository, never()).findByCodeForUpdate(anyString());
        verify(outboxRepository, never()).insertMessageCreatedOutbox(anyLong(), anyString(), any());
    }

    @Test
    void validatesRobotMessageWithoutInvokingUserAuthorization() {
        assertThatThrownBy(() -> service.publishRobotMessage(0L, "开奖助手", "main", "3000000",
                KEY, CONTENT, PAYLOAD, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_ROBOT_INVALID");
        assertThatThrownBy(() -> service.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000000",
                KEY, "<b>不允许</b>", PAYLOAD, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_MESSAGE_HTML_FORBIDDEN");
        assertThatThrownBy(() -> service.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000000",
                KEY, CONTENT, "not-json", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT_ROBOT_PAYLOAD_INVALID");

        verifyNoInteractions(userRepository, permissionService, roomRepository, messageRepository,
                outboxRepository, muteRepository, readCursorRepository, gameDataRepository, eventPublisher);
    }

    private ChatMessage robotMessage(long id, long sequence) {
        return new ChatMessage(id, 1L, "main", sequence, null, KEY, "3000000",
                ChatMessageType.ROBOT, ChatSenderType.ROBOT, ROBOT_ID, "开奖助手", CONTENT, PAYLOAD,
                ChatMessageStatus.ACTIVE, NOW, NOW);
    }
}
