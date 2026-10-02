package com.xupan.server.report.service;

import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报网闭环端到端（用户 2026-10-02 确认按 A 方案：上报真实影响订单与玩家余额）。
 *
 * <p>BY220 时序依据：飞单是**独立轮询**（{@code application/console/Websocket.php:658-673}
 * 的 {@code Swoole\Timer::tick(2400, ...)}），不是下注事务的一部分；失败且机器开启
 * {@code flyers_withdraw} 时退玩家本金并物理删除注单（{@code common.php:1627-1641}）。</p>
 *
 * <p>覆盖：配置 → 本地模拟登录 → 下注产生待报订单 → 轮询上报成功（已报）→ 盘口 D 触发超时（只标记不退款）
 * → 失败自动退单（退还本金 + 物理删除注单）→ 重报已报订单被拒。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "xupan.report.dispatch-interval-ms=3600000"})
class ReportClosureIntegrationTest {

    private static final String ADMIN = "reportclosure-admin";
    private static final String MACHINE_USER = "reportclosure-agent";
    private static final String MACHINE_CODE = "REPORTCLOSURE_A";
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
    private VirtualWalletService walletService;

    @Autowired
    private ReportService reportService;

    private long adminId;
    private long machineId;
    private long accountId;
    private long sysUserId;
    private final List<Long> createdBetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "报网闭环管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "报网闭环机器", PASSWORD, null, adminId).id();

