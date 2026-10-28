package com.xupan.server.center.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-C2 / C-3：中心侧订单处置（超时确认、失败确认、退款、撤单）。
 *
 * <p>验证四件事：</p>
 * <ol>
 *   <li>「超时不算失败」（需求 §12.3）：超时单必须能被人为查证后落到明确终态；</li>
 *   <li>额度回流走账本：冻结 → 可用的两层流水都写，账本自检始终为 0 不一致；</li>
 *   <li>幂等：同一幂等键重复提交不二次改状态、不二次退额度；</li>
 *   <li>状态守卫：已结算的单不能被退款，未受理的单不能被撤单。</li>
 * </ol>
 */
@SpringBootTest(properties = "xupan.automation.enabled=false")
@ActiveProfiles("test")
@DisplayName("中心订单处置（P1-C2 / C-3）")
class CenterOrderDispositionTest {

    private static final String AGENT_CODE = "DISPOSITION_AGENT";
    private static final String GROUP_CODE = "DISPOSITION_GROUP";
    private static final String MACHINE_CODE = "DISPOSITION_MACHINE";
    private static final String MACHINE_USER = "disposition_machine_user";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CenterOrderService orderService;
    @Autowired
    private CenterAgentService agentService;
    @Autowired
    private CenterAgentRepository agentRepository;
    @Autowired
    private UserRepository userRepository;

    private long centerAgentId;
    private long machineId;
    private long groupId;
    private int betSequence;

