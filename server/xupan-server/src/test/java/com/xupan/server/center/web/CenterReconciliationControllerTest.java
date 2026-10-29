package com.xupan.server.center.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两侧对账（阶段 B 第五步）。
 *
 * <p>对账的价值在于**能发现差异**，所以这里既验证"对平"也验证"确实报得出差异"，
 * 否则一个永远返回 balanced=true 的对账等于没有。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CenterReconciliationControllerTest {

    private static final String ADMIN = "center-recon-admin";
    private static final String PASSWORD = "CenterReconPassword123";
    private static final String CENTER_CODE = "RECON_CENTER_A";
    private static final String OTHER_CENTER_CODE = "RECON_CENTER_B";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private String adminToken;
    private long centerAgentId;
    private long agentId;
    private long accountId;
    private long betId;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        long adminId = userRepository.insert(ADMIN, "对账管理员",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        adminToken = login(ADMIN, PASSWORD);

        // 中心代理
        jdbc.update("""
                INSERT INTO center_agent (agent_code, agent_name, upstream_account, token_hash, token_prefix,
                                          status, credit_limit, available_balance, created_by)
                VALUES (?, '对账中心代理', 'recon_up_a', REPEAT('b', 64), 'ctr_recon', 'ACTIVE',
                        0.00, 0.00, ?)
                """, CENTER_CODE, adminId);
        centerAgentId = jdbc.queryForObject(
                "SELECT id FROM center_agent WHERE agent_code = ?", Long.class, CENTER_CODE);
        postCenterLedger(centerAgentId, "AVAILABLE", "OPENING_BALANCE", "IN",
                new BigDecimal("10000.00"), "RECON-OPEN-AVAILABLE-A");
        postCenterLedger(centerAgentId, "FROZEN", "OPENING_BALANCE", "IN",
                new BigDecimal("1.00"), "RECON-OPEN-FROZEN-A");
        postCenterLedger(centerAgentId, "UPSTREAM_UNSETTLED", "OPENING_BALANCE", "IN",
                new BigDecimal("1.00"), "RECON-OPEN-UNSETTLED-A");
        postCenterLedger(centerAgentId, "CREDIT_LIMIT", "OPENING_BALANCE", "IN",
                new BigDecimal("10000.00"), "RECON-OPEN-LIMIT-A");

        // 本地机器 + 账号 + 注单
        long accountUserId = userRepository.insert("recon-machine-account", "对账机器账号",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, status, system_owned, manual_betting_open, report_type,
                                   report_auto, report_online, center_agent_id, account_user_id, created_by)
                VALUES ('RECON_MACHINE_A', '对账机器', 'ACTIVE', FALSE, TRUE, 'CENTER', TRUE, TRUE, ?, ?, ?)
                """, centerAgentId, accountUserId, adminId);
        agentId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = 'RECON_MACHINE_A'", Long.class);
        jdbc.update("UPDATE agent SET score = 500.00 WHERE id = ?", agentId);
        insertAgentLedger("OPENING_BALANCE", "IN", "500.00", "0.00", "500.00", "RECON-MACHINE-OPENING");

        long playerUserId = userRepository.insert("recon-player", "对账玩家",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        jdbc.update("""
                INSERT INTO demo_user_account (sys_user_id, user_code, member_code, display_name, player_kind,
                                               status, agent_id, balance)
                VALUES (?, 'RECON-PLAYER-1', 'RECON001', '对账玩家', 'NORMAL', 'ACTIVE', ?, 1000.00)
                """, playerUserId, agentId);
        accountId = jdbc.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);

        jdbc.update("""
                INSERT INTO game_bet (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                                      play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES (?, 'RECON-BET-1', 'RECON-BET-1', '39999801', 1, 'FAN', '1', 100.00, 3.850, 'PENDING')
                """, accountId);
        betId = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = 'RECON-BET-1'", Long.class);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /** 只有本地上报记录、中心没有对应订单 → 报 MISSING_IN_CENTER。 */
    @Test
    void reportsMissingInCenter() throws Exception {
        insertLocalAttempt("ACCEPTED");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.missingInCenterCount").value(1))
                .andExpect(jsonPath("$.differences[0].type").value("MISSING_IN_CENTER"))
                .andExpect(jsonPath("$.differences[0].betId").value((int) betId));
    }

    /** 两侧都有且一致 → 对平。 */
    @Test
    void balancedWhenBothSidesAgree() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertCenterOrder("ACCEPTED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.differenceCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(0))
                .andExpect(jsonPath("$.inconsistentCenterBuckets").value(0));
    }

    @Test
    void canceledCenterOrderMatchesResolvedLocalReportStatus() throws Exception {
        insertLocalAttempt("CANCELLED");
        insertCenterOrder("CANCELLED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusMismatchCount").value(0));
    }

    @Test
    void refundedCenterOrderMatchesResolvedLocalReportStatus() throws Exception {
        insertLocalAttempt("REFUNDED");
        insertCenterOrder("REFUNDED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusMismatchCount").value(0));
    }

    @Test
    void acceptedOrderWithoutFreezeEntriesCannotBalance() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertCenterOrder("ACCEPTED", "100.00");
        jdbc.update("DELETE FROM center_agent_ledger WHERE idempotency_key IN (?, ?)",
                "RECON-FREEZE-OUT-" + centerOrderId(), "RECON-FREEZE-IN-" + centerOrderId());
        jdbc.update("UPDATE center_agent SET available_balance=10000.00, frozen_balance=1.00 WHERE id=?",
                centerAgentId);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialIssueCount").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    /** 同一注单号在另一个中心账号下是独立事实；重试历史按本账号最新尝试对账。 */
    @Test
    void scopesOrdersByCenterAccountAndUsesLatestAttempt() throws Exception {
        insertLocalAttempt("REJECTED", 1, centerAgentId);
        insertLocalAttempt("ACCEPTED", 2, centerAgentId);
        insertCenterOrder(centerAgentId, "ACCEPTED", "100.00");

        long otherCenterAgentId = insertOtherCenterAccount();
        insertCenterOrder(otherCenterAgentId, "ACCEPTED", "90.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.localTotal").value(1))
                .andExpect(jsonPath("$.counts.centerTotal").value(2))
                .andExpect(jsonPath("$.missingInLocalCount").value(1))
                .andExpect(jsonPath("$.statusMismatchCount").value(0))
                .andExpect(jsonPath("$.amountMismatchCount").value(0));

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("machineId", String.valueOf(agentId))
                        .param("centerAgentId", String.valueOf(centerAgentId))
                        .param("issueNumber", "39999801")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.localTotal").value(1))
                .andExpect(jsonPath("$.counts.centerTotal").value(1))
                .andExpect(jsonPath("$.differenceCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(0))
                .andExpect(jsonPath("$.financial.reservedOrders").value(1))
                .andExpect(jsonPath("$.differences").isEmpty());
    }

    @Test
    void oneSidedSettlementPreventsBalancedResult() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertCenterOrder("ACCEPTED", "100.00");
        jdbc.update("UPDATE game_bet SET settlement_status = 'WIN' WHERE id = ?", betId);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("centerAgentId", String.valueOf(centerAgentId))
                        .param("issueNumber", "39999801")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenceCount").value(0))
                .andExpect(jsonPath("$.financial.oneSidedSettlementCount").value(1))
                .andExpect(jsonPath("$.financialIssueCount").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void legacyRefundUsesFrozenSnapshotWhenRiskVersionWasNotCaptured() throws Exception {
        insertLegacyRefundedCenterOrder("400.00", "400.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("centerAgentId", String.valueOf(centerAgentId))
                        .param("issueNumber", "39999801")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financial.refundedOrders").value(1))
                .andExpect(jsonPath("$.financial.refundedRiskAmount").value(400.00))
                .andExpect(jsonPath("$.financial.refundDispositionAmount").value(400.00))
                .andExpect(jsonPath("$.financial.refundMismatchCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(0))
                .andExpect(jsonPath("$.balanced").value(true));
    }

    @Test
    void refundDispositionWithoutReleaseEntriesCannotBalance() throws Exception {
        insertLegacyRefundedCenterOrder("400.00", "400.00", false);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("centerAgentId", String.valueOf(centerAgentId))
                        .param("issueNumber", "39999801")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financial.refundMismatchCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(2))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void cancelledOrderWithoutReleaseEntriesCannotBalance() throws Exception {
        insertCancelledOrderWithoutLedger("100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("centerAgentId", String.valueOf(centerAgentId))
                        .param("issueNumber", "39999801")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialIssueCount").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void machineFundedAcceptedOrderWithoutReserveCannotBalance() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertMachineFundedAcceptedOrder();

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenceCount").value(0))
                .andExpect(jsonPath("$.inconsistentCenterBuckets").value(0))
                .andExpect(jsonPath("$.financial.settlementLedgerMismatchCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void settledOrderMissingCenterSettlementEntryCannotBalance() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertSettledCenterFundedOrder("AGENT_WIN", "10.00", "WIN", "10.00", true);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenceCount").value(0))
                .andExpect(jsonPath("$.inconsistentCenterBuckets").value(0))
                .andExpect(jsonPath("$.financial.settlementLedgerMismatchCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void centerSettlementResultOpposingPlayerResultCannotBalance() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertSettledCenterFundedOrder("AGENT_LOSE", "100.00", "WIN", "10.00", false);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenceCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void settledMachineRiskWithoutReleaseCannotBalance() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertMachineFundedSettledOrder();

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenceCount").value(0))
                .andExpect(jsonPath("$.financialIssueCount").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    /** 中心受理但本地记失败 → 状态语义不一致。 */
    @Test
    void reportsStatusMismatch() throws Exception {
        insertLocalAttempt("REJECTED");
        insertCenterOrder("ACCEPTED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.statusMismatchCount").value(1))
                .andExpect(jsonPath("$.differences[0].type").value("STATUS_MISMATCH"))
                .andExpect(jsonPath("$.differences[0].localStatus").value("REJECTED"))
                .andExpect(jsonPath("$.differences[0].centerStatus").value("ACCEPTED"));
    }

    /** 金额不一致是必须逐笔查的一类差异。 */
    @Test
    void reportsAmountMismatch() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertCenterOrder("ACCEPTED", "90.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.amountMismatchCount").value(1))
                .andExpect(jsonPath("$.differences[0].localAmount").value(100.0))
                .andExpect(jsonPath("$.differences[0].centerAmount").value(90.0));
    }

    /** 中心有订单但本地无上报记录 → 脏数据。 */
    @Test
    void reportsMissingInLocal() throws Exception {
        insertCenterOrder("ACCEPTED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.missingInLocalCount").value(1));
    }

    /** 未解决项（本地 PENDING/TIMEOUT）会被计数，便于盯遗留。 */
    @Test
    void countsUnresolvedItems() throws Exception {
        insertLocalAttempt("TIMEOUT");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.localUnresolved").value(1))
                .andExpect(jsonPath("$.counts.localTotal").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    /** 账本自检：中心三层与本地机器都一致时，两个不一致计数都为 0。 */
    @Test
    void reportsBothLedgersSelfConsistent() throws Exception {
        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inconsistentCenterBuckets").value(0))
                .andExpect(jsonPath("$.inconsistentAgentBuckets").value(0));
    }

    /** 账本被破坏时对账必须报出来（否则自检形同虚设）。 */
    @Test
    void detectsBrokenAgentLedger() throws Exception {
        // 人为把积分改掉，制造"流水与余额不一致"
        jdbc.update("UPDATE agent SET score = 999.00 WHERE id = ?", agentId);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inconsistentAgentBuckets").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void detectsLedgerFormulaErrorEvenWhenLatestBalanceMatchesAccount() throws Exception {
        jdbc.update("UPDATE agent SET score = 601.00 WHERE id = ?", agentId);
        insertAgentLedger("MACHINE_ADJUST", "IN", "100.00", "500.00", "601.00", "recon-formula-bad");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inconsistentAgentBuckets").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void detectsLedgerDiscontinuityEvenWhenLatestBalanceMatchesAccount() throws Exception {
        jdbc.update("UPDATE agent SET score = 601.00 WHERE id = ?", agentId);
        insertAgentLedger("MACHINE_ADJUST", "IN", "100.00", "501.00", "601.00", "recon-chain-bad");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inconsistentAgentBuckets").value(1))
                .andExpect(jsonPath("$.balanced").value(false));
    }

    @Test
    void validatesLimitAndPermission() throws Exception {
        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("limit", "1000")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_RECONCILIATION_QUERY_INVALID"));

        mockMvc.perform(get("/api/admin/center/agents/reconciliation"))
                .andExpect(status().isUnauthorized());
    }

    private void insertLocalAttempt(String status) {
        insertLocalAttempt(status, 1, centerAgentId);
    }

    private void insertLocalAttempt(String status, int attemptNo, long attemptCenterAgentId) {
        jdbc.update("""
                INSERT INTO report_upstream_order (bet_id, agent_id, center_agent_id, attempt_no, status, report_type,
                                                   idempotency_key, sent_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, 'CENTER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, betId, agentId, attemptCenterAgentId, attemptNo, status,
                "RECON-ATTEMPT-" + attemptCenterAgentId + "-" + attemptNo);
    }

    private void insertCenterOrder(String status, String amount) {
        insertCenterOrder(centerAgentId, status, amount);
    }

    private void insertCenterOrder(long orderCenterAgentId, String status, String amount) {
        BigDecimal stake = new BigDecimal(amount);
        boolean accepted = "ACCEPTED".equals(status);
        BigDecimal risk = accepted ? stake : BigDecimal.ZERO.setScale(2);
        BigDecimal centerFunded = accepted ? centerFunded(risk) : BigDecimal.ZERO.setScale(2);
        BigDecimal machineFunded = accepted ? risk.subtract(centerFunded) : BigDecimal.ZERO.setScale(2);
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, local_bet_id, local_agent_id,
                                          game_code, issue_number, play_type, play_text, odds, amount,
                                          status, risk_amount, risk_policy_version, frozen_amount,
                                          machine_funded_amount, accepted_at)
                VALUES (?, ?, ?, ?, 'K28', '39999801', 'FAN', 'params=1', 3.8500, ?, ?, ?, ?, ?, ?,
                        CURRENT_TIMESTAMP)
                """, orderCenterAgentId, "BET-" + betId, betId, agentId, stake, status, risk,
                accepted ? 1 : 0, centerFunded, machineFunded);
        if (accepted) {
            long orderId = jdbc.queryForObject("SELECT id FROM center_order WHERE center_agent_id = ? AND agent_order_no = ?",
                    Long.class, orderCenterAgentId, "BET-" + betId);
            postCenterOrderLedger(orderCenterAgentId, orderId, "AVAILABLE", "BET_FREEZE", "OUT", centerFunded,
                    "RECON-FREEZE-OUT-" + orderId);
            postCenterOrderLedger(orderCenterAgentId, orderId, "FROZEN", "BET_FREEZE", "IN", centerFunded,
                    "RECON-FREEZE-IN-" + orderId);
            postMachineOrderLedger(orderId, "CENTER_RISK_RESERVE", "OUT", machineFunded,
                    "RECON-MACHINE-RESERVE-" + orderId);
        }
    }

    private void insertLegacyRefundedCenterOrder(String frozenAmount, String refundAmount) {
        insertLegacyRefundedCenterOrder(frozenAmount, refundAmount, true);
    }

    private void insertLegacyRefundedCenterOrder(String frozenAmount, String refundAmount, boolean includeRelease) {
        BigDecimal totalRisk = new BigDecimal(frozenAmount);
        BigDecimal machineFunded = totalRisk.movePointLeft(1).setScale(2);
        BigDecimal centerFunded = totalRisk.subtract(machineFunded);
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, local_agent_id,
                                          game_code, issue_number, play_type, play_text, odds, amount,
                                          status, risk_amount, frozen_amount,
                                          machine_funded_amount, risk_policy_version, accepted_at)
                VALUES (?, ?, ?, 'K28', '39999801', 'FAN', 'params=1', 3.8500, ?, 'REFUNDED',
                        0.00, ?, ?, 0, CURRENT_TIMESTAMP)
                """, centerAgentId, "LEGACY-REFUND-" + betId, agentId, totalRisk, centerFunded, machineFunded);
        long orderId = jdbc.queryForObject("SELECT id FROM center_order WHERE agent_order_no = ?",
                Long.class, "LEGACY-REFUND-" + betId);
        postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", "BET_FREEZE", "OUT", centerFunded,
                "RECON-LEGACY-FREEZE-OUT-" + orderId);
        postCenterOrderLedger(centerAgentId, orderId, "FROZEN", "BET_FREEZE", "IN", centerFunded,
                "RECON-LEGACY-FREEZE-IN-" + orderId);
        postMachineOrderLedger(orderId, "CENTER_RISK_RESERVE", "OUT", machineFunded,
                "RECON-LEGACY-MACHINE-RESERVE-" + orderId);
        if (includeRelease) {
            postCenterOrderLedger(centerAgentId, orderId, "FROZEN", "BET_UNFREEZE", "OUT", centerFunded,
                    "RECON-LEGACY-REFUND-OUT-" + orderId);
            postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", "BET_UNFREEZE", "IN", centerFunded,
                    "RECON-LEGACY-REFUND-IN-" + orderId);
            postMachineOrderLedger(orderId, "CENTER_RISK_RELEASE", "IN", machineFunded,
                    "RECON-LEGACY-MACHINE-RELEASE-" + orderId);
        }
        jdbc.update("""
                INSERT INTO center_order_disposition (center_order_id, action, from_status, to_status,
                                                       amount, reason, idempotency_key)
                VALUES (?, 'REFUND', 'ACCEPTED', 'REFUNDED', ?, 'legacy refund fixture', ?)
                """, orderId, new BigDecimal(refundAmount), "LEGACY-REFUND-DISP-" + betId);
    }

    private void insertCancelledOrderWithoutLedger(String amount) {
        BigDecimal value = new BigDecimal(amount);
        String orderNo = "CANCEL-NO-LEDGER-" + betId;
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, game_code, issue_number, play_type,
                                          play_text, odds, amount, status, risk_amount, frozen_amount,
                                          machine_funded_amount, risk_policy_version, accepted_at)
                VALUES (?, ?, 'K28', '39999801', 'FAN', 'params=1', 3.8500, ?, 'CANCELLED', ?, ?, 0.00, 1,
                        CURRENT_TIMESTAMP)
                """, centerAgentId, orderNo, value, value, value);
        long orderId = jdbc.queryForObject("SELECT id FROM center_order WHERE agent_order_no = ?",
                Long.class, orderNo);
        postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", "BET_FREEZE", "OUT", value,
                "RECON-CANCEL-FREEZE-OUT-" + orderId);
        postCenterOrderLedger(centerAgentId, orderId, "FROZEN", "BET_FREEZE", "IN", value,
                "RECON-CANCEL-FREEZE-IN-" + orderId);
        jdbc.update("""
                INSERT INTO center_order_disposition (center_order_id, action, from_status, to_status,
                                                       amount, reason, idempotency_key)
                VALUES (?, 'CANCEL', 'ACCEPTED', 'CANCELLED', ?, 'cancel fixture', ?)
                """, orderId, value, "RECON-CANCEL-DISP-" + orderId);
    }

    private void insertMachineFundedAcceptedOrder() {
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, local_bet_id, local_agent_id,
                                          game_code, issue_number, play_type, play_text, odds, amount, status,
                                          risk_amount, risk_policy_version, frozen_amount, machine_funded_amount,
                                          accepted_at)
                VALUES (?, ?, ?, ?, 'K28', '39999801', 'FAN', 'params=1', 3.8500, 100.00, 'ACCEPTED',
                        100.00, 1, 0.00, 100.00, CURRENT_TIMESTAMP)
                """, centerAgentId, "BET-" + betId, betId, agentId);
    }

    private long insertSettledCenterFundedOrder(String centerResult, String centerSettleAmount,
                                                String localResult, String localNetProfit,
                                                boolean omitUnsettledDebit) {
        BigDecimal amount = new BigDecimal("100.00");
        BigDecimal settleAmount = new BigDecimal(centerSettleAmount);
        BigDecimal centerFunded = centerFunded(amount);
        BigDecimal machineFunded = amount.subtract(centerFunded);
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, local_bet_id, local_agent_id,
                                          game_code, issue_number, play_type, play_text, odds, amount, status,
                                          risk_amount, risk_policy_version, frozen_amount, machine_funded_amount,
                                          settle_result, settle_amount, accepted_at, settled_at)
                VALUES (?, ?, ?, ?, 'K28', '39999801', 'FAN', 'params=1', 3.8500, ?, 'SETTLED',
                        ?, 1, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, centerAgentId, "BET-" + betId, betId, agentId, amount, amount, centerFunded,
                machineFunded,
                centerResult, settleAmount);
        long orderId = centerOrderId();
        jdbc.update("UPDATE game_bet SET settlement_status = ?, net_profit = ? WHERE id = ?",
                localResult, new BigDecimal(localNetProfit), betId);
        postMachineOrderLedger(orderId, "CENTER_RISK_RESERVE", "OUT", machineFunded,
                "RECON-SETTLE-MACHINE-RESERVE-" + orderId);

        postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", "BET_FREEZE", "OUT", centerFunded,
                "RECON-SETTLE-FREEZE-OUT-" + orderId);
        postCenterOrderLedger(centerAgentId, orderId, "FROZEN", "BET_FREEZE", "IN", centerFunded,
                "RECON-SETTLE-FREEZE-IN-" + orderId);
        postCenterOrderLedger(centerAgentId, orderId, "FROZEN", "UPSTREAM_MOVE", "OUT", centerFunded,
                "RECON-SETTLE-MOVE-OUT-" + orderId);
        postCenterOrderLedger(centerAgentId, orderId, "UPSTREAM_UNSETTLED", "UPSTREAM_MOVE", "IN", centerFunded,
                "RECON-SETTLE-MOVE-IN-" + orderId);
        String entryType = "AGENT_WIN".equals(centerResult) ? "SETTLE_WIN" : "SETTLE_LOSE";
        if (!omitUnsettledDebit) {
            postCenterOrderLedger(centerAgentId, orderId, "UPSTREAM_UNSETTLED", entryType, "OUT", centerFunded,
                    "RECON-SETTLE-UNSETTLED-" + orderId);
        } else {
            // Keep the aggregate net and bucket chains balanced while the order debit lands in the wrong bucket.
            postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", entryType, "OUT", centerFunded,
                    "RECON-SETTLE-WRONG-BUCKET-" + orderId);
        }
        BigDecimal actualCenterLoss = "AGENT_WIN".equals(centerResult) ? settleAmount : BigDecimal.ZERO;
        BigDecimal centerConsumed = actualCenterLoss.min(centerFunded);
        BigDecimal machineConsumed = actualCenterLoss.subtract(centerFunded).max(BigDecimal.ZERO);
        BigDecimal centerRelease = centerFunded.subtract(centerConsumed).max(BigDecimal.ZERO);
        BigDecimal machineRelease = machineFunded.subtract(machineConsumed).max(BigDecimal.ZERO);
        if (centerRelease.signum() > 0) {
            postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", entryType, "IN", centerRelease,
                    "RECON-SETTLE-RELEASE-" + orderId);
        }
        if ("AGENT_LOSE".equals(centerResult)) {
            postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", entryType, "IN", amount,
                    "RECON-SETTLE-STAKE-" + orderId);
        }
        postMachineOrderLedger(orderId, "CENTER_RISK_RELEASE", "IN", machineRelease,
                "RECON-SETTLE-MACHINE-RELEASE-" + orderId);
        return orderId;
    }

    private long insertMachineFundedSettledOrder() {
        BigDecimal amount = new BigDecimal("100.00");
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, local_bet_id, local_agent_id,
                                          game_code, issue_number, play_type, play_text, odds, amount, status,
                                          risk_amount, risk_policy_version, frozen_amount, machine_funded_amount,
                                          settle_result, settle_amount, accepted_at, settled_at)
                VALUES (?, ?, ?, ?, 'K28', '39999801', 'FAN', 'params=1', 3.8500, ?, 'SETTLED',
                        ?, 1, 0.00, ?, 'AGENT_LOSE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, centerAgentId, "BET-" + betId, betId, agentId, amount, amount, amount, amount);
        long orderId = centerOrderId();
        jdbc.update("UPDATE game_bet SET settlement_status = 'LOSE', net_profit = -100.00 WHERE id = ?", betId);
        postCenterOrderLedger(centerAgentId, orderId, "AVAILABLE", "SETTLE_LOSE", "IN", amount,
                "RECON-MACHINE-SETTLE-STAKE-" + orderId);

        postMachineOrderLedger(orderId, "CENTER_RISK_RESERVE", "OUT", amount,
                "RECON-MACHINE-RESERVE-" + orderId);
        return orderId;
    }

    private long centerOrderId() {
        return jdbc.queryForObject("SELECT id FROM center_order WHERE center_agent_id = ? AND agent_order_no = ?",
                Long.class, centerAgentId, "BET-" + betId);
    }

    private void postCenterLedger(long targetCenterId, String bucket, String entryType, String direction,
                                  BigDecimal amount, String idempotencyKey) {
        appendCenterLedger(targetCenterId, bucket, entryType, direction, amount,
                null, null, idempotencyKey);
    }

    private void postCenterOrderLedger(long targetCenterId, long orderId, String bucket, String entryType,
                                      String direction, BigDecimal amount, String idempotencyKey) {
        appendCenterLedger(targetCenterId, bucket, entryType, direction, amount,
                "CENTER_ORDER", String.valueOf(orderId), idempotencyKey);
    }

    private BigDecimal centerFunded(BigDecimal risk) {
        return risk.multiply(new BigDecimal("0.90")).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private void postMachineOrderLedger(long orderId, String entryType, String direction,
                                        BigDecimal amount, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        BigDecimal before = jdbc.queryForObject("SELECT score FROM agent WHERE id = ?", BigDecimal.class, agentId);
        BigDecimal after = "IN".equals(direction) ? before.add(amount) : before.subtract(amount);
        appendAgentLedger(agentId, entryType, direction, amount, before, after,
                "CENTER_ORDER", String.valueOf(orderId), idempotencyKey);
    }

    private void appendCenterLedger(long targetCenterId, String bucket, String entryType, String direction,
                                   BigDecimal amount, String referenceType, String referenceId,
                                   String idempotencyKey) {
        String column = switch (bucket) {
            case "AVAILABLE" -> "available_balance";
            case "FROZEN" -> "frozen_balance";
            case "UPSTREAM_UNSETTLED" -> "upstream_unsettled";
            case "CREDIT_LIMIT" -> "credit_limit";
            default -> throw new IllegalArgumentException("unsupported center bucket: " + bucket);
        };
        BigDecimal before = jdbc.queryForObject("SELECT " + column + " FROM center_agent WHERE id = ?",
                BigDecimal.class, targetCenterId);
        BigDecimal after = "IN".equals(direction) ? before.add(amount) : before.subtract(amount);
        jdbc.update("UPDATE center_agent SET " + column + " = ? WHERE id = ?", after, targetCenterId);
        jdbc.update("""
                INSERT INTO center_agent_ledger (center_agent_id, bucket, entry_type, direction, amount,
                                                 balance_before, balance_after, reason, reference_type,
                                                 reference_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'reconciliation fixture', ?, ?, ?)
                """, targetCenterId, bucket, entryType, direction, amount, before, after,
                referenceType, referenceId, idempotencyKey);
    }

    private void insertAgentLedger(String entryType, String direction, String amount, String before,
                                  String after, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO agent_ledger (agent_id, entry_type, direction, amount, balance_before, balance_after,
                                          idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, agentId, entryType, direction, new BigDecimal(amount), new BigDecimal(before),
                new BigDecimal(after), idempotencyKey);
    }

    private void appendAgentLedger(long targetAgentId, String entryType, String direction, BigDecimal amount,
                                   BigDecimal before, BigDecimal after, String referenceType, String referenceId,
                                   String idempotencyKey) {
        jdbc.update("UPDATE agent SET score = ? WHERE id = ?", after, targetAgentId);
        jdbc.update("""
                INSERT INTO agent_ledger (agent_id, entry_type, direction, amount, balance_before, balance_after,
                                          reference_type, reference_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, targetAgentId, entryType, direction, amount, before, after,
                referenceType, referenceId, idempotencyKey);
    }

    private long insertOtherCenterAccount() {
        jdbc.update("""
                INSERT INTO center_agent (agent_code, agent_name, upstream_account, token_hash, token_prefix,
                                          status, credit_limit, available_balance, created_by)
                VALUES (?, '另一个对账中心代理', 'recon_up_b', REPEAT('c', 64), 'ctr_recon_b', 'ACTIVE',
                        0.00, 0.00, (SELECT id FROM sys_user WHERE username = ?))
                """, OTHER_CENTER_CODE, ADMIN);
        long otherCenterAgentId = jdbc.queryForObject("SELECT id FROM center_agent WHERE agent_code = ?",
                Long.class, OTHER_CENTER_CODE);
        postCenterLedger(otherCenterAgentId, "AVAILABLE", "OPENING_BALANCE", "IN",
                new BigDecimal("10000.00"), "RECON-OPEN-AVAILABLE-B");
        postCenterLedger(otherCenterAgentId, "FROZEN", "OPENING_BALANCE", "IN",
                new BigDecimal("1.00"), "RECON-OPEN-FROZEN-B");
        postCenterLedger(otherCenterAgentId, "UPSTREAM_UNSETTLED", "OPENING_BALANCE", "IN",
                new BigDecimal("1.00"), "RECON-OPEN-UNSETTLED-B");
        postCenterLedger(otherCenterAgentId, "CREDIT_LIMIT", "OPENING_BALANCE", "IN",
                new BigDecimal("10000.00"), "RECON-OPEN-LIMIT-B");
        return otherCenterAgentId;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearerOf(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code IN (?, ?)))", CENTER_CODE, OTHER_CENTER_CODE);
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code IN (?, ?))", CENTER_CODE, OTHER_CENTER_CODE);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code IN (?, ?))", CENTER_CODE, OTHER_CENTER_CODE);
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = 'RECON_MACHINE_A')");
        jdbc.update("DELETE FROM game_bet WHERE bet_code = 'RECON-BET-1'");
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = 'RECON_MACHINE_A')");
        jdbc.update("UPDATE agent SET report_type = NULL, center_agent_id = NULL "
                + "WHERE agent_code = 'RECON_MACHINE_A'");
        jdbc.update("DELETE FROM demo_user_account WHERE member_code = 'RECON001'");
        jdbc.update("DELETE FROM agent WHERE agent_code = 'RECON_MACHINE_A'");
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_CODE);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", OTHER_CENTER_CODE);
        for (String username : new String[] {ADMIN, "recon-machine-account", "recon-player"}) {
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user WHERE username = ?", username);
        }
    }
}
