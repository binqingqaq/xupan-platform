package com.xupan.server.report.service;

import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.center.service.CenterOrderService;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中心拒绝 → 本地失败退单 的端到端联动（《代理报网盘口与结算业务规则说明》§12.4）。
 *
 * <p>这是阶段 B 里最容易被漏掉的一环：中心侧"拒绝"本身很容易实现，
 * 但代理侧必须真的把玩家的钱退回去、把注单撤掉，否则一笔被中心拒收的订单
 * 会一直挂在玩家身上（钱扣了、单还在、还没上报成功）。</p>
 *
 * <p>链路：机器登录中心账号 → 中心拒绝 → 注单保留并置为 CANCELED，
 * 上报尝试置为 REFUNDED，玩家收到幂等退款。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CenterRejectWithdrawIntegrationTest {

    private static final String GROUP_CODE = "REJECT_CENTER_GROUP";
    private static final String CENTER_USERNAME = "reject_center_up_a";
    private static final String MACHINE_CODE = "REJECT_MACHINE_A";
    private static final String BET_CODE = "REJECT-BET-1";

    @Autowired
    private ReportService reportService;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private CenterUpstreamAccountService centerAccountService;

    @Autowired
    private CenterOrderService centerOrderService;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.xupan.server.auth.repository.UserRepository userRepository;

    private long centerAgentId;
    private long machineId;
    private long accountId;
    private long betId;
    private long playerUserId;
    private long operatorId;
    private long groupId;
    private long machineUserId;

    @BeforeEach
    void setUp() {
        clean();
        operatorId = userRepository.insert("reject-center-admin", "退单联动管理员",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("RejectPass123"),
                "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");

        groupId = agentAdminService.createGroup(GROUP_CODE, "退单联动子账号", operatorId).id();
        var machine = agentAdminService.createAgent(MACHINE_CODE, "reject-machine-account", "退单联动机器",
                "RejectPass123", groupId, operatorId);
        machineId = machine.id();
        machineUserId = machine.accountUserId();
        centerAgentId = centerAccountService.createAccount(groupId, CENTER_USERNAME, "RejectPass123",
                new BigDecimal("50000.00"), null, operatorId).account().accountId();
        jdbc.update("UPDATE agent SET report_auto = TRUE, report_withdraw = TRUE "
                + "WHERE id = ?", machineId);
        reportService.login(machineUserId, CENTER_USERNAME, "RejectPass123", "127.0.0.1");

        playerUserId = userRepository.insert("reject-player", "退单联动玩家",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("RejectPass123"),
                "ACTIVE");
        jdbc.update("""
                INSERT INTO demo_user_account (sys_user_id, user_code, member_code, display_name, player_kind,
                                               status, agent_id, balance)
                VALUES (?, 'REJECT-PLAYER-1', 'REJECT001', '退单联动玩家', 'NORMAL', 'ACTIVE', ?, 500.00)
                """, playerUserId, machineId);
        accountId = jdbc.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);

        jdbc.update("""
                INSERT INTO game_bet (game_code, user_id, bet_code, request_idempotency_key, issue_number,
                                      ball_number, play_type, parameters_text, stake, odds_snapshot,
                                      settlement_status)
                VALUES ('AU8', ?, ?, ?, '39999401', 1, 'FAN', '1', 100.00, 3.850, 'PENDING')
                """, accountId, BET_CODE, BET_CODE);
        jdbc.update("INSERT INTO game_issue (game_code, issue_number, status, phase) "
                + "VALUES ('AU8', '39999401', 'OPEN', 'BETTING')");
        betId = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, BET_CODE);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /**
     * 中心拒绝（此处用"玩法不在受理范围"制造拒绝）→ 退款且保留取消注单。
     *
     * <p>为了走到中心的校验分支，注单的玩法必须是中心不接受的值；
     * 这正好模拟"代理报了中心不认的玩法"这一真实场景。</p>
     */
    @Test
    void centerRejectionWithdrawsBetAndRefundsPlayer() {
        // 把注单玩法改成中心不接受的取值，让中心在建档阶段就拒绝
        jdbc.update("UPDATE game_bet SET play_type = 'NOT_ACCEPTED_PLAY' WHERE id = ?", betId);
        BigDecimal before = balanceOf();

        Optional<String> result = reportService.submitForBet(betId);

        // 退单路径返回 WITHDRAWN，注单仍保留为 CANCELED 供追溯
        assertThat(result).contains("WITHDRAWN");
        assertThat(betExists()).as("失败退单应保留注单").isTrue();
        assertThat(betSettlementStatus()).isEqualTo("CANCELED");
        assertThat(balanceOf()).as("失败退单应退还本金").isEqualByComparingTo(before.add(new BigDecimal("100.00")));
        // 中心侧留下一条 REJECTED 订单，便于对账与追查原因
        assertThat(centerOrderStatus()).isEqualTo("REJECTED");
        assertThat(centerRejectCode()).isEqualTo("CENTER_PLAY_TYPE_INVALID");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM report_upstream_order WHERE idempotency_key = ?", String.class,
                "REPORT-" + betId + "-1")).isEqualTo("REFUNDED");
        // 退款写了玩家流水（可审计）。幂等键格式是 CANCEL_REFUND:<betId>（下划线），
        // 这里按真实格式断言，避免"以为退了、其实没写流水"。
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger WHERE idempotency_key = ?
                """, Long.class, "CANCEL_REFUND:" + betId)).isEqualTo(1L);
    }

    /** 中心与机器可覆盖风险不足时，明确拒绝同样按开关触发退单。 */
    @Test
    void insufficientCombinedRiskCapacityAlsoWithdraws() {
        // 把中心可用额度压到 0，当前机器积分不足以补足最大净赔付风险。
        jdbc.update("UPDATE center_agent SET available_balance = 0.00 WHERE id = ?", centerAgentId);
        BigDecimal before = balanceOf();

        Optional<String> result = reportService.submitForBet(betId);

        assertThat(result).contains("WITHDRAWN");
        assertThat(betExists()).isTrue();
        assertThat(betSettlementStatus()).isEqualTo("CANCELED");
        assertThat(balanceOf()).isEqualByComparingTo(before.add(new BigDecimal("100.00")));
        assertThat(centerRejectCode()).isEqualTo("CENTER_RISK_CAPACITY_INSUFFICIENT");
    }

    @Test
    void pendingCenterReplayKeepsOriginalAttemptAndWaitsForResolutionWithoutRefund() {
        seedUnresolvedAttempt("PENDING");
        BigDecimal playerBeforeRecovery = balanceOf();
        BigDecimal centerAvailableBeforeRecovery = centerBalance("available_balance");
        BigDecimal centerFrozenBeforeRecovery = centerBalance("frozen_balance");
        BigDecimal machineScoreBeforeRecovery = machineScore();
        long centerLedgerBeforeRecovery = centerLedgerCount();
        long machineLedgerBeforeRecovery = machineLedgerCount();

        reportService.dispatchPending(true, 50);

        assertThat(betSettlementStatus()).isEqualTo("PENDING");
        assertThat(reportStatus()).isEqualTo("PENDING");
        assertThat(reportAttemptCount()).isEqualTo(1);
        assertThat(balanceOf()).isEqualByComparingTo(playerBeforeRecovery);
        assertThat(centerBalance("available_balance")).isEqualByComparingTo(centerAvailableBeforeRecovery);
        assertThat(centerBalance("frozen_balance")).isEqualByComparingTo(centerFrozenBeforeRecovery);
        assertThat(machineScore()).isEqualByComparingTo(machineScoreBeforeRecovery);
        assertThat(centerLedgerCount()).isEqualTo(centerLedgerBeforeRecovery);
        assertThat(machineLedgerCount()).isEqualTo(machineLedgerBeforeRecovery);
        assertThat(centerOrderCount()).isEqualTo(1);

        long orderId = jdbc.queryForObject("SELECT id FROM center_order WHERE center_agent_id = ? AND agent_order_no = ?",
                Long.class, centerAgentId, "BET-" + betId);
        centerOrderService.dispose(orderId, "CONFIRM_ACCEPTED", "核实中心已受理", "TEST-CONFIRM-" + betId,
                operatorId);
        BigDecimal playerBeforeReplay = balanceOf();
        BigDecimal centerAvailableAfterConfirmation = centerBalance("available_balance");
        BigDecimal centerFrozenAfterConfirmation = centerBalance("frozen_balance");
        BigDecimal machineAfterConfirmation = machineScore();
        long centerLedgerAfterConfirmation = centerLedgerCount();
        long machineLedgerAfterConfirmation = machineLedgerCount();
        jdbc.update("UPDATE report_upstream_order SET next_retry_at = CURRENT_TIMESTAMP WHERE bet_id = ?", betId);
        reportService.dispatchPending(true, 50);

        assertThat(reportStatus()).isEqualTo("ACCEPTED");
        assertThat(reportAttemptCount()).isEqualTo(1);
        assertThat(centerOrderCount()).isEqualTo(1);
        assertThat(balanceOf()).isEqualByComparingTo(playerBeforeReplay);
        assertThat(balanceOf()).isEqualByComparingTo(playerBeforeRecovery);
        assertThat(centerBalance("available_balance")).isEqualByComparingTo(centerAvailableAfterConfirmation);
        assertThat(centerBalance("frozen_balance")).isEqualByComparingTo(centerFrozenAfterConfirmation);
        assertThat(machineScore()).isEqualByComparingTo(machineAfterConfirmation);
        assertThat(centerLedgerCount()).isEqualTo(centerLedgerAfterConfirmation);
        assertThat(machineLedgerCount()).isEqualTo(machineLedgerAfterConfirmation);
        assertThat(centerLedgerCount()).isGreaterThan(centerLedgerBeforeRecovery);
    }

    @Test
    void centerTimeoutReplayIsUnknownAndDoesNotTriggerFailureWithdrawal() {
        seedUnresolvedAttempt("TIMEOUT");
        BigDecimal playerBeforeRecovery = balanceOf();
        BigDecimal centerAvailableBeforeRecovery = centerBalance("available_balance");
        BigDecimal centerFrozenBeforeRecovery = centerBalance("frozen_balance");
        long centerLedgerBeforeRecovery = centerLedgerCount();
        long machineLedgerBeforeRecovery = machineLedgerCount();

        reportService.dispatchPending(true, 50);

        assertThat(betExists()).isTrue();
        assertThat(betSettlementStatus()).isEqualTo("PENDING");
        assertThat(reportStatus()).isEqualTo("TIMEOUT");
        assertThat(reportAttemptCount()).isEqualTo(1);
        assertThat(balanceOf()).isEqualByComparingTo(playerBeforeRecovery);
        assertThat(centerBalance("available_balance")).isEqualByComparingTo(centerAvailableBeforeRecovery);
        assertThat(centerBalance("frozen_balance")).isEqualByComparingTo(centerFrozenBeforeRecovery);
        assertThat(centerLedgerCount()).isEqualTo(centerLedgerBeforeRecovery);
        assertThat(machineLedgerCount()).isEqualTo(machineLedgerBeforeRecovery);
        assertThat(centerOrderCount()).isEqualTo(1);
    }

    /**
     * 关闭失败退单开关时：中心拒绝但注单保留，只标记失败等人工/重报。
     *
     * <p>对应 §12.4"失败退单关：保留订单 → 标记失败 → 等待重试"。</p>
     */
    @Test
    void keepsBetWhenWithdrawIsDisabled() {
        jdbc.update("UPDATE agent SET report_withdraw = FALSE WHERE id = ?", machineId);
        jdbc.update("UPDATE game_bet SET play_type = 'NOT_ACCEPTED_PLAY' WHERE id = ?", betId);
        BigDecimal before = balanceOf();

        Optional<String> result = reportService.submitForBet(betId);

        assertThat(result).contains("REJECTED");
        assertThat(betExists()).as("未开启失败退单时应保留注单").isTrue();
        assertThat(betSettlementStatus()).isEqualTo("PENDING");
        assertThat(balanceOf()).as("未开启失败退单时不退款").isEqualByComparingTo(before);
    }

    /**
     * 中心账号停用撤销机器会话 → 订单未发送，不得伪造成拒绝或退单。
     */
    @Test
    void disabledCenterAccountLeavesBetPendingWithoutWithdrawal() {
        centerAccountService.changeStatus(centerAgentId, "DISABLED", "测试停用中心账号", operatorId);
        BigDecimal before = balanceOf();

        Optional<String> result = reportService.submitForBet(betId);

        assertThat(result).isEmpty();
        assertThat(betExists()).isTrue();
        assertThat(betSettlementStatus()).isEqualTo("PENDING");
        assertThat(balanceOf()).isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?",
                Long.class, betId)).isZero();
        assertThat(centerOrderCount()).as("停用后不得在中心建单").isZero();
    }

    private BigDecimal balanceOf() {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId);
    }

    private boolean betExists() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE id = ?", Long.class, betId);
        return count != null && count > 0;
    }

    private String betSettlementStatus() {
        return jdbc.queryForObject("SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId);
    }

    private String centerOrderStatus() {
        return jdbc.queryForObject("SELECT status FROM center_order WHERE agent_order_no = ?",
                String.class, "BET-" + betId);
    }

    private String centerRejectCode() {
        return jdbc.queryForObject("SELECT reject_code FROM center_order WHERE agent_order_no = ?",
                String.class, "BET-" + betId);
    }

    private long centerOrderCount() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_order WHERE center_agent_id = ?", Long.class, centerAgentId);
        return total == null ? 0L : total;
    }

    private void seedUnresolvedAttempt(String status) {
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, local_bet_id, local_agent_id,
                                          game_code, issue_number, ball_number, play_type, play_text, odds,
                                          amount, status, frozen_amount)
                VALUES (?, ?, ?, ?, 'AU8', '39999401', 1, 'FAN', 'params=1', 3.8500, 100.00, ?, 0.00)
                """, centerAgentId, "BET-" + betId, betId, machineId, status);
        jdbc.update("""
                INSERT INTO report_upstream_order (bet_id, agent_id, center_agent_id, attempt_no, status,
                                                   report_type, idempotency_key, response_code, response_message,
                                                   sent_at, next_retry_at)
                VALUES (?, ?, ?, 1, ?, 'CENTER', ?, ?, '测试夹具中的未决中心状态',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, betId, machineId, centerAgentId, status, "REPORT-" + betId + "-1", status);
    }

    private String reportStatus() {
        return jdbc.queryForObject("SELECT status FROM report_upstream_order WHERE bet_id = ?",
                String.class, betId);
    }

    private long reportAttemptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?",
                Long.class, betId);
    }

    private BigDecimal centerBalance(String bucket) {
        if (!"available_balance".equals(bucket) && !"frozen_balance".equals(bucket)) {
            throw new IllegalArgumentException("unsupported center balance bucket");
        }
        return jdbc.queryForObject("SELECT " + bucket + " FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
    }

    private BigDecimal machineScore() {
        return jdbc.queryForObject("SELECT score FROM agent WHERE id = ?", BigDecimal.class, machineId);
    }

    private long centerLedgerCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?",
                Long.class, centerAgentId);
    }

    private long machineLedgerCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ?",
                Long.class, machineId);
    }

    private void clean() {
        jdbc.update("DELETE FROM center_machine_session WHERE machine_agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?))", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        // chat_outbox 没有幂等键列，只能借 chat_message 关联删除（先删 outbox 再删 message，避免外键）
        jdbc.update("""
                DELETE FROM chat_outbox WHERE message_id IN
                 (SELECT id FROM chat_message WHERE idempotency_key LIKE 'CHAT-REPORT-WITHDRAW-%')
                """);
        jdbc.update("DELETE FROM chat_message WHERE idempotency_key LIKE 'CHAT-REPORT-WITHDRAW-%'");
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE member_code = 'REJECT001')");
        jdbc.update("DELETE FROM game_bet WHERE bet_code = ?", BET_CODE);
        jdbc.update("DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number = '39999401'");
        jdbc.update("DELETE FROM demo_user_account WHERE member_code = 'REJECT001'");
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_USERNAME);
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        for (String username : new String[] {"reject-center-admin", "reject-machine-account", "reject-player"}) {
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user WHERE username = ?", username);
        }
    }
}
