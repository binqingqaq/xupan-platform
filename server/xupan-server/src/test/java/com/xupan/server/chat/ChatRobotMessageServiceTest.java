package com.xupan.server.chat;

import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.realtime.ChatConnectionRegistry;
import com.xupan.server.chat.service.ChatMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ChatRobotMessageServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final long ROBOT_ID = 900002L;
    private static final String OFFLINE_KEY = "robot-message-test-offline";
    private static final String IDEMPOTENT_KEY = "robot-message-test-idempotent";
    private static final String PAYLOAD = "{\"eventType\":\"ISSUE_STARTED\",\"eventId\":2}";

    @Autowired
    private ChatMessageService messageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ChatConnectionRegistry connectionRegistry;

    @BeforeEach
    void setUp() {
        connectionRegistry.closeAll();
        cleanRobotMessages();
    }

    @AfterEach
    void tearDown() {
        connectionRegistry.closeAll();
        cleanRobotMessages();
    }

    @Test
    void persistsRobotMessageAndOutboxWhenNoWebSocketConnectionIsOnline() {
        assertThat(connectionRegistry.size()).isZero();
        long sequenceBefore = currentRoomSequence();
        int gameBetsBefore = countRows("game_bet");
        int walletsBefore = countRows("demo_user_account");
        int ledgerBefore = countRows("demo_balance_ledger");

        ChatMessage message = messageService.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000000",
                OFFLINE_KEY, "3000000期开始", PAYLOAD, NOW);

        assertThat(connectionRegistry.size()).isZero();
        assertThat(connectionRegistry.roomConnectionCount("main")).isZero();
        assertThat(message.messageType()).isEqualTo(com.xupan.server.chat.domain.ChatMessageType.ROBOT);
        assertThat(message.senderType()).isEqualTo(com.xupan.server.chat.domain.ChatSenderType.ROBOT);
        assertThat(message.senderId()).isEqualTo(ROBOT_ID);
        assertThat(message.clientMessageId()).isNull();
        assertThat(message.idempotencyKey()).isEqualTo(OFFLINE_KEY);
        assertThat(currentRoomSequence()).isEqualTo(sequenceBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE id = ? AND sender_type = 'ROBOT'", Integer.class,
                message.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_outbox WHERE message_id = ? AND event_type = 'MESSAGE_CREATED'",
                Integer.class, message.id())).isEqualTo(1);
        assertThat(countRows("game_bet")).isEqualTo(gameBetsBefore);
        assertThat(countRows("demo_user_account")).isEqualTo(walletsBefore);
        assertThat(countRows("demo_balance_ledger")).isEqualTo(ledgerBefore);
    }

    @Test
    void repeatedRobotPublishReturnsSameMessageAndAdvancesSequenceOnce() {
        long sequenceBefore = currentRoomSequence();

        ChatMessage first = messageService.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000001",
                IDEMPOTENT_KEY, "3000001期开始", PAYLOAD, NOW);
        ChatMessage second = messageService.publishRobotMessage(ROBOT_ID, "开奖助手", "main", "3000001",
                IDEMPOTENT_KEY, "3000001期开始", PAYLOAD, NOW.plusSeconds(1));

        assertThat(second).isEqualTo(first);
        assertThat(currentRoomSequence()).isEqualTo(sequenceBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE idempotency_key = ?", Integer.class,
                IDEMPOTENT_KEY)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM chat_outbox o
                  JOIN chat_message m ON m.id = o.message_id
                 WHERE m.idempotency_key = ? AND o.event_type = 'MESSAGE_CREATED'
                """, Integer.class, IDEMPOTENT_KEY)).isEqualTo(1);
    }

    private long currentRoomSequence() {
        return jdbcTemplate.queryForObject(
                "SELECT next_sequence_no FROM chat_room WHERE room_code = 'main'", Long.class);
    }

    private int countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private void cleanRobotMessages() {
        jdbcTemplate.update("""
                DELETE FROM chat_outbox
                 WHERE message_id IN (SELECT id FROM chat_message
                                       WHERE idempotency_key LIKE 'robot-message-test-%'
                                          OR idempotency_key LIKE 'robot-unit-%')
                """);
        jdbcTemplate.update("""
                DELETE FROM chat_message
                 WHERE idempotency_key LIKE 'robot-message-test-%'
                    OR idempotency_key LIKE 'robot-unit-%'
                """);
    }
}