        // 造一个属于该机器的玩家与其钱包
        sysUserId = userRepository.insert("reportclosure-player", "报网闭环玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(sysUserId, "USER");
        accountId = walletService.ensureWalletForUser(sysUserId, "报网闭环玩家");
        jdbc.update("UPDATE demo_user_account SET agent_id=?, balance=100.00 WHERE id=?", machineId, accountId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void fullClosureFromConfigToWithdrawAndResubmit() {
        // ① 配置报网并本地模拟登录（盘口 A → 接受）
        configureReport("K28", "A", true, false);
        reportService.login(adminId, machineId);

        // ② 下注产生一笔待报订单（下注本身还没有上报订单行，派生状态为 PENDING，BY220 上报由轮询完成）
        long acceptedBet = insertBet("CLOSURE-ACCEPT-1", "PENDING");

        // ③ 轮询上报 → 已报
        ReportService.DispatchResult first = reportService.dispatchPending(true, 50);
        assertThat(first.scanned()).isGreaterThanOrEqualTo(1);
        assertThat(reportStatusOf(acceptedBet)).isEqualTo("ACCEPTED");
        // 追加保留：一次上报尝试一行，不覆盖历史
        assertThat(attemptCountOf(acceptedBet)).isEqualTo(1);
        // 押注为本地模拟值（BY220 本地登录时 betting 也为 0）：累计已成功上报的投注额
        assertThat(reportStakeOf()).isEqualByComparingTo("10.00");

        // ④ 盘口切到 D（本地模拟超时分支）→ 新订单上报后为 TIMEOUT，且**不退款、不删单**
        long timeoutBet = insertBet("CLOSURE-TIMEOUT-1", "PENDING");
        BigDecimal balanceBefore = balanceOf();
        jdbc.update("UPDATE agent SET report_route_code='D' WHERE id=?", machineId);
        reportService.dispatchPending(true, 50);

        assertThat(reportStatusOf(timeoutBet)).isEqualTo("TIMEOUT");
        assertThat(betExists(timeoutBet)).as("超时不得删除注单").isTrue();
        assertThat(balanceOf()).as("超时不得退款").isEqualByComparingTo(balanceBefore);

        // ⑤ 打开失败自动退单，并清空网盘编码 → 适配器判为配置不完整（REJECTED）→ 退款并物理删除注单
        jdbc.update("UPDATE agent SET report_route_code='A' WHERE id=?", machineId);
        jdbc.update("UPDATE agent SET report_withdraw=TRUE WHERE id=?", machineId);
        long failedBet = insertBet("CLOSURE-FAIL-1", "PENDING");
        jdbc.update("UPDATE agent SET report_network_code=NULL WHERE id=?", machineId);
        BigDecimal balanceBeforeWithdraw = balanceOf();
        reportService.dispatchPending(true, 50);

        assertThat(betExists(failedBet)).as("失败退单应物理删除注单").isFalse();
        assertThat(balanceOf()).as("失败退单应退还本金")
                .isEqualByComparingTo(balanceBeforeWithdraw.add(new BigDecimal("10.00")));
        // BY220 失败退单还会发一条"注单取消"房间提示（common.php:1635 addMsg）
        assertThat(systemCancelMessageCount(failedBet)).as("失败退单应写入一条系统取消提示").isEqualTo(1);

        // ⑥ 重报已被删除的订单：按范围不存在，调用失败（幂等保护）
        assertThatThrownByResubmitMissing(failedBet);
    }

    @Test
    void resubmitRejectedOrderReturnsReportedAfterRouteIsFixed() {
        configureReport("K28", "A", true, false);
        // 先按完整配置登录（login 有配置完整性前置校验），再清空网盘编码让适配器判为失败
        reportService.login(adminId, machineId);
        jdbc.update("UPDATE agent SET report_network_code=NULL WHERE id=?", machineId);
        long betId = insertBet("CLOSURE-RETRY-1", "PENDING");
        jdbc.update("UPDATE agent SET report_withdraw=FALSE WHERE id=?", machineId);

        reportService.dispatchPending(true, 50);
        assertThat(reportStatusOf(betId)).isEqualTo("REJECTED");
        assertThat(betExists(betId)).as("未开启失败退单时保留注单").isTrue();

        // 配置仍不完整时重报：适配器仍判失败，状态保持 FAILED（重报本身不做配置完整性前置校验，
        // 因为它就是"再试一次"的入口；只有 login 要求配置完整）
        var stillFailed = reportService.resubmit(adminId, machineId, betId);
        assertThat(stillFailed.reportStatus()).isEqualTo("REJECTED");

        // 补齐网盘编码后手动重报 → 已报
        jdbc.update("UPDATE agent SET report_network_code='K28' WHERE id=?", machineId);
        var result = reportService.resubmit(adminId, machineId, betId);
        assertThat(result.reportStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void offlineMachineIsSkippedByDispatch() {
        configureReport("K28", "A", true, false);
        // 未登录（离线）：轮询不得处理该机器的订单
        long betId = insertBet("CLOSURE-OFFLINE-1", "PENDING");
        reportService.dispatchPending(true, 50);
        assertThat(reportStatusOf(betId)).isEqualTo("PENDING");
    }

    @Test
    void autoReportDisabledIsSkippedByDispatch() {
        configureReport("K28", "A", false, false); // 关闭自动上报
        reportService.login(adminId, machineId);
        long betId = insertBet("CLOSURE-MANUAL-1", "PENDING");
        reportService.dispatchPending(true, 50);
        assertThat(reportStatusOf(betId)).isEqualTo("PENDING");
    }

    private void assertThatThrownByResubmitMissing(long betId) {
        try {
            reportService.resubmit(adminId, machineId, betId);
            throw new AssertionError("已删除的注单不应能重报");
        } catch (com.xupan.server.web.BusinessException expected) {
            // 注单已随退单物理删除，按"不存在"处理
            assertThat(expected.code()).isIn("REPORT_BET_NOT_FOUND", "MACHINE_NOT_FOUND");
        }
    }

    private void configureReport(String type, String route, boolean auto, boolean withdraw) {
        jdbc.update("""
                UPDATE agent SET report_type=?, report_route_code=?, report_auto=?, report_withdraw=?,
                    report_username='closure-user', report_network_code='K28'
                 WHERE id=?
                """, type, route, auto, withdraw, machineId);
    }

    /**
     * 造一笔注单。
     *
     * <p>V58 起上报状态不在 {@code game_bet} 上，而是"该注单最新一行上报订单"的派生值，
     * 因此需要指定非 {@code PENDING} 状态时改写成追加一行上报订单。</p>
     */
    private long insertBet(String betCode, String reportStatus) {
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, ?, ?, ?, 1, 'FAN', '1', 10.00, 3.850)
                """, accountId, betCode, betCode, ISSUE_NUMBER);
        Long id = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
        if (id == null) {
            return 0L;
        }
        createdBetIds.add(id);
        if (reportStatus != null && !"PENDING".equals(reportStatus)) {
            jdbc.update("""
                    INSERT INTO report_upstream_order
                        (bet_id, agent_id, attempt_no, status, idempotency_key, sent_at, resolved_at)
                    VALUES (?, ?, 1, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, id, machineId, reportStatus, "SEED-" + betCode);
        }
        return id;
    }

    /** 注单的派生上报状态：最新一行上报订单的状态，没有行即 {@code PENDING}。 */
    private String reportStatusOf(long betId) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT o.status FROM report_upstream_order o
                                  WHERE o.bet_id = b.id ORDER BY o.id DESC LIMIT 1), 'PENDING')
                  FROM game_bet b WHERE b.id = ?
                """, String.class, betId);
    }

    /** 该注单的上报订单行数（验证"追加保留"）。 */
    private long attemptCountOf(long betId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?", Long.class, betId);
        return count == null ? 0L : count;
    }

    private boolean betExists(long betId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE id = ?", Long.class, betId);
        return count != null && count > 0;
    }

    /** 统计该注单的"注单取消"系统提示条数（幂等键以注单号结尾）。 */
    private long systemCancelMessageCount(long betId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_message
                 WHERE message_type = 'SYSTEM' AND sender_type = 'SYSTEM'
                   AND idempotency_key = ?
                """, Long.class, "CHAT-REPORT-WITHDRAW-" + betId);
        return count == null ? 0L : count;
    }

    /** 机器报网的"押注"本地模拟值（累计已成功上报的投注额）。 */
    private BigDecimal reportStakeOf() {
        return jdbc.queryForObject("SELECT report_stake FROM agent WHERE id = ?", BigDecimal.class, machineId);
    }

    private BigDecimal balanceOf() {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?", BigDecimal.class, accountId);
    }

    private void clean() {
        List<Long> machineIds = jdbc.queryForList("SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);
        List<Long> users = jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username LIKE 'reportclosure-%'", Long.class);
        List<Long> accounts = new ArrayList<>();
        if (!machineIds.isEmpty()) {
            accounts.addAll(jdbc.queryForList("SELECT id FROM demo_user_account WHERE agent_id IN ("
                    + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray()));
        }
        if (!users.isEmpty()) {
            accounts.addAll(jdbc.queryForList("SELECT id FROM demo_user_account WHERE sys_user_id IN ("
                    + placeholders(users.size()) + ")", Long.class, users.toArray()));
        }
        List<Long> allUsers = new ArrayList<>(users);
        if (!accounts.isEmpty()) {
            accounts = accounts.stream().distinct().toList();
            allUsers.addAll(jdbc.queryForList("SELECT sys_user_id FROM demo_user_account WHERE id IN ("
                    + placeholders(accounts.size()) + ")", Long.class, accounts.toArray()));
        }
        List<Long> owners = allUsers.stream().filter(java.util.Objects::nonNull).distinct().toList();

        if (!accounts.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
            delete("DELETE FROM game_bet WHERE user_id IN (%s)", accounts);
            delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accounts);
        }
        if (!createdBetIds.isEmpty()) {
            delete("DELETE FROM game_bet WHERE id IN (%s)", createdBetIds);
        }
        if (!owners.isEmpty()) {
            delete("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", owners, owners);
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", owners);
        }
        if (!accounts.isEmpty()) {
            delete("DELETE FROM demo_user_account WHERE id IN (%s)", accounts);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!owners.isEmpty()) {
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", owners);
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", owners);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", owners);
            delete("DELETE FROM sys_user WHERE id IN (%s)", owners);
        }
    }

    private void delete(String sqlTemplate, List<Long>... groups) {
        String[] parts = sqlTemplate.split("%s", -1);
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            sql.append(parts[i]);
            if (i < parts.length - 1) {
                List<Long> group = groups[Math.min(i, groups.length - 1)];
                sql.append(placeholders(group.size()));
                args.addAll(group);
            }
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(count, 1), "?"));
    }
}
