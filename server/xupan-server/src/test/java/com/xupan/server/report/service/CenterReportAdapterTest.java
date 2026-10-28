package com.xupan.server.report.service;

import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Machine-session and persisted-bet behavior of the in-process center adapter. */
@SpringBootTest
@ActiveProfiles("test")
class CenterReportAdapterTest {

    private static final String ADMIN = "adapter-center-admin";
    private static final String MACHINE_USER = "adapter-machine-account";
    private static final String PLAYER_USER = "adapter-player";
    private static final String GROUP_CODE = "ADAPTER_CENTER_GROUP";
    private static final String CENTER_USERNAME = "adapter_center_up_a";
    private static final String MACHINE_CODE = "ADAPTER_MACHINE_A";
    private static final String BET_CODE = "ADAPTER-BET-1";
    private static final String ISSUE = "39999601";
    private static final String PASSWORD = "AdapterPass123";

    @Autowired
    private CenterReportAdapter adapter;

    @Autowired
    private CenterOrderGateway gateway;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private CenterUpstreamAccountService centerAccountService;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private long operatorId;
    private long groupId;
    private long machineId;
    private long machineUserId;
    private long centerAgentId;
    private long playerAccountId;
    private long betId;

    @BeforeEach
    void setUp() {
        clean();
        operatorId = userRepository.insert(ADMIN, "适配器测试管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");
        groupId = agentAdminService.createGroup(GROUP_CODE, "适配器测试子账号", operatorId).id();
        var machine = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "适配器测试机器",
                PASSWORD, groupId, operatorId);
        machineId = machine.id();
        machineUserId = machine.accountUserId();
        centerAgentId = centerAccountService.createAccount(groupId, CENTER_USERNAME, PASSWORD,
                new BigDecimal("1000.00"), null, operatorId).account().accountId();
        centerAccountService.login(machineUserId, CENTER_USERNAME, PASSWORD, "127.0.0.1");

        // K28 is deliberately first; the persisted AU8 bet must determine the center order game.
        jdbc.update("INSERT INTO agent_game (agent_id, game_code) VALUES (?, 'K28')", machineId);
        jdbc.update("INSERT INTO agent_game (agent_id, game_code) VALUES (?, 'AU8')", machineId);
        jdbc.update("INSERT INTO game_issue (game_code, issue_number, status, phase) "
                + "VALUES ('AU8', ?, 'OPEN', 'BETTING')", ISSUE);

        long playerUserId = userRepository.insert(PLAYER_USER, "适配器测试玩家", passwordPolicy.encode(PASSWORD),
                "ACTIVE");
        userRepository.assignRole(playerUserId, "USER");
        playerAccountId = walletService.ensureWalletForUser(playerUserId, "适配器测试玩家");
        jdbc.update("UPDATE demo_user_account SET agent_id = ?, balance = 1000.00 WHERE id = ?",
                machineId, playerAccountId);
        jdbc.update("""
                INSERT INTO game_bet (game_code, user_id, bet_code, request_idempotency_key, issue_number,
                                      ball_number, play_type, parameters_text, stake, odds_snapshot)
                VALUES ('AU8', ?, ?, ?, ?, 1, 'FAN', '1', 100.00, 3.850)
                """, playerAccountId, BET_CODE, BET_CODE, ISSUE);
        betId = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, BET_CODE);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void sameGroupMachineSessionAcceptsBetAndUsesPersistedGameCode() {
        ReportOutcome outcome = adapter.submit(command(true));

        assertThat(outcome).isEqualTo(ReportOutcome.ACCEPTED);
        assertThat(centerOrderCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT game_code FROM center_order WHERE agent_order_no = ?",
                String.class, "BET-" + betId)).isEqualTo("AU8");
        assertThat(jdbc.queryForObject("SELECT game_code FROM agent_game WHERE agent_id = ? ORDER BY game_code LIMIT 1",
                String.class, machineId)).isEqualTo("AU8");
        assertThat(availableBalance()).isEqualByComparingTo("900.00");
        assertThat(frozenBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void repeatedSubmitOfSameBetDoesNotDuplicateCenterOrder() {
        assertThat(adapter.submit(command(true))).isEqualTo(ReportOutcome.ACCEPTED);
        assertThat(adapter.submit(command(true))).isEqualTo(ReportOutcome.ACCEPTED);

        assertThat(centerOrderCount()).isEqualTo(1L);
        assertThat(frozenBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void machineWithoutCurrentSessionReturnsNotSentAndCreatesNoCenterOrder() {
        centerAccountService.logout(machineUserId, "127.0.0.1");

        assertThat(adapter.submit(command(true))).isEqualTo(ReportOutcome.NOT_SENT);
        assertThat(gateway.submit(machineId, betId, ISSUE, "FAN", "1", new BigDecimal("100.00"),
                new BigDecimal("3.850"))).isEqualTo(ReportOutcome.NOT_SENT);
        assertThat(centerOrderCount()).isZero();
    }

    @Test
    void disabledCenterAccountRevokesSessionAndReturnsNotSent() {
        centerAccountService.changeStatus(centerAgentId, "DISABLED", "测试停用中心账号", operatorId);

        assertThat(adapter.submit(command(true))).isEqualTo(ReportOutcome.NOT_SENT);
        assertThat(centerOrderCount()).isZero();
        assertThat(availableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void insufficientAvailableQuotaIsAnExplicitRejection() {
        assertThat(gateway.submit(machineId, betId, ISSUE, "FAN", "1", new BigDecimal("5000.00"),
                new BigDecimal("3.850"))).isEqualTo(ReportOutcome.REJECTED);

        assertThat(centerOrderCount()).isEqualTo(1L);
        assertThat(frozenBalance()).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT reject_code FROM center_order WHERE agent_order_no = ?",
                String.class, "BET-" + betId)).isEqualTo("CENTER_AVAILABLE_INSUFFICIENT");
    }

    @Test
    void offlineCommandIsNotSent() {
        assertThat(adapter.submit(command(false))).isEqualTo(ReportOutcome.NOT_SENT);
        assertThat(centerOrderCount()).isZero();
    }

    @Test
    void adapterCodeMatchesCenterRoute() {
        assertThat(adapter.code()).isEqualTo("CENTER");
    }

    private ReportCommand command(boolean online) {
        return new ReportCommand(betId, playerAccountId, machineId, ISSUE, "FAN", "1",
                new BigDecimal("100.00"), new BigDecimal("3.850"), "CENTER", "K28", "A", online);
    }

    private long centerOrderCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE center_agent_id = ?",
                Long.class, centerAgentId);
        return count == null ? 0 : count;
    }

    private BigDecimal availableBalance() {
        return jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
    }

    private BigDecimal frozenBalance() {
        return jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
    }

    private void clean() {
        jdbc.update("DELETE FROM center_machine_session WHERE machine_agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM center_risk_hit WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM game_bet WHERE bet_code = ?", BET_CODE);
        jdbc.update("DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number = ?", ISSUE);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", PLAYER_USER);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", PLAYER_USER);
        jdbc.update("DELETE FROM agent_game WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_USERNAME);
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        for (String username : new String[] {ADMIN, MACHINE_USER, PLAYER_USER}) {
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)",
                    username);
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                    + "(SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                    + "(SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM auth_session WHERE user_id IN "
                    + "(SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM auth_ws_ticket WHERE user_id IN "
                    + "(SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user WHERE username = ?", username);
        }
    }
}
