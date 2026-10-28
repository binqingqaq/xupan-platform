package com.xupan.server.report.service;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 4：运营后台（{@code /console}，BY220 {@code robot.php}）报网/盘口的后端行为。
 *
 * <p>覆盖：读配置与统计、保存配置（密码只存密文、响应绝不回显）、范围与越权、
 * 中心账号密码登录/退出、按报网状态重报的状态机、订单按上报状态筛选与计数、
 * 以及下注链路入口 {@link ReportService#submitForBet(long)} 的自动上报条件。</p>
 *
 * <p>合规边界：报网登录和上报均在当前应用内部闭环，不访问外部上游。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class ReportConsoleTest {

    private static final String ADMIN = "reportconsole-admin";
    private static final String MACHINE_A_USER = "reportconsole-agent-a";
    private static final String MACHINE_B_USER = "reportconsole-agent-b";
    private static final String MACHINE_A_CODE = "REPORTCONSOLE_A";
    private static final String MACHINE_B_CODE = "REPORTCONSOLE_B";
    private static final String PASSWORD = "ReportConsolePassword123";
    private static final String CENTER_USERNAME = "reportconsole-center";
    private static final String CENTER_PASSWORD = "ReportConsoleCenterPassword123!";
    private static final String GROUP_CODE = "REPORTCONSOLE_GROUP";
    private static final String ISSUE_NUMBER = Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private com.xupan.server.center.service.CenterUpstreamAccountService centerAccountService;

    @Autowired
    private VirtualWalletService walletService;

    private long adminId;
    private long machineAId;
    private long machineBId;
    private long centerGroupId;

    /** 通过接口创建的玩家用户名由后端自动生成，必须显式登记才能可靠清理。 */
    private final List<Long> createdPlayerUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdPlayerUserIds.clear();
        clean();
        java.time.Instant now = java.time.Instant.now();
        jdbc.update("""
                INSERT INTO game_issue (game_code, issue_number, status, phase, issue_started_at,
                                        betting_ends_at, draw_ends_at)
                VALUES ('AU8', ?, 'OPEN', 'BETTING', ?, ?, ?)
                """, ISSUE_NUMBER, java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(now.plus(2, ChronoUnit.HOURS)));
        adminId = userRepository.insert(ADMIN, "报网配置管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        jdbc.update("INSERT INTO agent_group (group_code, display_name, status, created_by) VALUES (?, ?, 'ACTIVE', ?)",
                GROUP_CODE, "报网测试子账号", adminId);
        centerGroupId = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, GROUP_CODE);
        machineAId = agentAdminService.createAgent(MACHINE_A_CODE, MACHINE_A_USER, "报网机器A", PASSWORD,
                centerGroupId, adminId).id();
        machineBId = agentAdminService.createAgent(MACHINE_B_CODE, MACHINE_B_USER, "报网机器B", PASSWORD,
                centerGroupId, adminId).id();
        centerAccountService.createAccount(centerGroupId, CENTER_USERNAME, CENTER_PASSWORD,
                new java.math.BigDecimal("10000.00"), Instant.now().plus(30, ChronoUnit.DAYS), adminId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /** 同一子账号的机器可用同一中心账号，但登录会话按机器分别建立。 */
    @Test
    void centerLoginUsesAssignedCredentialsAndKeepsSessionsMachineScoped() throws Exception {
        String tokenA = login(MACHINE_A_USER, PASSWORD);
        String tokenB = login(MACHINE_B_USER, PASSWORD);

        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(tokenA))
                        .contentType("application/json")
                        .content("{\"username\":\"" + CENTER_USERNAME + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CENTER_UPSTREAM_AUTH_INVALID"));

        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(tokenA))
                        .contentType("application/json")
                        .content("{\"username\":\"" + CENTER_USERNAME + "\",\"password\":\"" + CENTER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true))
                .andExpect(jsonPath("$.centerAccountUsername").value(CENTER_USERNAME));

        assertThat(online(machineAId)).isTrue();
        assertThat(online(machineBId)).isFalse();

        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(tokenB))
                        .contentType("application/json")
                        .content("{\"username\":\"" + CENTER_USERNAME + "\",\"password\":\"" + CENTER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true));
        assertThat(online(machineBId)).isTrue();

        mockMvc.perform(post("/api/admin/player-desk/report/logout").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(false));
        assertThat(online(machineAId)).isFalse();
        assertThat(online(machineBId)).isTrue();
    }

    @Test
    void machineAccountReadsEmptyReportConfigByDefault() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.reportAuto").value(false))
                .andExpect(jsonPath("$.reportOnline").value(false))
                .andExpect(jsonPath("$.reportWithdraw").value(false))
                .andExpect(jsonPath("$.centerAccountUsername").value(CENTER_USERNAME))
                .andExpect(jsonPath("$.centerAccountExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.centerAvailableBalance").value(10000.0))
                .andExpect(jsonPath("$.centerFrozenBalance").value(0.0))
                .andExpect(jsonPath("$.centerOpenStake").value(0.0))
                .andExpect(jsonPath("$.timeoutOrderCount").value(0))
                .andExpect(jsonPath("$.unreportedCount").value(0))
                .andExpect(jsonPath("$.failedOrderCount").value(0));
    }

    @Test
    void savesMachineReportSwitchesWithoutExternalAccountConfiguration() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);

        MvcResult saved = mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportAuto\":true,\"reportWithdraw\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.reportAuto").value(true))
                .andExpect(jsonPath("$.reportWithdraw").value(true))
                .andReturn();

        MvcResult read = mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.centerAccountUsername").value(CENTER_USERNAME))
                .andReturn();

        String savedBody = saved.getResponse().getContentAsString();
        String readBody = read.getResponse().getContentAsString();
        assertThat(savedBody).doesNotContain("reportPassword").doesNotContain("reportUrl");
        assertThat(readBody).doesNotContain("reportPassword").doesNotContain("reportUrl");

        // 审计记录存在且不含密码
        String summary = jdbc.queryForObject("""
                SELECT request_summary FROM sys_operation_log
                 WHERE permission_code = 'AGENT_REPORT_CONFIG' AND resource_id = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, Long.toString(machineAId));
        assertThat(summary).isNotNull().doesNotContain(CENTER_PASSWORD);

        // 只更新一个开关时保留另一个值。
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportWithdraw\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportAuto").value(true))
                .andExpect(jsonPath("$.reportWithdraw").value(false));
    }

    @Test
    void rejectsOutOfRangeMachineAndInvalidValues() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        String token = login(MACHINE_A_USER, PASSWORD);

        // 阶段 3（严格对齐 BY220）：报网登录/配置属于机器账号的代理后台（robot.php），
        // 超管/子账号调 /api/admin/player-desk/report** 一律 403；
        // 原"非机器身份未传机器 → 400 PLAYER_DESK_MACHINE_REQUIRED"与
        // "管理员可以显式选择机器 → 200"、"不存在的机器 → 400 PLAYER_DESK_MACHINE_INVALID"
        // 三条平台侧期望随 GLOBAL/GROUP 链路一起不可达。
        mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"reportAuto\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/report").param("machineId", String.valueOf(machineAId))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/report").param("machineId", "999999")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());

        // 机器账号传别人的机器 → 404（范围外按"不存在"处理，不静默改写）
        mockMvc.perform(get("/api/admin/player-desk/report").param("machineId", String.valueOf(machineBId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"machineId\":" + machineBId + ",\"reportAuto\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));

        // 开关字段保持严格类型校验；旧外部配置不再是当前契约。
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportAuto\":\"not-a-boolean\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
        mockMvc.perform(get("/api/admin/player-desk/report/orders").param("status", "BOGUS")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_STATUS_INVALID"));
    }

    @Test
    void centerCredentialsAreRequiredAndLoginLogoutToggleSessionState() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);

        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"username\":\"" + CENTER_USERNAME + "\",\"password\":\"bad-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CENTER_UPSTREAM_AUTH_INVALID"));
        assertThat(online(machineAId)).isFalse();

        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportAuto\":false,\"reportWithdraw\":false}"))
                .andExpect(status().isOk());

        loginCenter(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true))
                .andExpect(jsonPath("$.centerAccountUsername").value(CENTER_USERNAME));
        assertThat(online(machineAId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT authenticated_at FROM center_machine_session WHERE machine_agent_id = ?",
                java.sql.Timestamp.class, machineAId)).isNotNull();

        mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true));

        mockMvc.perform(post("/api/admin/player-desk/report/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(false));
        assertThat(online(machineAId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM center_machine_session WHERE machine_agent_id = ?",
                java.sql.Timestamp.class, machineAId)).isNotNull();
    }

    @Test
    void resubmitUsesCenterSessionAndEnforcesMachineScope() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportAuto\":false,\"reportWithdraw\":false}"))
                .andExpect(status().isOk());
        loginCenter(token).andExpect(status().isOk());

        long accountA = createPlayer(token, "报网验证玩家A");
        long acceptedBet = insertBet(accountA, "reportconsole-bet-accepted", "PENDING");

        // 中心内部接单成功后，机器侧记录 ACCEPTED。
        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + acceptedBet + "/resubmit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.betId").value((int) acceptedBet))
                .andExpect(jsonPath("$.reportStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.config.machineId").value((int) machineAId));
        assertThat(reportStatusOf(acceptedBet)).isEqualTo("ACCEPTED");

        // TIMEOUT 是可重报状态；处理依赖有效中心会话，不再依赖线路选项。
        long timeoutBet = insertBet(accountA, "reportconsole-bet-timeout", "TIMEOUT");
        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + timeoutBet + "/resubmit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("ACCEPTED"));
        assertThat(reportStatusOf(timeoutBet)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM game_bet WHERE id = ?",
                String.class, timeoutBet)).isEqualTo("PENDING");

        // 已上报的注单不允许重报 → 409
        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + acceptedBet + "/resubmit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_BET_STATUS_INVALID"));

        // 别的机器的注单 → 404（按"不存在"处理）
        String machineBToken = login(MACHINE_B_USER, PASSWORD);
        long accountB = createPlayer(machineBToken, "报网验证玩家B");
        long foreignBet = insertBet(accountB, "reportconsole-bet-foreign", "PENDING");
        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + foreignBet + "/resubmit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_BET_NOT_FOUND"));
        assertThat(reportStatusOf(foreignBet)).isEqualTo("PENDING");

        // 重报写审计（不含任何凭据）
        Long audits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE permission_code = 'AGENT_REPORT_CONFIG' AND resource_id = ?
                   AND request_path LIKE '%/resubmit'
                """, Long.class, Long.toString(machineAId));
        assertThat(audits).isEqualTo(2L);
    }

    @Test
    void countsAndFiltersOrdersByReportStatus() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        long accountA = createPlayer(token, "报网统计玩家");
        insertBet(accountA, "reportconsole-bet-unreported", "PENDING");
        insertBet(accountA, "reportconsole-bet-failed", "REJECTED");
        insertBet(accountA, "reportconsole-bet-timeout", "TIMEOUT");
        insertBet(accountA, "reportconsole-bet-reported", "ACCEPTED");
        // 别的机器的注单不能计入本机器的统计
        String machineBToken = login(MACHINE_B_USER, PASSWORD);
        long accountB = createPlayer(machineBToken, "报网统计玩家B");
        insertBet(accountB, "reportconsole-bet-b-other", "TIMEOUT");

        mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeoutOrderCount").value(1))
                .andExpect(jsonPath("$.unreportedCount").value(1))
                .andExpect(jsonPath("$.failedOrderCount").value(1));

        mockMvc.perform(get("/api/admin/player-desk/report/orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items.length()").value(4));

        mockMvc.perform(get("/api/admin/player-desk/report/orders").param("status", "TIMEOUT")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].reportStatus").value("TIMEOUT"))
                .andExpect(jsonPath("$.items[0].betCode").value("reportconsole-bet-timeout"))
                .andExpect(jsonPath("$.items[0].issueNumber").value(ISSUE_NUMBER))
                .andExpect(jsonPath("$.items[0].settlementStatus").value("PENDING"));

        mockMvc.perform(get("/api/admin/player-desk/report/orders").param("status", "REJECTED")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].responseCode").value("CENTER_TEST_REJECTED"))
                .andExpect(jsonPath("$.items[0].responseMessage").value("测试拒绝原因"));

        mockMvc.perform(get("/api/admin/player-desk/report/orders").param("status", "ACCEPTED")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/admin/player-desk/report/orders").param("status", "BOGUS")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_STATUS_INVALID"));

        mockMvc.perform(get("/api/admin/player-desk/report/orders").param("pageSize", "1000")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_QUERY_INVALID"));
    }

    @Test
    void keepsLocalBetAfterCenterCancellationAndMakesDecisionImmutableAndIdempotent() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        long accountId = createPlayer(token, "中心撤单保留本地单玩家");
        long betId = insertCenterManualBet(accountId, "reportconsole-bet-center-cancel-keep", "CANCELLED");
        java.math.BigDecimal balanceBefore = playerBalance(accountId);
        String reason = "中心明确撤单，核对本地注单有效，保留参加本地开奖";

        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + betId + "/local-disposition")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"decision\":\"KEEP_LOCAL\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.centerOutcome").value("CANCELLED"))
                .andExpect(jsonPath("$.decision").value("KEEP_LOCAL"))
                .andExpect(jsonPath("$.settlementStatus").value("PENDING"))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(playerBalance(accountId)).isEqualByComparingTo(balanceBefore);
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId))
                .isEqualTo("PENDING");
        assertThat(reportStatusOf(betId)).isEqualTo("CANCELLED");

        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + betId + "/local-disposition")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"decision\":\"KEEP_LOCAL\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + betId + "/local-disposition")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"decision\":\"REFUND_CANCEL_LOCAL\",\"reason\":\"改成退款\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_LOCAL_DISPOSITION_ALREADY_DECIDED"));

        mockMvc.perform(get("/api/admin/player-desk/report/orders").param("status", "CANCELLED")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].localCenterOutcome").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].localDisposition").value("KEEP_LOCAL"))
                .andExpect(jsonPath("$.items[0].localDispositionReason").value(reason));
        assertThat(dispositionAuditCount(betId)).isEqualTo(1L);
    }

    @Test
    void refundDispositionCancelsLocalBetAndCreditsStakeExactlyOnce() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        long accountId = createPlayer(token, "中心退款本地退款玩家");
        long betId = insertCenterManualBet(accountId, "reportconsole-bet-center-refund", "REFUNDED");
        java.math.BigDecimal balanceBefore = playerBalance(accountId);
        String reason = "中心退款已核实，本地注单同步退回本金并取消";
        String request = "{\"decision\":\"REFUND_CANCEL_LOCAL\",\"reason\":\"" + reason + "\"}";

        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + betId + "/local-disposition")
                        .header("Authorization", bearer(token)).contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.centerOutcome").value("REFUNDED"))
                .andExpect(jsonPath("$.decision").value("REFUND_CANCEL_LOCAL"))
                .andExpect(jsonPath("$.settlementStatus").value("CANCELED"))
                .andExpect(jsonPath("$.refundedAmount").value(10.0))
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(playerBalance(accountId)).isEqualByComparingTo(balanceBefore.add(new java.math.BigDecimal("10.00")));
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE idempotency_key = ? AND related_bet_id = ? AND amount = 10.00
                """, Long.class, "CENTER_LOCAL_DISPOSITION_REFUND:" + betId, betId)).isEqualTo(1L);

        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + betId + "/local-disposition")
                        .header("Authorization", bearer(token)).contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(playerBalance(accountId)).isEqualByComparingTo(balanceBefore.add(new java.math.BigDecimal("10.00")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_balance_ledger WHERE idempotency_key = ?",
                Long.class, "CENTER_LOCAL_DISPOSITION_REFUND:" + betId)).isEqualTo(1L);
        assertThat(dispositionAuditCount(betId)).isEqualTo(1L);
    }

    @Test
    void localDispositionRequiresReasonAndEnforcesMachineOwnership() throws Exception {
        String machineAToken = login(MACHINE_A_USER, PASSWORD);
        String machineBToken = login(MACHINE_B_USER, PASSWORD);
        long accountB = createPlayer(machineBToken, "中心撤单跨机器玩家");
        long foreignBet = insertCenterManualBet(accountB, "reportconsole-bet-center-cancel-foreign", "CANCELLED");

        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + foreignBet + "/local-disposition")
                        .header("Authorization", bearer(machineAToken)).contentType("application/json")
                        .content("{\"decision\":\"KEEP_LOCAL\",\"reason\":\"跨机器测试\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_BET_NOT_FOUND"));
        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + foreignBet + "/local-disposition")
                        .header("Authorization", bearer(machineBToken)).contentType("application/json")
                        .content("{\"decision\":\"KEEP_LOCAL\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM game_bet WHERE id = ?",
                String.class, foreignBet)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_local_disposition WHERE bet_id = ?",
                Long.class, foreignBet)).isZero();
    }

    @Test
    void submitForBetOnlyReportsWhenAutoAndOnline() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        long accountA = createPlayer(token, "自动上报玩家");
        long betId = insertBet(accountA, "reportconsole-bet-auto", "PENDING");

        // 自动上报开、但机器离线 → 不提交，状态不变
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportAuto\":true,\"reportWithdraw\":false}"))
                .andExpect(status().isOk());
        assertThat(reportService.submitForBet(betId)).isEmpty();
        assertThat(reportStatusOf(betId)).isEqualTo("PENDING");

        // 中心账号登录后 → 自动上报成功，并且重复调用是幂等的
        loginCenter(token).andExpect(status().isOk());
        assertThat(reportService.submitForBet(betId)).contains("ACCEPTED");
        assertThat(reportStatusOf(betId)).isEqualTo("ACCEPTED");
        assertThat(reportService.submitForBet(betId)).isEmpty();

        // 不存在的注单直接跳过，不抛异常
        assertThat(reportService.submitForBet(999999999L)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions loginCenter(String machineToken) throws Exception {
        return mockMvc.perform(post("/api/admin/player-desk/report/login")
                .header("Authorization", bearer(machineToken)).contentType("application/json")
                .content("{\"username\":\"" + CENTER_USERNAME + "\",\"password\":\""
                        + CENTER_PASSWORD + "\"}"));
    }

    private long createPlayer(String token, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long userId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
        createdPlayerUserIds.add(userId);
        return jdbc.queryForObject("SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, userId);
    }

    /**
     * 直接插入注单，覆盖 V38/V48/V53 之后的必填列。
     *
     * <p>V58 起上报状态不在 {@code game_bet} 上，而是"该注单最新一行上报订单"的派生值；
     * 指定非 {@code PENDING} 状态时改为追加一行上报订单。</p>
     */
    private long insertBet(long accountId, String betCode, String reportStatus) {
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, ?, ?, ?, 1, 'FAN', '1', 10.00, 3.850)
                """, accountId, betCode, betCode, ISSUE_NUMBER);
        Long betId = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
        if (betId != null && reportStatus != null && !"PENDING".equals(reportStatus)) {
            jdbc.update("""
                    INSERT INTO report_upstream_order
                        (bet_id, agent_id, attempt_no, status, response_code, response_message,
                         idempotency_key, sent_at, resolved_at)
                    SELECT ?, a.agent_id, 1, ?,
                           CASE WHEN ? = 'REJECTED' THEN 'CENTER_TEST_REJECTED' ELSE 'CENTER_TEST_STATUS' END,
                           CASE WHEN ? = 'REJECTED' THEN '测试拒绝原因' ELSE '测试状态记录' END,
                           ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                      FROM demo_user_account a WHERE a.id = ?
                    """, betId, reportStatus, reportStatus, reportStatus, "SEED-" + betCode, accountId);
        }
        return betId;
    }

    private long insertCenterManualBet(long accountId, String betCode, String centerOutcome) {
        long betId = insertBet(accountId, betCode, null);
        Long agentId = jdbc.queryForObject("SELECT agent_id FROM demo_user_account WHERE id = ?", Long.class, accountId);
        Long sysUserId = jdbc.queryForObject("SELECT sys_user_id FROM demo_user_account WHERE id = ?", Long.class, accountId);
        Long centerAgentId = jdbc.queryForObject("SELECT id FROM center_agent WHERE agent_code = ?",
                Long.class, CENTER_USERNAME);
        Long machineUserId = jdbc.queryForObject("SELECT account_user_id FROM agent WHERE id = ?",
                Long.class, agentId);
        walletService.grant(machineUserId, sysUserId, new java.math.BigDecimal("20.00"),
                "中心撤退款本地注单测试下注本金", "REPORTCONSOLE-SEED-GRANT:" + betCode);
        walletService.debitForBet(sysUserId, betId, betCode, ISSUE_NUMBER, new java.math.BigDecimal("10.00"));
        String responseCode = "CANCELLED".equals(centerOutcome) ? "CENTER_CANCELLED" : "CENTER_REFUNDED";
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, center_agent_id, attempt_no, status, report_type,
                     response_code, response_message, idempotency_key, sent_at, resolved_at)
                VALUES (?, ?, ?, 1, 'MANUAL', 'CENTER', ?, '测试中心终态', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, betId, agentId, centerAgentId, responseCode, "SEED-CENTER-" + betCode);
        return betId;
    }

    private java.math.BigDecimal playerBalance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                java.math.BigDecimal.class, accountId);
    }

    private long dispositionAuditCount(long betId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE permission_code = 'AGENT_REPORT_CONFIG' AND resource_id = ?
                   AND request_path LIKE '%/local-disposition'
                """, Long.class, Long.toString(betId));
    }

    private String reportStatusOf(long betId) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT o.status FROM report_upstream_order o
                                  WHERE o.bet_id = b.id ORDER BY o.id DESC LIMIT 1), 'PENDING')
                  FROM game_bet b WHERE b.id = ?
                """, String.class, betId);
    }

    private boolean online(long machineId) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM center_machine_session
                 WHERE machine_agent_id = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP)
                """, Boolean.class, machineId);
        return Boolean.TRUE.equals(value);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        List<Long> machineIds = jdbc.queryForList("SELECT id FROM agent WHERE agent_code IN (?, ?)",
                Long.class, MACHINE_A_CODE, MACHINE_B_CODE);
        List<Long> centerAccountIds = jdbc.queryForList("SELECT id FROM center_agent WHERE agent_code = ?",
                Long.class, CENTER_USERNAME);
        List<Long> users = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username LIKE 'reportconsole-%'", Long.class));
        users.addAll(createdPlayerUserIds);
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

        if (!owners.isEmpty()) {
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", owners);
            delete("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", owners, owners);
            delete("DELETE FROM player_name_change_record WHERE user_id IN (%s)", owners);
            delete("DELETE FROM player_link_expiration_config WHERE user_id IN (%s)", owners);
        }
        if (!centerAccountIds.isEmpty()) {
            delete("DELETE FROM center_order_disposition WHERE center_order_id IN "
                    + "(SELECT id FROM center_order WHERE center_agent_id IN (%s))", centerAccountIds);
            delete("DELETE FROM center_order WHERE center_agent_id IN (%s)", centerAccountIds);
            delete("DELETE FROM center_machine_session WHERE center_agent_id IN (%s)", centerAccountIds);
            delete("DELETE FROM center_agent_ledger WHERE center_agent_id IN (%s)", centerAccountIds);
            delete("DELETE FROM center_agent WHERE id IN (%s)", centerAccountIds);
        }
        if (!accounts.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
        }
        delete("DELETE FROM game_bet WHERE bet_code LIKE 'reportconsole-%'");
        jdbc.update("DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number = ?", ISSUE_NUMBER);
        if (!accounts.isEmpty()) {
            delete("DELETE FROM game_bet WHERE user_id IN (%s)", accounts);
        }
        if (!accounts.isEmpty()) {
            delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM demo_user_account WHERE id IN (%s)", accounts);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
        delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        if (!owners.isEmpty()) {
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", owners);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", owners);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", owners);
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
