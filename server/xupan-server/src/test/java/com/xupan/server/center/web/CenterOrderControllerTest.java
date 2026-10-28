package com.xupan.server.center.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.agent.service.AgentLedgerService;
import com.xupan.server.center.service.CenterOrderService;
import com.xupan.server.center.service.CenterAgentService;
import com.xupan.server.center.service.CenterSettlementService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.game.domain.BallResult;
import com.xupan.server.web.BusinessException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Center order rules remain covered through the in-process service; retired token HTTP routes stay closed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CenterOrderControllerTest {

    private static final String ADMIN = "center-order-admin";
    private static final String PASSWORD = "CenterOrderPassword123";
    private static final String GROUP_CODE = "CENTER_ORDER_GROUP";
    private static final String CENTER_USERNAME = "center_order_account";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CenterUpstreamAccountService accountService;

    @Autowired
    private CenterOrderService centerOrderService;

    @Autowired
    private CenterAgentService centerAgentService;

    @Autowired
    private AgentLedgerService agentLedgerService;

    @Autowired
    private CenterSettlementService centerSettlementService;

    private long adminId;
    private long centerAgentId;
    private long machineId;
    private int betSequence;
    private int ledgerSequence;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        adminId = userRepository.insert(ADMIN, "中心订单管理员",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        adminToken = login(ADMIN, PASSWORD);
        jdbc.update("INSERT INTO agent_group (group_code, display_name, status, created_by) "
                + "VALUES (?, '中心订单测试组', 'ACTIVE', ?)", GROUP_CODE, adminId);
        long groupId = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, GROUP_CODE);
        centerAgentId = accountService.createAccount(groupId, CENTER_USERNAME, PASSWORD,
                new BigDecimal("10000.00"), null, adminId).account().accountId();
        jdbc.update("INSERT INTO agent (agent_code, display_name, group_id, account_user_id, system_owned, status) "
                        + "VALUES ('CENTER_ORDER_MACHINE', '中心订单机器', ?, ?, FALSE, 'ACTIVE')",
                groupId, adminId);
        machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = 'CENTER_ORDER_MACHINE'", Long.class);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void acceptsOrderAndFreezesCenterCredit() throws Exception {
        CenterOrderService.Accepted accepted = accept("ORD-1001", "FAN", "3.850", "100", "39999001");

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.duplicate()).isFalse();
        assertThat(accepted.frozenAmount()).isEqualByComparingTo("285.00");
        mockMvc.perform(get("/api/admin/center/agents").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + centerAgentId + ")].availableBalance")
                        .value(org.hamcrest.Matchers.contains(9715.0)))
                .andExpect(jsonPath("$[?(@.id == " + centerAgentId + ")].frozenBalance")
                        .value(org.hamcrest.Matchers.contains(285.0)))
                .andExpect(jsonPath("$[?(@.id == " + centerAgentId + ")].upstreamUnsettled")
                        .value(org.hamcrest.Matchers.contains(0.0)));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'
                """, Long.class, centerAgentId)).isEqualTo(2L);
        assertThat(countOrders("ACCEPTED")).isEqualTo(1L);
    }

    @Test
    void duplicateReportIsIdempotentAndDoesNotFreezeTwice() {
        CenterOrderService.Accepted first = accept("ORD-1002", "FAN", "3.850", "100", "39999002");
        CenterOrderService.Accepted replay = accept("ORD-1002", "FAN", "3.850", "100", "39999002");

        assertThat(first.duplicate()).isFalse();
        assertThat(replay.status()).isEqualTo("ACCEPTED");
        assertThat(replay.duplicate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE agent_order_no = ?",
                Long.class, "ORD-1002")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'
                """, Long.class, centerAgentId)).isEqualTo(2L);
    }

    @Test
    void rejectsInvalidOrdersWithReasonAndDoesNotFreezeCredit() {
        assertThat(accept("ORD-2001", "FAN", "3.850", "100", "ABC").rejectCode())
                .isEqualTo("CENTER_ISSUE_INVALID");
        assertThat(accept("ORD-2002", "NOT_A_PLAY", "3.850", "100", "39999003").rejectCode())
                .isEqualTo("CENTER_PLAY_TYPE_INVALID");
        assertThat(accept("ORD-2003", "FAN", "3.850", "200000", "39999006").rejectCode())
                .isEqualTo("CENTER_AMOUNT_LIMIT_EXCEEDED");
        assertThat(countOrders("REJECTED")).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger "
                + "WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'", Long.class, centerAgentId)).isZero();
    }

    @Test
    void rejectsWhenAvailableCreditIsInsufficient() {
        jdbc.update("UPDATE center_agent SET available_balance = 50.00 WHERE id = ?", centerAgentId);

        CenterOrderService.Accepted rejected = accept("ORD-3001", "FAN", "3.850", "100", "39999007");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectCode()).isEqualTo("CENTER_RISK_CAPACITY_INSUFFICIENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger "
                + "WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'", Long.class, centerAgentId)).isZero();
    }

    @Test
    void retiredTokenRoutesAndOldAdminCreationEndpointAreDenied() throws Exception {
        String authorization = bearer(adminToken);
        mockMvc.perform(post("/api/center/orders").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
        mockMvc.perform(get("/api/center/orders").header("Authorization", authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
        mockMvc.perform(get("/api/center/me").header("Authorization", authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
        mockMvc.perform(post("/api/admin/center/agents").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
    }

    @Test
    void adminCanReadOrdersAndSummaryAfterServiceAcceptance() throws Exception {
        accept("ORD-6001", "FAN", "3.850", "100", "39999012");

        mockMvc.perform(get("/api/admin/center/agents/orders").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/admin/center/agents/orders/summary")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.status == 'ACCEPTED')].total")
                        .value(org.hamcrest.Matchers.contains(1)));
        mockMvc.perform(get("/api/admin/center/agents/orders").param("pageSize", "1000")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_ORDER_QUERY_INVALID"));
    }

    @Test
    void acceptsOrderWithoutOptionalPlayText() {
        long betId = insertBet("ORD-7001", "FAN", "1", "3.85", "100", "39999020");
        CenterOrderService.Accepted accepted = centerOrderService.accept(centerAgentId, "ORD-7001", betId,
                machineId, "K28", "000000", "NOT_A_PLAY", null,
                new BigDecimal("99.99"), new BigDecimal("999"));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_order "
                        + "WHERE agent_order_no = 'ORD-7001' AND play_text = 'params=1' "
                        + "AND game_code = 'AU8' AND issue_number = '39999020' "
                        + "AND amount = 100.00 AND odds = 3.8500", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void calculatesMaximumNetRiskFromPersistedBetSnapshot() {
        long betId = insertBet("ORD-RISK-95", "FAN", "1", "1.950", "100", "39999030");

        CenterOrderService.Accepted accepted = centerOrderService.accept(centerAgentId, "ORD-RISK-95", betId,
                machineId, "K28", "000000", "FAN", "1", new BigDecimal("9.999"),
                new BigDecimal("999"));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.frozenAmount()).isEqualByComparingTo("95.00");
        assertThat(jdbc.queryForObject("SELECT risk_amount FROM center_order WHERE id = ?",
                BigDecimal.class, accepted.orderId())).isEqualByComparingTo("95.00");
        assertThat(jdbc.queryForObject("SELECT ball_number FROM center_order WHERE id = ?",
                Integer.class, accepted.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM center_order WHERE id = ?",
                BigDecimal.class, accepted.orderId())).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT odds FROM center_order WHERE id = ?",
                BigDecimal.class, accepted.orderId())).isEqualByComparingTo("1.9500");
        assertThat(jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("9905.00");
    }

    @Test
    void centerRiskIsReservedBeforeMachineSupplementAndReportReplayDoesNotReserveTwice() {
        setCenterAvailable("20.00");
        setMachineScore("100.00");
        long betId = insertBet("ORD-RISK-SPLIT", "FAN", "1", "1.950", "100", "39999031");

        CenterOrderService.Accepted first = centerOrderService.accept(centerAgentId, "ORD-RISK-SPLIT", betId,
                machineId, "K28", "000000", "FAN", null, BigDecimal.ONE, BigDecimal.ONE);
        CenterOrderService.Accepted replay = centerOrderService.accept(centerAgentId, "ORD-RISK-SPLIT", betId,
                machineId, "K28", "000000", "FAN", null, BigDecimal.ONE, BigDecimal.ONE);

        assertThat(first.status()).isEqualTo("ACCEPTED");
        assertThat(first.frozenAmount()).isEqualByComparingTo("20.00");
        assertThat(replay.duplicate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT machine_funded_amount FROM center_order WHERE id = ?",
                BigDecimal.class, first.orderId())).isEqualByComparingTo("75.00");
        assertThat(machineScore()).isEqualByComparingTo("25.00");
        assertThat(countMachineRiskEntries("CENTER_RISK_RESERVE", first.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger "
                + "WHERE center_agent_id = ? AND reference_id = ? AND entry_type = 'BET_FREEZE'",
                Long.class, centerAgentId, Long.toString(first.orderId()))).isEqualTo(2L);
    }

    @Test
    void refundReleasesMachineSupplementExactlyOnce() {
        setCenterAvailable("20.00");
        setMachineScore("100.00");
        CenterOrderService.Accepted accepted = accept("ORD-RISK-REFUND", "FAN", "1.950", "100",
                "39999037");

        CenterOrderService.DispositionResult first = centerOrderService.dispose(accepted.orderId(), "REFUND",
                "测试退回机器补足", "center-risk-refund-once", adminId);
        CenterOrderService.DispositionResult replay = centerOrderService.dispose(accepted.orderId(), "REFUND",
                "重复请求", "center-risk-refund-once", adminId);

        assertThat(first.status()).isEqualTo("REFUNDED");
        assertThat(replay.duplicate()).isTrue();
        assertThat(machineScore()).isEqualByComparingTo("100.00");
        assertThat(countMachineRiskEntries("CENTER_RISK_RELEASE", accepted.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsWhenCenterAndReportingMachineCannotCoverMaximumRisk() {
        setCenterAvailable("20.00");
        setMachineScore("74.99");

        CenterOrderService.Accepted rejected = accept("ORD-RISK-SHORT", "FAN", "1.950", "100", "39999032");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectCode()).isEqualTo("CENTER_RISK_CAPACITY_INSUFFICIENT");
        assertThat(jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("0.00");
        assertThat(machineScore()).isEqualByComparingTo("74.99");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ? "
                + "AND entry_type = 'CENTER_RISK_RESERVE'", Long.class, machineId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ? "
                + "AND entry_type = 'BET_FREEZE'", Long.class, centerAgentId)).isZero();
    }

    @Test
    void winConsumesReservedRiskAndRepeatedSettlementDoesNotWriteAdditionalLedgerEntries() {
        setCenterAvailable("20.00");
        setMachineScore("100.00");
        ensureConfirmedIssue("39999033", 1);
        CenterOrderService.Accepted accepted = accept("ORD-RISK-SETTLE-WIN", "FAN", "1.950", "100",
                "39999033");
        List<BallResult> results = ballResults(1);

        CenterSettlementService.SettlementSummary first = centerSettlementService.settleIssue(
                "AU8", "39999033", results, "1,1,1,1,1,1,1,1", 100);
        long centerLedgerAfterFirst = countCenterOrderLedger(accepted.orderId());
        long machineLedgerAfterFirst = countMachineOrderLedger(accepted.orderId());
        CenterSettlementService.SettlementSummary replay = centerSettlementService.settleIssue(
                "AU8", "39999033", results, "1,1,1,1,1,1,1,1", 100);

        assertThat(first.settledCount()).isEqualTo(1);
        assertThat(first.orders().getFirst().settleResult()).isEqualTo("AGENT_WIN");
        assertThat(first.orders().getFirst().settleAmount()).isEqualByComparingTo("95.00");
        assertThat(replay.settledCount()).isZero();
        assertThat(countCenterOrderLedger(accepted.orderId())).isEqualTo(centerLedgerAfterFirst);
        assertThat(countMachineOrderLedger(accepted.orderId())).isEqualTo(machineLedgerAfterFirst);
        assertThat(jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT upstream_unsettled FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("0.00");
        assertThat(machineScore()).isEqualByComparingTo("25.00");
    }

    @Test
    void losingBetCreditsStakeToCenterAndReleasesItsRiskReservation() {
        ensureConfirmedIssue("39999034", 2);
        CenterOrderService.Accepted accepted = accept("ORD-RISK-SETTLE-LOSE", "FAN", "1.950", "100",
                "39999034");

        CenterSettlementService.SettlementSummary summary = centerSettlementService.settleIssue(
                "AU8", "39999034", ballResults(2), "2,2,2,2,2,2,2,2", 100);

        assertThat(summary.settledCount()).isEqualTo(1);
        assertThat(summary.orders().getFirst().settleResult()).isEqualTo("AGENT_LOSE");
        assertThat(summary.orders().getFirst().settleAmount()).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("10100.00");
        assertThat(jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId)).isEqualByComparingTo("0.00");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
    }

    @Test
    void settlementSelectsTheResultByEachOrdersStoredBallNumber() {
        ensureConfirmedIssue("39999038", List.of(1, 2, 3, 4, 5, 6, 7, 8));
        long firstBetId = insertBet("ORD-RISK-BALL-1", "FAN", "1", "1.950", "100", "39999038", 1);
        long secondBetId = insertBet("ORD-RISK-BALL-2", "FAN", "2", "1.950", "100", "39999038", 2);
        CenterOrderService.Accepted firstOrder = centerOrderService.accept(centerAgentId, "ORD-RISK-BALL-1",
                firstBetId, machineId, "ignored", "ignored", "FAN", "ignored", BigDecimal.ONE, BigDecimal.ONE);
        CenterOrderService.Accepted secondOrder = centerOrderService.accept(centerAgentId, "ORD-RISK-BALL-2",
                secondBetId, machineId, "ignored", "ignored", "FAN", "ignored", BigDecimal.ONE, BigDecimal.ONE);
        List<BallResult> results = List.of(BallResult.fromNumber(1), BallResult.fromNumber(2),
                BallResult.fromNumber(3), BallResult.fromNumber(4), BallResult.fromNumber(5),
                BallResult.fromNumber(6), BallResult.fromNumber(7), BallResult.fromNumber(8));

        CenterSettlementService.SettlementSummary summary = centerSettlementService.settleIssue(
                "AU8", "39999038", results, "1,2,3,4,5,6,7,8", 100);

        assertThat(summary.settledCount()).isEqualTo(2);
        assertThat(summary.orders()).extracting(CenterSettlementService.SettledOrder::settleResult)
                .containsOnly("AGENT_WIN");
        assertThat(summary.orders()).extracting(CenterSettlementService.SettledOrder::settleAmount)
                .containsExactly(new BigDecimal("95.00"), new BigDecimal("95.00"));
        assertThat(firstOrder.status()).isEqualTo("ACCEPTED");
        assertThat(secondOrder.status()).isEqualTo("ACCEPTED");
    }

    @Test
    void rejectsSettlementNumbersThatDifferFromTheLockedDraw() {
        ensureConfirmedIssue("39999035", 1);
        accept("ORD-RISK-SETTLE-MISMATCH", "FAN", "1.950", "100", "39999035");

        assertThatThrownBy(() -> centerSettlementService.settleIssue(
                "AU8", "39999035", ballResults(2), "2,2,2,2,2,2,2,2", 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CENTER_DRAW_RESULT_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT status FROM center_order WHERE agent_order_no = ?",
                String.class, "ORD-RISK-SETTLE-MISMATCH")).isEqualTo("ACCEPTED");
    }

    @Test
    void concurrentOrdersCannotReserveTheSameCenterRiskTwice() throws Exception {
        setCenterAvailable("95.00");
        long firstBetId = insertBet("ORD-RISK-CONCURRENT-1", "FAN", "1", "1.950", "100", "39999035");
        long secondBetId = insertBet("ORD-RISK-CONCURRENT-2", "FAN", "1", "1.950", "100", "39999035");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<CenterOrderService.Accepted>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> concurrentAccept("ORD-RISK-CONCURRENT-1", firstBetId, ready, start)));
            futures.add(executor.submit(() -> concurrentAccept("ORD-RISK-CONCURRENT-2", secondBetId, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CenterOrderService.Accepted> results = List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(CenterOrderService.Accepted::status)
                    .containsExactlyInAnyOrder("ACCEPTED", "REJECTED");
            assertThat(jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                    BigDecimal.class, centerAgentId)).isEqualByComparingTo("0.00");
            assertThat(jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                    BigDecimal.class, centerAgentId)).isEqualByComparingTo("95.00");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE center_agent_id = ? "
                    + "AND status = 'ACCEPTED'", Long.class, centerAgentId)).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private CenterOrderService.Accepted concurrentAccept(String orderNo, long betId, CountDownLatch ready,
                                                          CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent order test start timed out");
        }
        return centerOrderService.accept(centerAgentId, orderNo, betId, machineId,
                "ignored", "ignored", "FAN", "ignored", BigDecimal.ONE, BigDecimal.ONE);
    }

    private void setCenterAvailable(String target) {
        BigDecimal current = jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
        BigDecimal desired = new BigDecimal(target);
        BigDecimal delta = desired.subtract(current);
        if (delta.signum() == 0) {
            return;
        }
        centerAgentService.moveInternal(centerAgentId, CenterAgentService.BUCKET_AVAILABLE,
                CenterAgentService.TYPE_CREDIT_ADJUST, delta.signum() > 0 ? "IN" : "OUT", delta.abs(),
                "中心订单测试额度准备", "TEST", GROUP_CODE,
                "CENTER-ORDER-TEST-AVAILABLE-" + (++ledgerSequence), adminId);
    }

    private void setMachineScore(String target) {
        agentLedgerService.setMachineScore(machineId, new BigDecimal(target), AgentLedgerService.TYPE_MACHINE_ADJUST,
                "中心风险测试机器积分准备", adminId, "CENTER-ORDER-TEST-MACHINE-" + (++ledgerSequence));
    }

    private BigDecimal machineScore() {
        return jdbc.queryForObject("SELECT score FROM agent WHERE id = ?", BigDecimal.class, machineId);
    }

    private long countMachineRiskEntries(String type, long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ? "
                + "AND entry_type = ? AND reference_id = ?", Long.class, machineId, type, Long.toString(orderId));
    }

    private long countMachineOrderLedger(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ? "
                + "AND reference_id = ?", Long.class, machineId, Long.toString(orderId));
    }

    private long countCenterOrderLedger(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ? "
                + "AND reference_id = ?", Long.class, centerAgentId, Long.toString(orderId));
    }

    private static List<BallResult> ballResults(int winningNumber) {
        List<BallResult> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(BallResult.fromNumber(winningNumber));
        }
        return List.copyOf(results);
    }

    private void ensureConfirmedIssue(String issueNumber, int ballNumber) {
        ensureConfirmedIssue(issueNumber, List.of(ballNumber, ballNumber, ballNumber, ballNumber,
                ballNumber, ballNumber, ballNumber, ballNumber));
    }

    private void ensureConfirmedIssue(String issueNumber, List<Integer> numbers) {
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, issue_started_at, opened_at, closed_at,
                     settled_at, draw_result_version, draw_confirmed_at, draw_confirmed_by)
                VALUES ('AU8', ?, 'CLOSED', 'SETTLED', ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, ?)
                """, issueNumber, numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3),
                numbers.get(4), numbers.get(5), numbers.get(6), numbers.get(7), adminId);
    }

    private CenterOrderService.Accepted accept(String orderNo, String playType, String odds,
                                               String amount, String issueNumber) {
        long betId = insertBet(orderNo, playType, "1", odds, amount, issueNumber);
        return centerOrderService.accept(centerAgentId, orderNo, betId, machineId, "K28", issueNumber,
                playType, "browser params", new BigDecimal(odds), new BigDecimal(amount));
    }

    private long insertBet(String orderNo, String playType, String parameters, String odds,
                           String amount, String issueNumber) {
        return insertBet(orderNo, playType, parameters, odds, amount, issueNumber, 1);
    }

    private long insertBet(String orderNo, String playType, String parameters, String odds,
                           String amount, String issueNumber, int ballNumber) {
        String betCode = "CENTER_ORDER_BET_" + (++betSequence);
        jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, machine_id_snapshot, agent_id_snapshot)
                VALUES ('AU8', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, betCode, betCode, issueNumber, ballNumber, playType, parameters, new BigDecimal(amount),
                new BigDecimal(odds), machineId, machineId);
        return jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
    }

    private long countOrders(String state) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE center_agent_id = ? AND status = ?",
                Long.class, centerAgentId, state);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?))", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_risk_hit WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_machine_session WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_USERNAME);
        jdbc.update("DELETE FROM game_bet WHERE machine_id_snapshot IN "
                + "(SELECT id FROM agent WHERE agent_code = 'CENTER_ORDER_MACHINE')");
        jdbc.update("DELETE FROM chat_robot_dispatch WHERE game_event_id IN "
                + "(SELECT id FROM game_issue_event WHERE issue_number IN "
                + "('39999033','39999034','39999035','39999038'))");
        jdbc.update("DELETE FROM game_issue_event WHERE issue_number IN "
                + "('39999033','39999034','39999035','39999038')");
        jdbc.update("DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number IN "
                + "('39999033','39999034','39999035','39999038')");
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = 'CENTER_ORDER_MACHINE')");
        jdbc.update("DELETE FROM agent WHERE agent_code = 'CENTER_ORDER_MACHINE'");
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", ADMIN);
    }
}
