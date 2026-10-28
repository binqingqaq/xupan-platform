package com.xupan.server.report.service;

import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.report.repository.ReportUpstreamOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end tests for machine login, automatic reporting, center acceptance and explicit rejection. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "xupan.report.dispatch-interval-ms=3600000"})
class ReportClosureIntegrationTest {

    private static final String ADMIN = "reportclosure-admin";
    private static final String MACHINE_USER = "reportclosure-machine";
    private static final String PLAYER_USER = "reportclosure-player";
    private static final String GROUP_CODE = "REPORTCLOSURE_GROUP";
    private static final String MACHINE_CODE = "REPORTCLOSURE_MACHINE";
    private static final String CENTER_USERNAME = "REPORTCLOSURE_CENTER";
    private static final String PASSWORD = "ReportClosurePassword123";
    private static final String ISSUE_NUMBER = "3999001";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private CenterUpstreamAccountService centerAccountService;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportUpstreamOrderRepository reportOrderRepository;

    private long adminId;
    private long groupId;
    private long machineId;
    private long machineUserId;
    private long centerAccountId;
    private long playerAccountId;
    private final List<Long> createdBetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "报网闭环管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        groupId = agentAdminService.createGroup(GROUP_CODE, "报网闭环子账号", adminId).id();
        machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "报网闭环机器",
                PASSWORD, groupId, adminId).id();
        machineUserId = jdbc.queryForObject("SELECT account_user_id FROM agent WHERE id = ?",
                Long.class, machineId);
        centerAccountId = centerAccountService.createAccount(groupId, CENTER_USERNAME, PASSWORD,
                new BigDecimal("5000.00"), null, adminId).account().accountId();

        long playerUserId = userRepository.insert(PLAYER_USER, "报网闭环玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(playerUserId, "USER");
        playerAccountId = walletService.ensureWalletForUser(playerUserId, "报网闭环玩家");
        jdbc.update("UPDATE demo_user_account SET agent_id = ?, balance = 100.00 WHERE id = ?",
                machineId, playerAccountId);
        jdbc.update("INSERT INTO game_issue (game_code, issue_number, status, phase) "
                        + "VALUES ('AU8', ?, 'OPEN', 'BETTING')", ISSUE_NUMBER);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void acceptedBetCreatesCenterOrderThroughSameProcessGateway() {
        configureReport(true, false);
        reportService.login(machineUserId, CENTER_USERNAME, PASSWORD, "127.0.0.1");
        long betId = insertBet("REPORTCLOSURE_ACCEPTED");

        ReportService.DispatchResult result = reportService.dispatchPending(true, 50);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.submitted()).isEqualTo(1);
        assertThat(reportStatusOf(betId)).isEqualTo("ACCEPTED");
        assertThat(attemptCountOf(betId)).isEqualTo(1);
        assertThat(centerOrderStatus(betId)).isEqualTo("ACCEPTED");
        assertThat(centerOrderAmount(betId)).isEqualByComparingTo("10.00");
        assertThat(centerAvailableBalance()).isEqualByComparingTo("4990.00");
        assertThat(centerFrozenBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void explicitCenterRejectionKeepsCanceledBetAndRefundedAttemptAndLedger() {
        configureReport(true, true);
        reportService.login(machineUserId, CENTER_USERNAME, PASSWORD, "127.0.0.1");
        long betId = insertBet("REPORTCLOSURE_REJECTED");
        jdbc.update("UPDATE game_bet SET play_type = 'NOT_ACCEPTED_PLAY' WHERE id = ?", betId);
        BigDecimal beforeRefund = playerBalance();

        ReportService.DispatchResult result = reportService.dispatchPending(true, 50);

        assertThat(result.withdrawn()).isEqualTo(1);
        assertThat(betExists(betId)).isTrue();
        assertThat(betSettlementStatus(betId)).isEqualTo("CANCELED");
        assertThat(reportStatusOf(betId)).isEqualTo("REFUNDED");
        assertThat(attemptCountOf(betId)).isEqualTo(1);
        assertThat(centerOrderStatus(betId)).isEqualTo("REJECTED");
        assertThat(centerOrderRejectCode(betId)).isEqualTo("CENTER_PLAY_TYPE_INVALID");
        assertThat(playerBalance()).isEqualByComparingTo(beforeRefund.add(new BigDecimal("10.00")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_balance_ledger WHERE idempotency_key = ?",
                Long.class, "CANCEL_REFUND:" + betId)).isEqualTo(1L);
    }

    @Test
    void logoutLeavesBetUnsentAndDoesNotCreateCenterOrder() {
        configureReport(true, false);
        reportService.login(machineUserId, CENTER_USERNAME, PASSWORD, "127.0.0.1");
        reportService.logout(machineUserId, "127.0.0.1");
        long betId = insertBet("REPORTCLOSURE_LOGGED_OUT");

        assertThat(reportService.submitForBet(betId)).isEmpty();
        assertUnsentWithoutCenterOrder(betId);
    }

    @Test
    void disabledAutomaticReportingLeavesBetUnsentAndDoesNotCreateCenterOrder() {
        configureReport(false, false);
        reportService.login(machineUserId, CENTER_USERNAME, PASSWORD, "127.0.0.1");
        long betId = insertBet("REPORTCLOSURE_AUTO_OFF");

        ReportService.DispatchResult result = reportService.dispatchPending(true, 50);

        assertThat(result.scanned()).isZero();
        assertThat(reportService.submitForBet(betId)).isEmpty();
        assertUnsentWithoutCenterOrder(betId);
    }

    @Test
    void statusCountsIncludeRejectedAndManualAsFailedAndNotSentAsUnreported() {
        long rejectedBetId = insertBet("REPORTCLOSURE_COUNT_REJECTED");
        long manualBetId = insertBet("REPORTCLOSURE_COUNT_MANUAL");
        long notSentBetId = insertBet("REPORTCLOSURE_COUNT_NOT_SENT");
        addAttempt(rejectedBetId, "REJECTED");
        addAttempt(manualBetId, "MANUAL");
        addAttempt(notSentBetId, "NOT_SENT");

        ReportUpstreamOrderRepository.StatusCounts counts = reportOrderRepository.counts(machineId);

        assertThat(counts.failedCount()).isEqualTo(2);
        assertThat(counts.unreportedCount()).isEqualTo(1);
        assertThat(counts.timeoutCount()).isZero();
    }

    private void addAttempt(long betId, String status) {
        long attemptId = reportOrderRepository.insertAttempt(betId, machineId, null, 1, "PENDING", "CENTER",
                null, null, "REPORT-CLOSURE-COUNT-" + betId, "test fixture", null, null, Instant.now(), null);
        reportOrderRepository.resolveAttempt(attemptId, 0, status, status, "测试状态", "LOCAL-" + betId,
                Instant.now());
    }

    private void assertUnsentWithoutCenterOrder(long betId) {
        assertThat(reportStatusOf(betId)).isEqualTo("PENDING");
        assertThat(attemptCountOf(betId)).isZero();
        assertThat(centerOrderCount()).isZero();
    }

    private void configureReport(boolean auto, boolean withdraw) {
        jdbc.update("UPDATE agent SET report_auto = ?, report_withdraw = ? WHERE id = ?",
                auto, withdraw, machineId);
    }

    private long insertBet(String betCode) {
        jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES ('AU8', ?, ?, ?, ?, 1, 'FAN', '1', 10.00, 3.850)
                """, playerAccountId, betCode, betCode, ISSUE_NUMBER);
        Long id = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
        if (id == null) {
            throw new IllegalStateException("测试注单写入后未取得 ID");
        }
        createdBetIds.add(id);
        return id;
    }

    private String reportStatusOf(long betId) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT o.status FROM report_upstream_order o
                                  WHERE o.bet_id = b.id ORDER BY o.id DESC LIMIT 1), 'PENDING')
                  FROM game_bet b WHERE b.id = ?
                """, String.class, betId);
    }

    private long attemptCountOf(long betId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?",
                Long.class, betId);
        return count == null ? 0L : count;
    }

    private boolean betExists(long betId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE id = ?", Long.class, betId);
        return count != null && count > 0;
    }

    private String betSettlementStatus(long betId) {
        return jdbc.queryForObject("SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId);
    }

    private String centerOrderStatus(long betId) {
        return jdbc.queryForObject("SELECT status FROM center_order WHERE agent_order_no = ?",
                String.class, "BET-" + betId);
    }

    private String centerOrderRejectCode(long betId) {
        return jdbc.queryForObject("SELECT reject_code FROM center_order WHERE agent_order_no = ?",
                String.class, "BET-" + betId);
    }

    private BigDecimal centerOrderAmount(long betId) {
        return jdbc.queryForObject("SELECT amount FROM center_order WHERE agent_order_no = ?",
                BigDecimal.class, "BET-" + betId);
    }

    private long centerOrderCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE center_agent_id = ?",
                Long.class, centerAccountId);
        return count == null ? 0L : count;
    }

    private BigDecimal centerAvailableBalance() {
        return jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAccountId);
    }

    private BigDecimal centerFrozenBalance() {
        return jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAccountId);
    }

    private BigDecimal playerBalance() {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, playerAccountId);
    }

    private void clean() {
        jdbc.update("DELETE FROM chat_outbox WHERE message_id IN (SELECT id FROM chat_message "
                + "WHERE idempotency_key LIKE 'CHAT-REPORT-WITHDRAW-%')");
        jdbc.update("DELETE FROM chat_message WHERE idempotency_key LIKE 'CHAT-REPORT-WITHDRAW-%'");
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_risk_hit WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_machine_session WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", PLAYER_USER);
        jdbc.update("DELETE FROM game_bet WHERE bet_code LIKE 'REPORTCLOSURE_%'");
        jdbc.update("DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number = ?", ISSUE_NUMBER);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", PLAYER_USER);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_USERNAME);
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        List<Long> userIds = jdbc.queryForList("SELECT id FROM sys_user WHERE username IN (?, ?, ?)",
                Long.class, ADMIN, MACHINE_USER, PLAYER_USER);
        if (!userIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
            Object[] ids = userIds.toArray();
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM auth_ws_ticket WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM sys_user WHERE id IN (" + placeholders + ")", ids);
        }
        createdBetIds.clear();
    }
}