    @BeforeEach
    void setUp() {
        clean();
        long userId = userRepository.insert(MACHINE_USER, "处置测试机器用户", "test-hash", "ACTIVE");
        jdbc.update("INSERT INTO agent_group (group_code, display_name, status) VALUES (?, '处置测试组', 'ACTIVE')",
                GROUP_CODE);
        groupId = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, GROUP_CODE);
        jdbc.update("INSERT INTO agent (agent_code, display_name, group_id, account_user_id, system_owned, status) "
                + "VALUES (?, '处置测试机器', ?, ?, FALSE, 'ACTIVE')", MACHINE_CODE, groupId, userId);
        machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);
        jdbc.update("""
                INSERT INTO center_agent (agent_code, agent_name, upstream_account, token_hash, token_prefix,
                                          status, credit_limit, available_balance, agent_group_id)
                VALUES (?, '处置测试代理', ?, ?, 'disppfx', 'ACTIVE', 100000.00, 100000.00, ?)
                """, AGENT_CODE, "disposition_upstream", "b".repeat(64), groupId);
        centerAgentId = jdbc.queryForObject(
                "SELECT id FROM center_agent WHERE agent_code = ?", Long.class, AGENT_CODE);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("超时单可确认受理：转 ACCEPTED 并占用冻结额度")
    void confirmAcceptedMovesTimeoutToAccepted() {
        long orderId = insertOrder("DISP-ACC-1", "TIMEOUT", new BigDecimal("100.00"),
                new BigDecimal("100.00"), BigDecimal.ZERO);

        CenterOrderService.DispositionResult result =
                orderService.dispose(orderId, "CONFIRM_ACCEPTED", "查证上游已受理", "disp-key-acc-1", null);

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.action()).isEqualTo("CONFIRM_ACCEPTED");
        assertThat(result.duplicate()).isFalse();

        CenterAgentRepository.AgentRow agent = agentRepository.findById(centerAgentId).orElseThrow();
        assertThat(agent.availableBalance()).isEqualByComparingTo("99715.00");
        assertThat(agent.frozenBalance()).isEqualByComparingTo("285.00");
        assertThat(agentRepository.countInconsistentBuckets(centerAgentId)).isZero();
        assertThat(orderService.dispositionHistory(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("超时单可确认失败：转 REJECTED 且不产生额度变动（未占用过）")
    void confirmFailedMovesTimeoutToRejectedWithoutLedgerChange() {
        long orderId = insertOrder("DISP-FAIL-1", "TIMEOUT", new BigDecimal("100.00"),
                new BigDecimal("100.00"), BigDecimal.ZERO);

        CenterOrderService.DispositionResult result =
                orderService.dispose(orderId, "CONFIRM_FAILED", "查证上游未收到", "disp-key-fail-1", null);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.frozenAmount()).isEqualByComparingTo("0.00");

        CenterAgentRepository.AgentRow agent = agentRepository.findById(centerAgentId).orElseThrow();
        assertThat(agent.availableBalance()).isEqualByComparingTo("100000.00");
        assertThat(agent.frozenBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("历史订单从完整投注快照重算风险后才能确认受理")
    void legacyRiskIsRecomputedFromPersistedOrderSnapshot() {
        long orderId = insertOrder("DISP-RISK-UNKNOWN-1", "TIMEOUT", new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        jdbc.update("UPDATE center_order SET risk_policy_version = 0, risk_amount = 0 WHERE id = ?", orderId);

        CenterOrderService.DispositionResult result = orderService.dispose(orderId, "CONFIRM_ACCEPTED",
                "查证上游已受理", "disp-key-risk-unknown-1", null);

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT risk_amount FROM center_order WHERE id = ?",
                BigDecimal.class, orderId)).isEqualByComparingTo("285.00");
        assertThat(jdbc.queryForObject("SELECT risk_policy_version FROM center_order WHERE id = ?",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("无法计算风险的历史订单拒绝按零风险确认受理")
    void uncomputableLegacyRiskCannotBeConfirmedAccepted() {
        long orderId = insertOrder("DISP-RISK-INVALID-1", "TIMEOUT", new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        jdbc.update("UPDATE center_order SET risk_policy_version = 0, risk_amount = 0, "
                + "play_type = 'UNSUPPORTED' WHERE id = ?", orderId);

        assertThatThrownBy(() -> orderService.dispose(orderId, "CONFIRM_ACCEPTED", "查证上游已受理",
                "disp-key-risk-invalid-1", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_RISK_SNAPSHOT_REQUIRED"));

        assertThat(orderStatus(orderId)).isEqualTo("TIMEOUT");
        assertThat(agentRepository.findById(centerAgentId).orElseThrow().frozenBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("已受理单可退款：冻结退回可用，账本保持一致")
    void refundReleasesFrozenBackToAvailable() {
        long orderId = insertOrder("DISP-REFUND-1", "ACCEPTED", new BigDecimal("200.00"),
                new BigDecimal("200.00"), new BigDecimal("200.00"));

        CenterOrderService.DispositionResult result =
                orderService.dispose(orderId, "REFUND", "上游取消该期", "disp-key-refund-1", null);

        assertThat(result.status()).isEqualTo("REFUNDED");
        assertThat(result.frozenAmount()).isEqualByComparingTo("570.00");

        CenterAgentRepository.AgentRow agent = agentRepository.findById(centerAgentId).orElseThrow();
        // 先看账本验算：不一致说明"写流水"和"写余额"脱节。
        long inconsistent = agentRepository.countInconsistentBuckets(centerAgentId);
        BigDecimal storedFrozen = jdbc.queryForObject(
                "SELECT frozen_balance FROM center_agent WHERE id = ?", BigDecimal.class, centerAgentId);
        assertThat(inconsistent)
                .as("账本与额度必须一致；storedFrozen=%s", storedFrozen)
                .isZero();
        assertThat(agent.frozenBalance()).isEqualByComparingTo("0.00");
        assertThat(agent.availableBalance()).isEqualByComparingTo("100000.00");
        assertThat(agentRepository.countInconsistentBuckets(centerAgentId)).isZero();
    }

    @Test
    @DisplayName("已受理单可撤单：落 CANCELLED 并退回冻结")
    void cancelReleasesFrozenAndMarksCancelled() {
        long orderId = insertOrder("DISP-CANCEL-1", "ACCEPTED", new BigDecimal("150.00"),
                new BigDecimal("150.00"), new BigDecimal("150.00"));

        CenterOrderService.DispositionResult result =
                orderService.dispose(orderId, "CANCEL", "该期流局", "disp-key-cancel-1", null);

        assertThat(result.status()).isEqualTo("CANCELLED");
        CenterAgentRepository.AgentRow agent = agentRepository.findById(centerAgentId).orElseThrow();
        assertThat(agent.frozenBalance()).isEqualByComparingTo("0.00");
        assertThat(agentRepository.countInconsistentBuckets(centerAgentId)).isZero();
    }

    @Test
    @DisplayName("重复提交同一幂等键：回放首次结果，不二次改状态、不二次退额度")
    void dispositionIsIdempotent() {
        long orderId = insertOrder("DISP-IDEM-1", "ACCEPTED", new BigDecimal("300.00"),
                new BigDecimal("300.00"), new BigDecimal("300.00"));

        CenterOrderService.DispositionResult first =
                orderService.dispose(orderId, "REFUND", "首次退款", "disp-key-idem-1", null);
        CenterOrderService.DispositionResult second =
                orderService.dispose(orderId, "REFUND", "重复提交", "disp-key-idem-1", null);

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.status()).isEqualTo("REFUNDED");

        CenterAgentRepository.AgentRow agent = agentRepository.findById(centerAgentId).orElseThrow();
        assertThat(agent.availableBalance())
                .as("额度只应退回一次")
                .isEqualByComparingTo("100000.00");
        assertThat(orderService.dispositionHistory(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("处置幂等键不能复用于另一笔中心订单")
    void dispositionIdempotencyKeyCannotBeReusedForAnotherOrder() {
        long firstOrderId = insertOrder("DISP-IDEM-ORDER-A", "ACCEPTED", new BigDecimal("100.00"),
                new BigDecimal("100.00"), BigDecimal.ZERO);
        long secondOrderId = insertOrder("DISP-IDEM-ORDER-B", "ACCEPTED", new BigDecimal("100.00"),
                new BigDecimal("100.00"), BigDecimal.ZERO);
        orderService.dispose(firstOrderId, "REFUND", "首次退款", "disp-key-shared-1", null);

        assertThatThrownBy(() -> orderService.dispose(secondOrderId, "CANCEL", "另一个订单",
                "disp-key-shared-1", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_DISPOSITION_KEY_CONFLICT"));
        assertThat(orderStatus(secondOrderId)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("状态守卫：已结算的订单不能被退款")
    void settledOrderCannotBeRefunded() {
        BigDecimal frozen = new BigDecimal("100.00");
        long orderId = insertOrder("DISP-SETTLED-1", "SETTLED", frozen, frozen, frozen);

        assertThatThrownBy(() -> orderService.dispose(orderId, "REFUND", "误操作", "disp-key-settled-1", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_DISPOSITION_STATUS_INVALID"));
    }

    @Test
    @DisplayName("状态守卫：已拒绝的订单不能被确认受理")
    void rejectedOrderCannotBeConfirmedAccepted() {
        long orderId = insertOrder("DISP-REJECTED-1", "REJECTED", new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertThatThrownBy(() -> orderService.dispose(orderId, "CONFIRM_ACCEPTED", "误操作",
                "disp-key-rejected-1", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_DISPOSITION_STATUS_INVALID"));
    }

    @Test
    @DisplayName("非法动作与缺失幂等键被拒绝")
    void invalidActionAndMissingKeyAreRejected() {
        long orderId = insertOrder("DISP-INVALID-1", "TIMEOUT", new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertThatThrownBy(() -> orderService.dispose(orderId, "WHATEVER", null, "disp-key-x-1", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_DISPOSITION_ACTION_INVALID"));

        assertThatThrownBy(() -> orderService.dispose(orderId, "REFUND", null, "  ", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_DISPOSITION_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("待处置列表包含 TIMEOUT 与 PENDING")
    void pendingDispositionsListsUnresolvedOrders() {
        insertOrder("DISP-PENDING-1", "TIMEOUT", new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        insertOrder("DISP-PENDING-2", "PENDING", new BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        List<CenterOrderRepository.OrderRow> pending = orderService.pendingDispositions(50);

        assertThat(pending).extracting(CenterOrderRepository.OrderRow::status)
                .contains("TIMEOUT", "PENDING");
    }

    /**
     * 构造一张处于指定状态的订单。
     *
     * <p>{@code ACCEPTED} 通过**真实接单流程**产生（{@link CenterOrderService#accept}），
     * 这样"可用 → 冻结"的额度与账本事实由生产代码自己写，测试只负责断言；
     * 手工 UPDATE 余额会绕过账本服务，导致账实不符。</p>
     */
    private long insertOrder(String orderNo, String status, BigDecimal amount, BigDecimal frozen,
                             BigDecimal settledAmount) {
        long betId = insertBet(orderNo, amount);
        if ("ACCEPTED".equals(status)) {
            CenterOrderService.Accepted accepted = orderService.accept(centerAgentId, orderNo, betId, machineId,
                    "AU8", "39999401", "FAN", "1", new BigDecimal("3.850"), amount);
            assertThat(accepted.status()).as("接单应成功，拒绝原因=%s", accepted.rejectCode())
                    .isEqualTo("ACCEPTED");
            return accepted.orderId();
        }
        jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, local_bet_id, local_agent_id, game_code, issue_number,
                     ball_number, play_type, play_text, odds, amount, risk_amount, risk_policy_version,
                     status, frozen_amount, settle_amount)
                VALUES (?, ?, ?, ?, 'AU8', '39999401', 1, 'FAN', 'params=1', 3.850, ?, ?, 1, ?, ?, ?)
                """, centerAgentId, orderNo, betId, machineId, amount,
                amount.multiply(new BigDecimal("2.85")).setScale(2), status, frozen,
                "SETTLED".equals(status) ? settledAmount : null);
        return jdbc.queryForObject("SELECT id FROM center_order WHERE agent_order_no = ?",
                Long.class, orderNo);
    }

    private long insertBet(String orderNo, BigDecimal amount) {
        String betCode = "DISPOSITION_BET_" + (++betSequence);
        jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, machine_id_snapshot, agent_id_snapshot)
                VALUES ('AU8', NULL, ?, ?, '39999401', 1, 'FAN', '1', ?, 3.850, ?, ?)
                """, betCode, betCode, amount, machineId, machineId);
        return jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM center_order WHERE id = ?", String.class, orderId);
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?))", AGENT_CODE);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", AGENT_CODE);
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", AGENT_CODE);
        jdbc.update("DELETE FROM game_bet WHERE machine_id_snapshot IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", AGENT_CODE);
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", MACHINE_USER);
    }
}
