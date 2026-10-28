package com.xupan.server.report;

import com.xupan.server.report.repository.ReportUpstreamOrderRepository;
import com.xupan.server.report.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 回归测试：上报重报的退避、重试上限与**头部阻塞消除**。
 *
 * <p>核心断言是"坏单不能饿死新单"——这正是原实现的缺陷：
 * 用 {@code ORDER BY b.id LIMIT 50} 且重报集合含 {@code REJECTED/TIMEOUT}，
 * 50 笔长期失败单会永久占满批次，此后新注单再也排不上上报。</p>
 *
 * <p>测试直接针对仓储的候选查询，不依赖定时器，因此稳定可重复。</p>
 */
@SpringBootTest(properties = "xupan.automation.enabled=false")
@ActiveProfiles("test")
@DisplayName("上报重报退避与头部阻塞（P0-2）")
class ReportRetryBackoffTest {

    private static final String REPORT_AGENT_CODE = "REPORT_BACKOFF_MACHINE";
    private static final List<String> RETRY_STATUSES = List.of("PENDING", "REJECTED", "TIMEOUT");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReportUpstreamOrderRepository orderRepository;

    private long agentId;
    private long accountId;

    @BeforeEach
    void setUp() {
        clean();
        long machineUserId = insertUser("report-backoff-machine");
        agentId = insertAndReturnId("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status,
                                   report_type, report_auto, report_online, report_withdraw)
                VALUES (?, '退避测试机器', ?, FALSE, 'ACTIVE', 'K28', TRUE, TRUE, FALSE)
                """, REPORT_AGENT_CODE, machineUserId);
        long playerUserId = insertUser("report-backoff-player");
        jdbc.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('REPORT_BACKOFF_PLAYER', '退避测试玩家', 1000.00, 'ACTIVE', ?, ?,
                        'REAL', 'NORMAL', 'V-BACKOFF-001')
                """, playerUserId, agentId);
        accountId = jdbc.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
    }

    @Test
    @DisplayName("退避未到期的失败单不参与上报，新单优先被捞取")
    void delayedRetryDoesNotBlockFreshBets() {
        // 40 笔"老"失败单，全部被退避推到未来 1 小时——模拟坏单积压
        for (int i = 0; i < 40; i++) {
            long betId = insertBet("BACKOFF_OLD_" + i);
            long attemptId = insertAttempt(betId, "REJECTED", i + 1);
            jdbc.update("UPDATE report_upstream_order SET next_retry_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().plusSeconds(3600)), attemptId);
        }
        // 3 笔全新注单（从未上报，无上报订单行）
        long firstFreshBetId = insertBet("BACKOFF_FRESH_0");
        insertBet("BACKOFF_FRESH_1");
        insertBet("BACKOFF_FRESH_2");

        List<ReportUpstreamOrderRepository.BetRow> candidates =
                orderRepository.findSubmittableBets(RETRY_STATUSES, 50);

        // 关键断言：批次里必须有新单。原实现下这里会是 0（被 40 笔老单占满）。
        assertThat(candidates)
                .as("新单必须能进入批次，不能被退避中的失败单饿死")
                .isNotEmpty();
        assertThat(candidates.get(0).betId())
                .as("新单排在最前（ORDER BY 让未上报的单优先），且是 id 最小的那笔新单")
                .isEqualTo(firstFreshBetId);
    }

    @Test
    @DisplayName("退避已到期的失败单可以重报")
    void expiredRetryIsSubmittable() {
        long betId = insertBet("BACKOFF_DUE");
        long attemptId = insertAttempt(betId, "REJECTED", 1);
        jdbc.update("UPDATE report_upstream_order SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), attemptId);

        List<ReportUpstreamOrderRepository.BetRow> candidates =
                orderRepository.findSubmittableBets(RETRY_STATUSES, 50);

        assertThat(candidates).extracting(ReportUpstreamOrderRepository.BetRow::betId).contains(betId);
    }

    @Test
    @DisplayName("转人工（MANUAL）的注单不再参与自动上报")
    void manualOrdersLeaveAutomaticPool() {
        long betId = insertBet("BACKOFF_MANUAL");
        insertAttempt(betId, "MANUAL", 5);

        List<ReportUpstreamOrderRepository.BetRow> candidates =
                orderRepository.findSubmittableBets(RETRY_STATUSES, 50);

        assertThat(candidates).extracting(ReportUpstreamOrderRepository.BetRow::betId).doesNotContain(betId);
    }

    @Test
    @DisplayName("scheduleRetry 累加尝试次数，markManual 可转人工")
    void retrySchedulingAccumulatesAttempts() {
        long betId = insertBet("BACKOFF_COUNT");
        // insertAttempt 会把 attempt_count 置为 attemptNo(=1)，因此这里从 1 起累加。
        long attemptId = insertAttempt(betId, "REJECTED", 1);

        int first = orderRepository.scheduleRetry(attemptId, Instant.now().plusSeconds(30));
        int second = orderRepository.scheduleRetry(attemptId, Instant.now().plusSeconds(60));
        assertThat(first).as("第一次重试后计数为 2（原有 1 次 + 本次）").isEqualTo(2);
        assertThat(second).as("第二次重试后计数为 3").isEqualTo(3);

        assertThat(orderRepository.markManual(attemptId, "RETRY_EXHAUSTED", "达上限")).isEqualTo(1);
        assertThat(orderRepository.currentAttemptCount(betId)).isEqualTo(3);
    }

    @Test
    @DisplayName("人工重报清障：计数清零、MANUAL 退回 REJECTED 并立即可报")
    void resetForManualResubmitClearsBackoff() {
        long betId = insertBet("BACKOFF_RESET");
        long attemptId = insertAttemptWithCount(betId, "MANUAL", 5, 5);
        jdbc.update("UPDATE report_upstream_order SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(3600)), attemptId);

        orderRepository.resetForManualResubmit(betId);

        assertThat(orderRepository.currentAttemptCount(betId)).isZero();
        List<ReportUpstreamOrderRepository.BetRow> candidates =
                orderRepository.findSubmittableBets(RETRY_STATUSES, 50);
        assertThat(candidates).extracting(ReportUpstreamOrderRepository.BetRow::betId)
                .as("解除人工标记后应重新进入候选集合")
                .contains(betId);
    }

    @Test
    @DisplayName("人工待处置数量可统计（运维告警用）")
    void manualCountIsQueryable() {
        long betId = insertBet("BACKOFF_MANUAL_COUNT");
        insertAttemptWithCount(betId, "MANUAL", 5, 5);
        assertThat(orderRepository.manualCount(agentId)).isEqualTo(1);
    }

    private long insertBet(String betCode) {
        return insertAndReturnId("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES ('AU8', ?, ?, ?, '39999601', 1, 'FAN', '1', 10.00, 3.850, 'PENDING')
                """, accountId, betCode, betCode);
    }

    /** 直接插入一次上报尝试行；状态可为 REJECTED/TIMEOUT/MANUAL。 */
    private long insertAttempt(long betId, String status, int attemptNo) {
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, attempt_no, status, report_type, idempotency_key,
                     response_code, attempt_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'K28', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, betId, agentId, attemptNo, status, "REPORT-" + betId + "-" + attemptNo,
                status, attemptNo);
        return jdbc.queryForObject("SELECT MAX(id) FROM report_upstream_order WHERE bet_id = ?",
                Long.class, betId);
    }

    /** 同上，但尝试计数独立指定（用于构造"已重试 N 次"的存量）。 */
    private long insertAttemptWithCount(long betId, String status, int attemptNo, int attemptCount) {
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, attempt_no, status, report_type, idempotency_key,
                     response_code, attempt_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'K28', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, betId, agentId, attemptNo, status, "REPORT-" + betId + "-" + attemptNo,
                status, attemptCount);
        return jdbc.queryForObject("SELECT MAX(id) FROM report_upstream_order WHERE bet_id = ?",
                Long.class, betId);
    }

    private long insertUser(String username) {
        // internal_code 在 V18 之后为 NOT NULL 且唯一，必须显式提供。
        jdbc.update("""
                INSERT INTO sys_user (username, display_name, password_hash, status, internal_code)
                VALUES (?, ?, 'x', 'ACTIVE', ?)
                """, username, username, "wxid_" + username);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private long insertAndReturnId(String sql, Object... args) {
        jdbc.update(sql, args);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void clean() {
        // 注意：本类创建的注单/账户在其它测试的统计里也会被数到，
        // 因此 setUp 与 tearDown 都要清（tearDown 防止残留影响后续测试类）。
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", REPORT_AGENT_CODE);
        jdbc.update("DELETE FROM game_bet WHERE user_id IN (SELECT id FROM demo_user_account "
                + "WHERE agent_id IN (SELECT id FROM agent WHERE agent_code = ?))", REPORT_AGENT_CODE);
        jdbc.update("DELETE FROM demo_user_account WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", REPORT_AGENT_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", REPORT_AGENT_CODE);
        jdbc.update("DELETE FROM sys_user WHERE username IN "
                + "('report-backoff-machine', 'report-backoff-player')");
    }

    @AfterEach
    void tearDown() {
        clean();
    }
}
