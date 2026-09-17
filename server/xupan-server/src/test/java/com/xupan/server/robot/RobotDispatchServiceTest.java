package com.xupan.server.robot;

import com.xupan.server.chat.realtime.ChatConnectionRegistry;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import com.xupan.server.robot.service.RobotDispatchService;
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
class RobotDispatchServiceTest {

    private static final String ISSUE_PREFIX = "robot-service-test-";
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @Autowired
    private RobotDispatchService dispatchService;
    @Autowired
    private RobotDispatchRepository dispatchRepository;
    @Autowired
    private ChatConnectionRegistry connectionRegistry;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        connectionRegistry.closeAll();
        clean();
        jdbcTemplate.update("UPDATE chat_robot SET status = 'ENABLED' WHERE robot_code = 'issue-helper'");
        resetDrawComponents();
    }

    @AfterEach
    void tearDown() {
        connectionRegistry.closeAll();
        clean();
        jdbcTemplate.update("UPDATE chat_robot SET status = 'ENABLED' WHERE robot_code = 'issue-helper'");
        resetDrawComponents();
    }

    @Test
    void publishesPersistedRobotMessageWithoutOnlineConnection() {
        long eventId = insertEvent("issue-start", "ISSUE_STARTED", "3000000期开始");
        long sequenceBefore = roomSequence();

        assertThat(dispatchService.scanPendingEvents(NOW, 50)).isEqualTo(1);

        RobotDispatchRepository.UnscheduledGameEvent source = dispatchRepository
                .findGameEventById(eventId).orElseThrow();
        assertThat(source.eventType()).isEqualTo("ISSUE_STARTED");
        assertThat(dispatchRepository.findByGameEventId(eventId)).get()
                .extracting(d -> d.status(), d -> d.messageId())
                .satisfies(values -> {
                    assertThat(values.get(0)).isEqualTo(RobotDispatchStatus.PUBLISHED);
                    assertThat(values.get(1)).isNotNull();
                });
        assertThat(connectionRegistry.roomConnectionCount("main")).isZero();
        assertThat(roomSequence()).isEqualTo(sequenceBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE issue_number = ? AND message_type = 'ROBOT'",
                Integer.class, ISSUE_PREFIX + "issue-start")).isEqualTo(1);
    }

    @Test
    void repeatedScanDoesNotCreateAnotherDispatchMessageOrSequence() {
        insertEvent("duplicate", "ISSUE_STARTED", "重复测试期开始");
        long sequenceBefore = roomSequence();

        dispatchService.scanPendingEvents(NOW, 50);
        dispatchService.scanPendingEvents(NOW.plusSeconds(1), 50);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_robot_dispatch WHERE issue_number = ?",
                Integer.class, ISSUE_PREFIX + "duplicate")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE issue_number = ? AND message_type = 'ROBOT'",
                Integer.class, ISSUE_PREFIX + "duplicate")).isEqualTo(1);
        assertThat(roomSequence()).isEqualTo(sequenceBefore + 1);
    }

    @Test
    void disabledRobotCreatesSkippedDispatchWithoutChatMessage() {
        jdbcTemplate.update("UPDATE chat_robot SET status = 'DISABLED' WHERE robot_code = 'issue-helper'");
        insertEvent("disabled", "BETTING_WARNING", "禁用机器人测试");

        dispatchService.scanPendingEvents(NOW, 50);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_robot_dispatch WHERE issue_number = ?",
                String.class, ISSUE_PREFIX + "disabled")).isEqualTo("SKIPPED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE issue_number = ? AND message_type = 'ROBOT'",
                Integer.class, ISSUE_PREFIX + "disabled")).isZero();
    }

    @Test
    void drawResultPublishesThreeStructuredMessagesFromGameIssue() {
        insertSettledIssue("draw-structured");
        long eventId = insertEvent("draw-structured", "DRAW_RESULT", "开奖文案不含号码");

        dispatchService.scanPendingEvents(NOW, 50);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE issue_number = ? AND message_type = 'ROBOT'",
                Integer.class, ISSUE_PREFIX + "draw-structured")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT payload_json
                  FROM chat_message
                 WHERE issue_number = ? AND message_type = 'ROBOT'
                 ORDER BY sequence_no
                """, String.class, ISSUE_PREFIX + "draw-structured"))
                .hasSize(3)
                .allSatisfy(payload -> assertThat(payload).contains("xupan.chat-payload.v1"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT payload_json FROM chat_message
                 WHERE issue_number = ? AND message_type = 'ROBOT'
                """, String.class, ISSUE_PREFIX + "draw-structured"))
                .anyMatch(payload -> payload.contains("DRAW_SUMMARY") && payload.contains("1,2,3"))
                .anyMatch(payload -> payload.contains("DRAW_HISTORY"))
                .anyMatch(payload -> payload.contains("WINNER_LIST")
                        && payload.contains("暂无获胜记录"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT idempotency_key) FROM chat_message WHERE issue_number = ?",
                Integer.class, ISSUE_PREFIX + "draw-structured")).isEqualTo(3);
        assertThat(dispatchRepository.findByGameEventId(eventId)).get()
                .extracting(d -> d.status(), d -> d.messageId())
                .satisfies(values -> {
                    assertThat(values.get(0)).isEqualTo(RobotDispatchStatus.PUBLISHED);
                    assertThat(values.get(1)).isNotNull();
                });
    }

    @Test
    void drawResultCanDisableWinnerListWithoutChangingOtherComponents() {
        insertSettledIssue("draw-disabled-winner");
        jdbcTemplate.update("""
                UPDATE chat_robot_draw_component
                   SET enabled = FALSE
                 WHERE component = 'WINNER_LIST'
                   AND robot_id = (SELECT id FROM chat_robot WHERE robot_code = 'issue-helper')
                """);
        insertEvent("draw-disabled-winner", "DRAW_RESULT", "开奖结果已发布");

        dispatchService.scanPendingEvents(NOW, 50);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE issue_number = ? AND message_type = 'ROBOT'",
                Integer.class, ISSUE_PREFIX + "draw-disabled-winner")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT payload_json FROM chat_message
                 WHERE issue_number = ? AND message_type = 'ROBOT'
                """, String.class, ISSUE_PREFIX + "draw-disabled-winner"))
                .noneMatch(payload -> payload.contains("WINNER_LIST"));
    }

    private long insertEvent(String suffix, String eventType, String message) {
        String issue = ISSUE_PREFIX + suffix;
        jdbcTemplate.update("""
                INSERT INTO game_issue_event (issue_number, event_type, message, created_at)
                VALUES (?, ?, ?, ?)
                """, issue, eventType, message, java.sql.Timestamp.from(NOW));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM game_issue_event WHERE issue_number = ? AND event_type = ?",
                Long.class, issue, eventType);
    }

    private long roomSequence() {
        return jdbcTemplate.queryForObject(
                "SELECT next_sequence_no FROM chat_room WHERE room_code = 'main'", Long.class);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch WHERE issue_number LIKE ?",
                ISSUE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM chat_outbox WHERE message_id IN (SELECT id FROM chat_message WHERE issue_number LIKE ?)",
                ISSUE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM chat_message WHERE issue_number LIKE ?", ISSUE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM game_issue_event WHERE issue_number LIKE ?", ISSUE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM game_bet WHERE issue_number LIKE ?", ISSUE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM game_issue WHERE issue_number LIKE ?", ISSUE_PREFIX + "%");
    }

    private void insertSettledIssue(String suffix) {
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (issue_number, status, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, phase, settled_at)
                VALUES (?, 'CLOSED', 1, 2, 3, 4, 5, 6, 7, 8, 'SETTLED', ?)
                """, ISSUE_PREFIX + suffix, java.sql.Timestamp.from(NOW));
    }

    private void resetDrawComponents() {
        jdbcTemplate.update("""
                UPDATE chat_robot_draw_component
                   SET enabled = TRUE
                 WHERE robot_id = (SELECT id FROM chat_robot WHERE robot_code = 'issue-helper')
                """);
    }
}
