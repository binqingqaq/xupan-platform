package com.xupan.server.robot;

import com.xupan.server.robot.service.RobotNoticeDispatchService;
import com.xupan.server.robot.service.RobotNoticeIntentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RobotNoticeLifecycleTest {

    private static final String PREFIX = "robot-notice-test-";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RobotNoticeIntentService intents;
    @Autowired private RobotNoticeDispatchService dispatcher;

    private String suffix;
    private String issueNumber;
    private String sevenIssueNumber;
    private long userId;
    private long machineId;
    private Instant now;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        issueNumber = "39" + suffix.replaceAll("[^0-9]", "0");
        if (issueNumber.length() > 16) issueNumber = issueNumber.substring(0, 16);
        sevenIssueNumber = issueNumber + "7";
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.update("UPDATE chat_robot SET status='ENABLED' WHERE robot_code='issue-helper'");
        jdbc.update("""
                INSERT INTO sys_user (username, display_name, password_hash, status, internal_code)
                VALUES (?, '通知验收机器', 'not-a-login-test-hash', 'ACTIVE', ?)
                """, PREFIX + suffix, "RN-" + suffix);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, PREFIX + suffix);
        jdbc.update("""
                INSERT INTO agent
                    (agent_code, display_name, account_user_id, system_owned, status, close_seconds,
                     manual_betting_open)
                VALUES (?, '通知验收机器', ?, FALSE, 'ACTIVE', 240, TRUE)
                """, "RN-" + suffix, userId);
        machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = ?", Long.class, "RN-" + suffix);
        jdbc.update("INSERT INTO agent_game (agent_id, game_code) VALUES (?, 'AU8')", machineId);
        Instant bettingEnds = now.plusSeconds(200);
        Instant drawEnds = now.plusSeconds(300);
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, opened_at, issue_started_at,
                     betting_ends_at, draw_ends_at)
                VALUES ('AU8', ?, 'OPEN', 'BETTING', ?, ?, ?, ?)
                """, issueNumber, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(bettingEnds), Timestamp.from(drawEnds));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM robot_notice_intent WHERE source_id LIKE ? OR idempotency_key LIKE ?",
                issueNumber + ":%", "%" + suffix);
        jdbc.update("DELETE FROM chat_outbox WHERE message_id IN (SELECT id FROM chat_message WHERE issue_number = ?)",
                issueNumber);
        jdbc.update("DELETE FROM chat_message WHERE issue_number = ?", issueNumber);
        jdbc.update("DELETE FROM game_issue WHERE issue_number IN (?, ?)", issueNumber, sevenIssueNumber);
        if (machineId > 0) {
            jdbc.update("DELETE FROM agent_game WHERE agent_id = ?", machineId);
            jdbc.update("DELETE FROM agent WHERE id = ?", machineId);
        }
        if (userId > 0) jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    @Test
    void closeWarningIsMachineScopedAndOnlyPublishedOnceThenChecklistFollows() {
        assertThat(dispatcher.scanMachineCloseNotices(now, 50)).isEqualTo(1);
        assertThat(dispatcher.scanMachineCloseNotices(now, 50)).isZero();
        assertThat(dispatcher.dispatchDue(now, 50)).isEqualTo(1);

        String warning = jdbc.queryForObject("""
                SELECT content FROM chat_message
                 WHERE issue_number = ? AND agent_id = ? AND visibility = 'MACHINE'
                   AND content LIKE '%本机封盘还剩%'
                """, String.class, issueNumber, machineId);
        assertThat(warning).contains("距离" + issueNumber + "期本机封盘还剩60秒");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_message
                 WHERE issue_number = ? AND agent_id = ? AND content LIKE '%本机封盘还剩%'
                """, Integer.class, issueNumber, machineId)).isEqualTo(1);

        assertThat(dispatcher.scanMachineCloseNotices(now.plusSeconds(61), 50)).isEqualTo(1);
        assertThat(dispatcher.dispatchDue(now.plusSeconds(61), 50)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_message
                 WHERE issue_number = ? AND agent_id = ? AND visibility = 'MACHINE'
                """, Integer.class, issueNumber, machineId)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT content FROM chat_message
                 WHERE issue_number = ? AND agent_id = ? AND visibility = 'MACHINE'
                """, String.class, issueNumber, machineId))
                .anyMatch(value -> value.contains("期本机已停止下注"))
                .anyMatch(value -> value.contains("核对列表:(0.00)")
                        && value.contains("开奖前已公告退单"));
    }

    @Test
    void manuallyClosedMachineDoesNotReceiveCountdownWarning() {
        jdbc.update("UPDATE agent SET manual_betting_open=FALSE WHERE id = ?", machineId);
        assertThat(dispatcher.scanMachineCloseNotices(now, 50)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM robot_notice_intent
                 WHERE source_type='GAME_ISSUE_MACHINE' AND source_id=?
                """, Integer.class, issueNumber + ":" + machineId)).isZero();
    }

    @Test
    void businessNoticeIntentPersistsAndDispatchesIdempotently() {
        String key = "ROBOT-NOTICE-TEST-" + suffix;
        intents.enqueue("TEST_NOTICE", "TEST", suffix, "AU8", issueNumber,
                com.xupan.server.chat.domain.ChatMessageVisibility.MACHINE, machineId,
                "TEST_NOTICE", "@玩家甲，积分操作完成。", key, now);
        assertThat(dispatcher.dispatchDue(now, 50)).isEqualTo(1);
        assertThat(dispatcher.dispatchDue(now.plusSeconds(1), 50)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM robot_notice_intent WHERE idempotency_key=?",
                String.class, key)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE idempotency_key=?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void skipsDelayedEightBallBusinessNoticeAfterSevenBallIssueStarts() {
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, ball_count, opened_at, issue_started_at)
                VALUES ('AU8', ?, 'CLOSED', 'SETTLED', 7, ?, ?)
                """, sevenIssueNumber, Timestamp.from(now), Timestamp.from(now));
        String key = "ROBOT-NOTICE-LEGACY-" + suffix;
        intents.enqueue("OLD_ISSUE_NOTICE", "GAME_ISSUE_MACHINE", issueNumber + ":" + machineId,
                "AU8", issueNumber, com.xupan.server.chat.domain.ChatMessageVisibility.MACHINE, machineId,
                "OLD_ISSUE_NOTICE", "旧期机器公告", key, now);

        assertThat(dispatcher.dispatchDue(now, 50)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM robot_notice_intent WHERE idempotency_key=?",
                String.class, key)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM robot_notice_intent WHERE idempotency_key=?",
                String.class, key)).isEqualTo("LEGACY_EIGHT_BALL_NOTICE_SUPPRESSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE idempotency_key=?",
                Integer.class, key)).isZero();
    }
}
