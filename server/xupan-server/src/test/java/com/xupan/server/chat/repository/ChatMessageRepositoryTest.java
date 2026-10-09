package com.xupan.server.chat.repository;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessageVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@ActiveProfiles("test")
class ChatMessageRepositoryTest {

    private static final String USERNAME = "chat-repository-test-user";
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    @Autowired
    private ChatRoomRepository roomRepository;
    @Autowired
    private ChatMessageRepository messageRepository;
    @Autowired
    private ChatOutboxRepository outboxRepository;
    @Autowired
    private ChatReadCursorRepository readCursorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        clean();
        userId = userRepository.insert(USERNAME, "仓储测试用户", "test-password-hash", "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @Transactional
    void allocatesRoomSequenceWritesMessageOutboxAndReadsHistory() {
        var room = roomRepository.findByCodeForUpdate("main").orElseThrow();
        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                "仓储测试用户", "repo-client-1", 101L, "第一条", NOW);
        outboxRepository.insertMessageCreatedOutbox(message.id(), "{}", NOW);

        assertThat(sequence).isEqualTo(room.nextSequenceNo() + 1);
        assertThat(messageRepository.findByClientMessageId(room.id(), userId, "repo-client-1"))
                .get().extracting(ChatMessage::content, ChatMessage::sequenceNo)
                .containsExactly("第一条", sequence);
        assertThat(messageRepository.findBeforeSequence(room.id(), sequence + 1, 50, 101L))
                .extracting(ChatMessage::content).containsExactly("第一条");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_outbox WHERE message_id = ? AND event_type = 'MESSAGE_CREATED'",
                Integer.class, message.id())).isEqualTo(1);
    }

    @Test
    void lockingMethodsRequireOuterTransaction() {
        assertThatThrownBy(() -> roomRepository.findByCodeForUpdate("main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("外层事务");
    }

    @Test
    @Transactional
    void readCursorOnlyMovesForward() {
        var room = roomRepository.findByCodeForUpdate("main").orElseThrow();
        readCursorRepository.saveReadSequence(room.id(), userId, 3L, NOW);
        readCursorRepository.saveReadSequence(room.id(), userId, 1L, NOW.plusSeconds(1));

        assertThat(readCursorRepository.currentReadSequence(room.id(), userId)).isEqualTo(3L);
    }

    @Test
    @Transactional
    void updatesSenderNameForAllHistoricalRobotMessages() {
        var room = roomRepository.findByCodeForUpdate("main").orElseThrow();
        ChatMessage first = messageRepository.insertRobotMessage(room.id(),
                room.nextSequenceNo() + 1, 99L, "旧机器人", "20260911001", null,
                "第一条", "{}", com.xupan.server.chat.domain.ChatMessageVisibility.PUBLIC, null, NOW);
        ChatMessage second = messageRepository.insertRobotMessage(room.id(),
                room.nextSequenceNo() + 2, 99L, "旧机器人", "20260911001", null,
                "第二条", "{}", com.xupan.server.chat.domain.ChatMessageVisibility.PUBLIC, null,
                NOW.plusSeconds(1));

        assertThat(messageRepository.updateRobotSenderName(99L, "新机器人")).isEqualTo(2);
        assertThat(messageRepository.findBeforeSequence(room.id(), room.nextSequenceNo() + 3, 10, null))
                .extracting(ChatMessage::id, ChatMessage::senderName)
                .containsExactly(tuple(second.id(), "新机器人"), tuple(first.id(), "新机器人"));
    }

    @Test
    @Transactional
    void beforeQueryReturnsNewestRowsInDescendingRepositoryOrderWithExtraRow() {
        var room = roomRepository.findByCodeForUpdate("main").orElseThrow();
        for (int i = 1; i <= 3; i++) {
            long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo() + i - 1);
            messageRepository.insertUserMessage(room.id(), sequence, userId, "仓储测试用户",
                    "repo-client-" + i, 101L, "消息" + i, NOW.plusSeconds(i));
        }

        List<ChatMessage> rows = messageRepository.findBeforeSequence(room.id(), room.nextSequenceNo() + 4L, 2, 101L);
        assertThat(rows).hasSize(3).extracting(ChatMessage::sequenceNo)
                .containsExactly(room.nextSequenceNo() + 3L, room.nextSequenceNo() + 2L,
                        room.nextSequenceNo() + 1L);
    }

    @Test
    @Transactional
    void historyCombinesPublicMessagesWithOnlyTheCurrentMachineMessages() {
        var room = roomRepository.findByCodeForUpdate("main").orElseThrow();
        ChatMessage publicMessage = insertRobot(room, "scope-public", ChatMessageVisibility.PUBLIC, null);
        ChatMessage machineOne = insertRobot(room, "scope-machine-101", ChatMessageVisibility.MACHINE, 101L);
        ChatMessage machineTwo = insertRobot(room, "scope-machine-202", ChatMessageVisibility.MACHINE, 202L);

        assertThat(messageRepository.findBeforeSequence(room.id(), machineTwo.sequenceNo() + 1, 10, 101L))
                .extracting(ChatMessage::id).containsExactly(machineOne.id(), publicMessage.id());
        assertThat(messageRepository.findBeforeSequence(room.id(), machineTwo.sequenceNo() + 1, 10, 202L))
                .extracting(ChatMessage::id).containsExactly(machineTwo.id(), publicMessage.id());
        assertThat(messageRepository.findBeforeSequence(room.id(), machineTwo.sequenceNo() + 1, 10, null))
                .extracting(ChatMessage::id).containsExactly(publicMessage.id());
    }

    private ChatMessage insertRobot(com.xupan.server.chat.domain.ChatRoom room, String key,
                                    ChatMessageVisibility visibility, Long agentId) {
        var currentRoom = roomRepository.findByCodeForUpdate(room.roomCode()).orElseThrow();
        long sequence = roomRepository.allocateNextSequence(currentRoom.id(), currentRoom.nextSequenceNo());
        return messageRepository.insertRobotMessage(room.id(), sequence, 99L, "测试机器人", "3000000",
                key, key, "{}", visibility, agentId, NOW);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM chat_read_cursor WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM chat_user_mute WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM chat_outbox WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", USERNAME);
        jdbcTemplate.update("DELETE FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
    }
}
