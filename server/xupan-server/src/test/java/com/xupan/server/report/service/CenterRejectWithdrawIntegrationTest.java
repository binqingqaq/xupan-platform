package com.xupan.server.report.service;

import com.xupan.server.center.service.CenterAgentService;
import com.xupan.server.game.repository.GameDataRepository;
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
 * <p>链路：{@code report_type=CENTER} → 中心拒绝 → 本地适配器返回 REJECTED
 * → {@code withdrawOnFailure} 退款 + 删单 + 聊天室取消提示。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CenterRejectWithdrawIntegrationTest {

    private static final String CENTER_CODE = "REJECT_CENTER_A";
    private static final String CENTER_UPSTREAM = "reject_center_up_a";
    private static final String MACHINE_CODE = "REJECT_MACHINE_A";
    private static final String BET_CODE = "REJECT-BET-1";

    @Autowired
    private ReportService reportService;

    @Autowired
    private CenterAgentService centerAgentService;

    @Autowired
    private GameDataRepository gameDataRepository;

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

    @BeforeEach
    void setUp() {
        clean();
        operatorId = userRepository.insert("reject-center-admin", "退单联动管理员",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("RejectPass123"),
                "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");

        CenterAgentService.Created created = centerAgentService.create(CENTER_CODE, "退单联动中心代理",
                CENTER_UPSTREAM, new BigDecimal("50000.00"), operatorId);
        centerAgentId = created.agent().id();

        long machineAccountUserId = userRepository.insert("reject-machine-account", "退单联动机器账号",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("RejectPass123"),
                "ACTIVE");
        // 开启自动上报、在线、开启失败退单；绑定中心代理
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, status, system_owned, board_open, report_type,
                                   report_auto, report_online, report_withdraw, report_network_code,
                                   report_route_code, center_agent_id, account_user_id, created_by)
                VALUES (?, '退单联动机器', 'ACTIVE', FALSE, TRUE, 'CENTER', TRUE, TRUE, TRUE, 'K28', 'A', ?, ?, ?)
                """, MACHINE_CODE, centerAgentId, machineAccountUserId, operatorId);
        machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);

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
                INSERT INTO game_bet (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                                      play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES (?, ?, ?, '39999401', 1, 'FAN', '1', 100.00, 3.850, 'PENDING')
                """, accountId, BET_CODE, BET_CODE);
        betId = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, BET_CODE);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /**
     * 中心拒绝（此处用"玩法不在受理范围"制造拒绝）→ 本地退款 + 删单。
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

        // 退单路径返回 WITHDRAWN（注单已被物理删除并退款）
        assertThat(result).contains("WITHDRAWN");
        assertThat(betExists()).as("失败退单应物理删除注单").isFalse();
        assertThat(balanceOf()).as("失败退单应退还本金").isEqualByComparingTo(before.add(new BigDecimal("100.00")));
        // 中心侧留下一条 REJECTED 订单，便于对账与追查原因
        assertThat(centerOrderStatus()).isEqualTo("REJECTED");
        assertThat(centerRejectCode()).isEqualTo("CENTER_PLAY_TYPE_INVALID");
        // 注意：report_upstream_order.bet_id 是 ON DELETE CASCADE，
        // 退单物理删注单会连带删掉本地这条上报尝试，所以这里断言"退单后本地无残留"；
        // 该次拒绝的凭据由中心侧的 REJECTED 订单保留（见上面的断言）。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM report_upstream_order WHERE idempotency_key = ?", Long.class,
                "REPORT-" + betId + "-1")).as("退单后本地上报记录随注单级联删除").isZero();
        // 退款写了玩家流水（可审计）。幂等键格式是 CANCEL_REFUND:<betId>（下划线），
        // 这里按真实格式断言，避免"以为退了、其实没写流水"。
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger WHERE idempotency_key = ?
                """, Long.class, "CANCEL_REFUND:" + betId)).isEqualTo(1L);
    }

    /** 中心可用额度不足时同样触发退单（这是最常见的拒绝原因）。 */
    @Test
    void centerInsufficientCreditAlsoWithdraws() {
        // 把中心可用额度压到 0，让接单因余额不足而拒绝
        jdbc.update("UPDATE center_agent SET available_balance = 0.00 WHERE id = ?", centerAgentId);
        BigDecimal before = balanceOf();

        Optional<String> result = reportService.submitForBet(betId);

        assertThat(result).contains("WITHDRAWN");
        assertThat(betExists()).isFalse();
        assertThat(balanceOf()).isEqualByComparingTo(before.add(new BigDecimal("100.00")));
        assertThat(centerRejectCode()).isEqualTo("CENTER_AVAILABLE_INSUFFICIENT");
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
        assertThat(balanceOf()).as("未开启失败退单时不退款").isEqualByComparingTo(before);
    }

    /**
     * 中心代理被停用 → 按"确认未受理"处理并退单，不得挂成"超时"永不退款。
     *
     * <p>注意 {@code ck_agent_center_binding} 不允许"report_type=CENTER 但不绑定"，
     * 所以"未绑定"这种配置在库里根本不存在；真正可构造的同类场景是"已绑定但代理被停用"。</p>
     */
    @Test
    void disabledCenterAgentWithdrawsInsteadOfHanging() {
        centerAgentService.changeStatus(centerAgentId, "DISABLED", operatorId);
        BigDecimal before = balanceOf();

        Optional<String> result = reportService.submitForBet(betId);

        assertThat(result).contains("WITHDRAWN");
        assertThat(betExists()).isFalse();
        assertThat(balanceOf()).isEqualByComparingTo(before.add(new BigDecimal("100.00")));
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

    private void clean() {
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_CODE);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_CODE);
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
        jdbc.update("DELETE FROM demo_user_account WHERE member_code = 'REJECT001'");
        jdbc.update("UPDATE agent SET report_type = NULL, center_agent_id = NULL WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_CODE);
        for (String username : new String[] {"reject-center-admin", "reject-machine-account", "reject-player"}) {
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user WHERE username = ?", username);
        }
    }
}
