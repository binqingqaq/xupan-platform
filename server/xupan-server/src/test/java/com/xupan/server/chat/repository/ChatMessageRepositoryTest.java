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

    @Test
    @Transactional
    void hidesEightBallIssueMessagesFromNormalFeedAfterSevenBallIssueStartsAndKeepsCursorOrder() {
        String oldIssue = "39998001";
        String sevenIssue = "39998002";
        insertIssue(oldIssue, 8, "SETTLED");
        insertIssue(sevenIssue, 7, "BETTING");

        var room = roomRepository.findByCodeForUpdate("main").orElseThrow();
        ChatMessage oldPlayerBet = insertPlayerMessage(room, "legacy-eight-bet", oldIssue, "第8球/1番10");
        ChatMessage oldRobotResult = insertRobotForIssue(room, "legacy-eight-result", oldIssue, "旧八球开奖结果");
        ChatMessage currentGeneric = insertPlayerMessage(room, "current-generic", null, "大家好");
        ChatMessage currentSevenResult = insertRobotForIssue(room, "current-seven-result", sevenIssue, "七球开奖结果");

        assertThat(messageRepository.isLegacyEightBallMessage(oldPlayerBet)).isTrue();
        assertThat(messageRepository.isLegacyEightBallMessage(oldRobotResult)).isTrue();
        assertThat(messageRepository.isLegacyEightBallMessage(currentGeneric)).isFalse();
        assertThat(messageRepository.isLegacyEightBallMessage(currentSevenResult)).isFalse();
        assertThat(messageRepository.findAfterSequence(room.id(), 0, 10, 101L))
                .extracting(ChatMessage::sequenceNo)
                .containsExactly(currentGeneric.sequenceNo(), currentSevenResult.sequenceNo());
        assertThat(messageRepository.findBeforeSequence(room.id(), currentSevenResult.sequenceNo() + 1, 10, 101L))
                .extracting(ChatMessage::sequenceNo)
                .containsExactly(currentSevenResult.sequenceNo(), currentGeneric.sequenceNo());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_message WHERE id IN (?, ?)",
                Integer.class, oldPlayerBet.id(), oldRobotResult.id())).isEqualTo(2);
    }

    private ChatMessage insertPlayerMessage(com.xupan.server.chat.domain.ChatRoom room, String clientId,
                                            String issueNumber, String content) {
        var currentRoom = roomRepository.findByCodeForUpdate(room.roomCode()).orElseThrow();
        long sequence = roomRepository.allocateNextSequence(currentRoom.id(), currentRoom.nextSequenceNo());
        if (issueNumber == null) {
            return messageRepository.insertUserMessage(room.id(), sequence, userId, "仓储测试用户",
                    clientId, 101L, content, NOW);
        }
        return messageRepository.insertUserBetMessage(room.id(), sequence, userId, "仓储测试用户",
                clientId, issueNumber, content, null, 101L, NOW);
    }

    private ChatMessage insertRobotForIssue(com.xupan.server.chat.domain.ChatRoom room, String key,
                                            String issueNumber, String content) {
        var currentRoom = roomRepository.findByCodeForUpdate(room.roomCode()).orElseThrow();
        long sequence = roomRepository.allocateNextSequence(currentRoom.id(), currentRoom.nextSequenceNo());
        return messageRepository.insertRobotMessage(room.id(), sequence, 99L, "机器人", issueNumber,
                key, content, "{}", ChatMessageVisibility.PUBLIC, null, NOW);
    }

    private void insertIssue(String issueNumber, int ballCount, String phase) {
        String status = "BETTING".equals(phase) ? "OPEN" : "CLOSED";
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, ball_count,
                     opened_at, issue_started_at, betting_ends_at, draw_ends_at)
                VALUES ('AU8', ?, ?, ?, ?, ?, ?, ?, ?)
                """, issueNumber, status, phase, ballCount,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(180)), java.sql.Timestamp.from(NOW.plusSeconds(300)));
    }

    private ChatMessage insertRobot(com.xupan.server.chat.domain.ChatRoom room, String key,
                                    ChatMessageVisibility visibility, Long agentId) {
        var currentRoom = roomRepository.findByCodeForUpdate(room.roomCode()).orElseThrow();
        long sequence = roomRepository.allocateNextSequence(currentRoom.id(), currentRoom.nextSequenceNo());
        return messageRepository.insertRobotMessage(room.id(), sequence, 99L, "测试机器人", "3000000",
                key, key, "{}", visibility, agentId, NOW);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM chat_message WHERE issue_number LIKE '399980%'");
        jdbcTemplate.update("DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number LIKE '399980%'");
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
