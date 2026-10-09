package com.xupan.server.system.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.xupan.server.robot.service.RobotDispatchService;


import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "xupan.automation.enabled=false",
        "xupan.test-player.behavior-enabled=false",
        "spring.task.scheduling.enabled=false",
        "xupan.retention.batch-size=2",
        "xupan.retention.max-batches-per-table-per-run=2"
})
class DataRetentionSchedulerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataRetentionScheduler scheduler;

    @Autowired
    private RobotDispatchService robotDispatchService;

    @Test
    @Transactional
    void removesExpiredChatLogsAndTerminalRobotEventsButProtectsPendingAndFailedRows() {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Instant expired = now.minusSeconds(6 * 24 * 60 * 60L);
        Instant recent = now.minusSeconds(2 * 24 * 60 * 60L);
        Instant expiredLogin = now.minusSeconds(200L * 24 * 60 * 60);
        Instant expiredAudit = now.minusSeconds(400L * 24 * 60 * 60);
        Instant oldRobotRecord = Instant.parse("2000-01-02T00:00:00Z");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO chat_room (room_code, display_name, status, message_retention_days) VALUES (?, ?, 'OPEN', 5)",
                "retention-" + suffix, "保留测试房间");
        long roomId = jdbc.queryForObject("SELECT id FROM chat_room WHERE room_code = ?", Long.class,
                "retention-" + suffix);
        long plainMessage = insertMessage(roomId, 1, expired, "过期消息");
        long publishedMessage = insertMessage(roomId, 2, expired, "已发布消息");
        long pendingMessage = insertMessage(roomId, 3, expired, "待投递消息");
        long failedMessage = insertMessage(roomId, 4, expired, "失败投递消息");
        insertOutbox(publishedMessage, "PUBLISHED", expired);
        insertOutbox(pendingMessage, "PENDING", expired);
        insertOutbox(failedMessage, "FAILED", expired);
        jdbc.update("INSERT INTO sys_login_log (username_snapshot, result, created_at) VALUES (?, 'FAILURE', ?)",
                "retention-old-" + suffix, Timestamp.from(expiredLogin));
        jdbc.update("INSERT INTO sys_login_log (username_snapshot, result, created_at) VALUES (?, 'SUCCESS', ?)",
                "retention-new-" + suffix, Timestamp.from(recent));
        jdbc.update("INSERT INTO sys_operation_log (http_method, request_path, result, created_at) VALUES ('POST', ?, 'SUCCESS', ?)",
                "/retention-test/" + suffix, Timestamp.from(expiredAudit));

        long robotId = jdbc.queryForObject("SELECT id FROM chat_robot WHERE robot_code = 'issue-helper'", Long.class);
        long publishedEvent = insertRobotEvent("ret-pub-" + suffix, oldRobotRecord);
        long publishedRobotMessage = insertMessage(roomId, 5, oldRobotRecord, "已完成机器人消息");
        insertDispatch(publishedEvent, robotId, "ret-pub-" + suffix, "PUBLISHED", publishedRobotMessage, oldRobotRecord);
        long failedEvent = insertRobotEvent("ret-failed-" + suffix, oldRobotRecord);
        long failedRobotMessage = insertMessage(roomId, 6, oldRobotRecord, "失败机器人消息");
        insertDispatch(failedEvent, robotId, "ret-failed-" + suffix, "FAILED", failedRobotMessage, oldRobotRecord);
        long pendingEvent = insertRobotEvent("ret-pending-" + suffix, oldRobotRecord);
        insertDispatch(pendingEvent, robotId, "ret-pending-" + suffix, "PENDING", null, oldRobotRecord);

        DataRetentionScheduler.CleanupResult result = scheduler.cleanAt(now);

        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE id = ?", plainMessage)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE id = ?", publishedMessage)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_outbox WHERE message_id = ?", publishedMessage)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE id = ?", pendingMessage)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE id = ?", failedMessage)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_outbox WHERE message_id = ? AND status = 'PENDING'", pendingMessage)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_outbox WHERE message_id = ? AND status = 'FAILED'", failedMessage)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE id = ?", publishedRobotMessage)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_robot_dispatch WHERE game_event_id = ?", publishedEvent)).isZero();
        assertThat(count("SELECT COUNT(*) FROM game_issue_event WHERE id = ?", publishedEvent)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_robot_dispatch WHERE game_event_id = ? AND status = 'FAILED'", failedEvent)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE id = ?", failedRobotMessage)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_robot_dispatch WHERE game_event_id = ? AND status = 'PENDING'", pendingEvent)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sys_login_log WHERE username_snapshot = ?", "retention-old-" + suffix)).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_login_log WHERE username_snapshot = ?", "retention-new-" + suffix)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sys_operation_log WHERE request_path = ?", "/retention-test/" + suffix)).isZero();
        assertThat(result.deletedByTable()).containsKeys("chat_message", "sys_login_log", "sys_operation_log");
    }

    @Test
    @Transactional
    void doesNotRediscoverUnscheduledRobotEventsOlderThanChatRetention() {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Instant expired = now.minusSeconds(6 * 24 * 60 * 60L);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long eventId = insertRobotEvent("ret-old-unscheduled-" + suffix, expired);

        int discovered = robotDispatchService.scanPendingEvents(now, 50);

        assertThat(discovered).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_robot_dispatch WHERE game_event_id = ?", eventId)).isZero();
    }

    @Test
    @Transactional
    void limitsEachCleanupTargetToConfiguredBatchesPerRun() {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Instant expired = now.minusSeconds(200L * 24 * 60 * 60);
        String prefix = "retention-batch-" + UUID.randomUUID().toString().replace("-", "");
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO sys_login_log (username_snapshot, result, created_at) VALUES (?, 'FAILURE', ?)",
                    prefix + "-" + i, Timestamp.from(expired));
        }

        DataRetentionScheduler.CleanupResult result = scheduler.cleanAt(now);

        assertThat(result.deletedByTable().get("sys_login_log")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM sys_login_log WHERE username_snapshot LIKE ?", prefix + "%"))
                .isEqualTo(1);
    }

    @Test
    @Transactional
    void purgesOnlyCompletedBotActionsAndKeepsPendingProcessingOrFailedActions() {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Instant expired = now.minusSeconds(6 * 24 * 60 * 60L);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long userId = insertUser("retention-user-" + suffix);
        long accountId = insertAccount("retention-account-" + suffix);
        jdbc.update("INSERT INTO test_player_behavior (account_id) VALUES (?)", accountId);
        long behaviorId = jdbc.queryForObject(
                "SELECT id FROM test_player_behavior WHERE account_id = ?", Long.class, accountId);
        long succeeded = insertAction(behaviorId, userId, accountId, "SUCCEEDED", 1, expired, suffix);
        long skipped = insertAction(behaviorId, userId, accountId, "SKIPPED", 2, expired, suffix);
        long pending = insertAction(behaviorId, userId, accountId, "PENDING", 3, expired, suffix);
        long processing = insertAction(behaviorId, userId, accountId, "PROCESSING", 4, expired, suffix);
        long failed = insertAction(behaviorId, userId, accountId, "FAILED", 5, expired, suffix);

        scheduler.cleanAt(now);

        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE id = ?", succeeded)).isZero();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE id = ?", skipped)).isZero();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE id IN (?, ?, ?)", pending, processing, failed)).isEqualTo(3);
    }

    @Test
    @Transactional
    void deletesOnlyOldSettledBetsAndTheirLedgerWithoutUpstreamOrReconciliationReferences() {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Instant expired = now.minusSeconds(4 * 365 * 24 * 60 * 60L);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long accountId = insertAccount("retention-bet-account-" + suffix);
        long settledBet = insertBet("retention-settled-" + suffix, accountId, "WIN", expired);
        long pendingBet = insertBet("retention-pending-" + suffix, accountId, "PENDING", null);
        long reconciledBet = insertBet("retention-reconciled-" + suffix, accountId, "LOSE", expired);
        long expiredLedger = insertWalletLedger(accountId, settledBet, "retention-ledger-" + suffix, expired);
        long standaloneLedger = insertWalletLedger(accountId, null, "retention-standalone-" + suffix, expired);
        jdbc.update("""
                INSERT INTO center_reconciliation_disposition
                    (difference_type, bet_id, resolution, idempotency_key, created_at)
                VALUES ('MISSING_IN_CENTER', ?, 'ACKNOWLEDGED', ?, ?)
                """, reconciledBet, "retention-recon-" + suffix, Timestamp.from(expired));

        DataRetentionScheduler.CleanupResult result = scheduler.cleanAt(now);

        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE id = ?", settledBet)).isZero();
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE id IN (?, ?)", pendingBet, reconciledBet)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM demo_balance_ledger WHERE id = ?", expiredLedger)).isZero();
        assertThat(count("SELECT COUNT(*) FROM demo_balance_ledger WHERE id = ?", standaloneLedger)).isZero();
        assertThat(result.deletedByTable().get("game_bet")).isGreaterThanOrEqualTo(1);
    }

    private long insertMessage(long roomId, long sequence, Instant createdAt, String content) {
        jdbc.update("""
                INSERT INTO chat_message (room_id, sequence_no, message_type, sender_type, sender_name, content, created_at)
                VALUES (?, ?, 'USER_CHAT', 'USER', '保留测试', ?, ?)
                """, roomId, sequence, content, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM chat_message WHERE room_id = ? AND sequence_no = ?",
                Long.class, roomId, sequence);
    }

    private void insertOutbox(long messageId, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO chat_outbox (message_id, event_type, payload_json, status, created_at)
                VALUES (?, 'CHAT_MESSAGE', '{}', ?, ?)
                """, messageId, status, Timestamp.from(createdAt));
    }

    private long insertRobotEvent(String issue, Instant createdAt) {
        jdbc.update("""
                INSERT INTO game_issue_event (issue_number, event_type, message, created_at)
                VALUES (?, 'ISSUE_STARTED', '保留测试事件', ?)
                """, issue, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM game_issue_event WHERE issue_number = ? AND event_type = 'ISSUE_STARTED'",
                Long.class, issue);
    }

    private void insertDispatch(long eventId, long robotId, String issue, String status, Long messageId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO chat_robot_dispatch
                    (game_event_id, robot_id, issue_number, event_type, status, message_id, created_at, updated_at)
                VALUES (?, ?, ?, 'ISSUE_STARTED', ?, ?, ?, ?)
                """, eventId, robotId, issue, status, messageId, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private long insertUser(String username) {
        String internalCode = "ret-" + username.substring(Math.max(0, username.length() - 36));
        jdbc.update("""
                INSERT INTO sys_user (username, display_name, internal_code, password_hash, status)
                VALUES (?, '保留测试用户', ?, 'not-a-secret-test-hash', 'ACTIVE')
                """, username, internalCode);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private long insertAccount(String code) {
        String memberCode = "v" + code.substring(Math.max(0, code.length() - 30));
        jdbc.update("INSERT INTO demo_user_account (user_code, member_code, display_name, balance) VALUES (?, ?, ?, 1000.00)",
                code, memberCode, code);
        return jdbc.queryForObject("SELECT id FROM demo_user_account WHERE user_code = ?", Long.class, code);
    }

    private long insertAction(long behaviorId, long userId, long accountId, String status,
                              int actionNo, Instant createdAt, String suffix) {
        jdbc.update("""
                INSERT INTO test_player_action
                    (behavior_id, user_id, account_id, issue_number, action_no, action_type,
                     idempotency_key, source_text, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'CHAT_TEXT', ?, '保留测试动作', ?, ?)
                """, behaviorId, userId, accountId, "retention-" + suffix, actionNo,
                "retention-action-" + suffix + "-" + actionNo, status, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM test_player_action WHERE idempotency_key = ?",
                Long.class, "retention-action-" + suffix + "-" + actionNo);
    }

    private long insertBet(String code, long accountId, String status, Instant settledAt) {
        jdbc.update("""
                INSERT INTO game_bet
                    (bet_code, issue_number, user_id, ball_number, play_type, parameters_text,
                     stake, odds_snapshot, settlement_status, net_profit, settled_at)
                VALUES (?, ?, ?, 1, 'FAN', '1', 10.00, 2.000, ?, 0.00, ?)
                """, code, "ret-" + code, accountId, status,
                settledAt == null ? null : Timestamp.from(settledAt));
        return jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, code);
    }

    private long insertWalletLedger(long accountId, Long betId, String key, Instant createdAt) {
        jdbc.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after, reason, operator_name,
                     idempotency_key, related_bet_id, created_at)
                VALUES (?, 'BET', -10.00, 1000.00, 990.00, '保留测试流水', '保留测试', ?, ?, ?)
                """, accountId, key, betId, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM demo_balance_ledger WHERE idempotency_key = ?", Long.class, key);
    }

    private long count(String sql, Object... parameters) {
        Long result = jdbc.queryForObject(sql, Long.class, parameters);
        return result == null ? 0 : result;
    }
}
