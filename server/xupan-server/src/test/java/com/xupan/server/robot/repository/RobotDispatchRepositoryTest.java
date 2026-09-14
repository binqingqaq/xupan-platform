package com.xupan.server.robot.repository;

import com.xupan.server.robot.domain.ChatRobotDispatch;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.domain.RobotEventType;
import com.xupan.server.robot.domain.RobotStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RobotDispatchRepositoryTest {

    private static final String ISSUE_PREFIX = "robot-dispatch-test-";
    private static final String ROBOT_CODE = "robot-dispatch-test";
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @Autowired
    private RobotRepository robotRepository;
    @Autowired
    private RobotDispatchRepository dispatchRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch WHERE issue_number LIKE ?",
                ISSUE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM game_issue_event WHERE issue_number LIKE ?",
                ISSUE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM chat_robot_template WHERE robot_id IN "
                + "(SELECT id FROM chat_robot WHERE robot_code = ?)", ROBOT_CODE);
        jdbcTemplate.update("DELETE FROM chat_robot WHERE robot_code = ?", ROBOT_CODE);
    }

    @Test
    @Transactional
    void duplicateGameEventCreatesOnlyOnePendingDispatch() {
        long robotId = robotId();
        long eventId = insertEvent(ISSUE_PREFIX + "duplicate", "ISSUE_STARTED");

        ChatRobotDispatch first = dispatchRepository.insertPendingIfAbsent(eventId, robotId,
                ISSUE_PREFIX + "duplicate", RobotEventType.ISSUE_STARTED, NOW);
        ChatRobotDispatch replay = dispatchRepository.insertPendingIfAbsent(eventId, robotId,
                ISSUE_PREFIX + "duplicate", RobotEventType.ISSUE_STARTED, NOW.plusSeconds(1));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_robot_dispatch WHERE game_event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
        assertThat(dispatchRepository.findByGameEventId(eventId)).get()
                .extracting(ChatRobotDispatch::status, ChatRobotDispatch::attemptCount)
                .containsExactly(RobotDispatchStatus.PENDING, 0);
    }

    @Test
    @Transactional
    void dueStateCanBeLockedFailedWithTruncatedErrorAndQueriedAgain() {
        long robotId = robotId();
        long eventId = insertEvent(ISSUE_PREFIX + "retry", "BETTING_WARNING");
        ChatRobotDispatch dispatch = dispatchRepository.insertPendingIfAbsent(eventId, robotId,
                ISSUE_PREFIX + "retry", RobotEventType.BETTING_WARNING, NOW.minusSeconds(5));

        assertThat(dispatchRepository.findUnscheduledEvents()).noneMatch(event -> event.id() == eventId);
        assertThat(dispatchRepository.findDue(NOW)).extracting(ChatRobotDispatch::id)
                .contains(dispatch.id());
        assertThat(dispatchRepository.findByIdForUpdate(dispatch.id())).isPresent();
        assertThat(dispatchRepository.markProcessing(dispatch.id(), NOW.plusSeconds(30), NOW)).isEqualTo(1);

        String longError = "x".repeat(1200);
        assertThat(dispatchRepository.markFailed(dispatch.id(), 1, NOW.minusSeconds(1), longError,
                NOW)).isEqualTo(1);
        assertThat(dispatchRepository.findById(dispatch.id())).get()
                .extracting(ChatRobotDispatch::status, ChatRobotDispatch::attemptCount)
                .containsExactly(RobotDispatchStatus.FAILED, 1);
        assertThat(dispatchRepository.findById(dispatch.id()).orElseThrow().lastError())
                .hasSize(1000);
        assertThat(dispatchRepository.findDue(NOW)).extracting(ChatRobotDispatch::id)
                .contains(dispatch.id());
    }

    @Test
    void lockingAndStateChangesRequireAnOuterTransaction() {
        assertThatThrownBy(() -> dispatchRepository.findByIdForUpdate(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("外层事务");
    }

    private long robotId() {
        return robotRepository.insert(ROBOT_CODE, "调度测试机器人", "robot-test",
                RobotStatus.ENABLED, 100, 0);
    }

    private long insertEvent(String issueNumber, String eventType) {
        jdbcTemplate.update("""
                INSERT INTO game_issue_event (issue_number, event_type, message, created_at)
                VALUES (?, ?, ?, ?)
                """, issueNumber, eventType, "测试事件", java.sql.Timestamp.from(NOW));
        return jdbcTemplate.queryForObject("""
                SELECT id FROM game_issue_event WHERE issue_number = ? AND event_type = ?
                """, Long.class, issueNumber, eventType);
    }
}
