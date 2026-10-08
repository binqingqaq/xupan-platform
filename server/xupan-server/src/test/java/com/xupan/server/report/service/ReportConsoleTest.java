package com.xupan.server.report.service;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * 本地模拟登录/退出（不发外部请求）、按盘口线路重报的状态机、订单按上报状态筛选与计数、
 * 以及下注链路入口 {@link ReportService#submitForBet(long)} 的自动上报条件。</p>
 *
 * <p>合规边界：全部使用本地模拟适配器，不访问任何外部地址；
 * {@code report_url} 只保存不请求；测试里使用的域名是 {@code .invalid} 保留域。</p>
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
    /** 只在本机测试中出现的假飞单密码；断言它绝不出现在任何响应与审计里。 */
    private static final String REPORT_SECRET = "ReportConsoleFlyPassword123!";
    private static final String REPORT_URL = "https://report.invalid/upstream";
    private static final String ISSUE_NUMBER = "39999801";

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

    private long adminId;
    private long machineAId;
    private long machineBId;

    /** 通过接口创建的玩家用户名由后端自动生成，必须显式登记才能可靠清理。 */
    private final List<Long> createdPlayerUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdPlayerUserIds.clear();
        clean();
        adminId = userRepository.insert(ADMIN, "报网配置管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        machineAId = agentAdminService.createAgent(MACHINE_A_CODE, MACHINE_A_USER, "报网机器A", PASSWORD, null, adminId).id();
        machineBId = agentAdminService.createAgent(MACHINE_B_CODE, MACHINE_B_USER, "报网机器B", PASSWORD, null, adminId).id();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /**
     * 回归（账本原则 8）：同一上游账号不得被多台机器共用；软删除后必须释放占用。
     *
     * <p>依据《代理报网盘口与结算业务规则说明》§14：共用同一上游账号会导致
     * 余额混合、订单归属不明、风险共用与退款不清。</p>
     */
    @Test
    void forbidsSharingUpstreamAccountAcrossMachinesAndReleasesOnDelete() throws Exception {
        String tokenA = login(MACHINE_A_USER, PASSWORD);
        String tokenB = login(MACHINE_B_USER, PASSWORD);

        // 机器 A 占用 feidan-a
        mockMvc.perform(put("/api/admin/player-desk/report").header("Authorization", bearer(tokenA))
                        .contentType("application/json").content("{\"reportUsername\":\"feidan-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportUsername").value("feidan-a"));

        // 机器 B 使用同一上游账号 → 409
        mockMvc.perform(put("/api/admin/player-desk/report").header("Authorization", bearer(tokenB))
                        .contentType("application/json").content("{\"reportUsername\":\"feidan-a\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_USERNAME_TAKEN"));

        // 换成别的账号即可
        mockMvc.perform(put("/api/admin/player-desk/report").header("Authorization", bearer(tokenB))
                        .contentType("application/json").content("{\"reportUsername\":\"feidan-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportUsername").value("feidan-b"));

        // 软删除机器 B → 释放上游账号与凭据，不长期占位
        String adminToken = login(ADMIN, PASSWORD);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/machines/" + machineBId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE id = ? AND report_username IS NOT NULL",
                Long.class, machineBId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE id = ? AND report_password_ciphertext IS NOT NULL",
                Long.class, machineBId)).isZero();

        // 释放后机器 A 可以改用 feidan-b
        mockMvc.perform(put("/api/admin/player-desk/report").header("Authorization", bearer(tokenA))
                        .contentType("application/json").content("{\"reportUsername\":\"feidan-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportUsername").value("feidan-b"));
    }

    @Test
    void machineAccountReadsEmptyReportConfigByDefault() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.reportType").doesNotExist())
                .andExpect(jsonPath("$.reportAuto").value(false))
                .andExpect(jsonPath("$.reportOnline").value(false))
                .andExpect(jsonPath("$.reportWithdraw").value(false))
                .andExpect(jsonPath("$.reportUsername").doesNotExist())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.reportUrl").doesNotExist())
                .andExpect(jsonPath("$.reportNetworkCode").doesNotExist())
                .andExpect(jsonPath("$.reportRouteCode").doesNotExist())
                .andExpect(jsonPath("$.reportBalance").value(0.0))
                .andExpect(jsonPath("$.reportStake").value(0.0))
                .andExpect(jsonPath("$.reportLastLoginAt").doesNotExist())
                .andExpect(jsonPath("$.timeoutOrderCount").value(0))
                .andExpect(jsonPath("$.unreportedCount").value(0))
                .andExpect(jsonPath("$.failedOrderCount").value(0));
    }

    @Test
    void savesReportConfigWithoutEverReturningPassword() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);

        MvcResult saved = mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(fullConfig("A", true, true, REPORT_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.reportType").value("TT"))
                .andExpect(jsonPath("$.reportAuto").value(true))
                .andExpect(jsonPath("$.reportWithdraw").value(true))
                .andExpect(jsonPath("$.reportUsername").value("feidan-a"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.reportUrl").value(REPORT_URL))
                .andExpect(jsonPath("$.reportNetworkCode").value("NET_A"))
                .andExpect(jsonPath("$.reportRouteCode").value("A"))
                .andReturn();

        MvcResult read = mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andReturn();

        // 密码只存密文：库里既不是明文，也必须带加密版本前缀
        String ciphertext = jdbc.queryForObject(
                "SELECT report_password_ciphertext FROM agent WHERE id = ?", String.class, machineAId);
        assertThat(ciphertext).isNotBlank().isNotEqualTo(REPORT_SECRET).startsWith("v1.");

        // 明文与密文都不得出现在任何响应体里
        String savedBody = saved.getResponse().getContentAsString();
        String readBody = read.getResponse().getContentAsString();
        assertThat(savedBody).doesNotContain(REPORT_SECRET).doesNotContain(ciphertext);
        assertThat(readBody).doesNotContain(REPORT_SECRET).doesNotContain(ciphertext);

        // 审计记录存在且不含密码
        String summary = jdbc.queryForObject("""
                SELECT request_summary FROM sys_operation_log
                 WHERE permission_code = 'AGENT_REPORT_CONFIG' AND resource_id = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, Long.toString(machineAId));
        assertThat(summary).isNotNull().doesNotContain(REPORT_SECRET).doesNotContain(ciphertext);

        // 不传密码时保留已保存的密文（不会被清空）
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportRouteCode\":\"B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportRouteCode").value("B"))
                .andExpect(jsonPath("$.hasPassword").value(true));
        assertThat(jdbc.queryForObject("SELECT report_password_ciphertext FROM agent WHERE id = ?",
                String.class, machineAId)).isEqualTo(ciphertext);
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
                        .content("{\"reportType\":\"TT\"}"))
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
                        .content("{\"machineId\":" + machineBId + ",\"reportType\":\"TT\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));

        // 取值校验：报网类型 / 盘口线路 / 上游地址
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportType\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_TYPE_INVALID"));
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportRouteCode\":\"E\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_ROUTE_INVALID"));
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportUrl\":\"ftp://report.invalid/upstream\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_URL_INVALID"));
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportPassword\":\"" + "x".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_CREDENTIAL_INVALID"));
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportNetworkCode\":\"" + "N".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_NETWORK_CODE_INVALID"));
    }

    @Test
    void loginRequiresCompleteConfigAndTogglesOnlineState() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);

        // 前置条件不满足：没有类型/网盘/盘口/账号
        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_CONFIG_INCOMPLETE"));
        assertThat(online(machineAId)).isFalse();

        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(fullConfig("A", false, false, REPORT_SECRET)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true))
                .andExpect(jsonPath("$.reportLastLoginAt").isNotEmpty());
        assertThat(online(machineAId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT report_last_login_at FROM agent WHERE id = ?",
                java.sql.Timestamp.class, machineAId)).isNotNull();

        mockMvc.perform(get("/api/admin/player-desk/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true));

        mockMvc.perform(post("/api/admin/player-desk/report/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(false));
        assertThat(online(machineAId)).isFalse();
        // 退出不清空登录时间与配置
        assertThat(jdbc.queryForObject("SELECT report_last_login_at FROM agent WHERE id = ?",
                java.sql.Timestamp.class, machineAId)).isNotNull();
    }

    @Test
    void resubmitMovesStatusByRouteAndEnforcesScope() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(fullConfig("A", false, false, REPORT_SECRET)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        long accountA = createPlayer(token, "报网验证玩家A");
        long acceptedBet = insertBet(accountA, "reportconsole-bet-accepted", "PENDING");

        // 盘口 A → 适配器接受 → REPORTED
        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + acceptedBet + "/resubmit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.betId").value((int) acceptedBet))
                .andExpect(jsonPath("$.reportStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.config.machineId").value((int) machineAId));
        assertThat(reportStatusOf(acceptedBet)).isEqualTo("ACCEPTED");

        // 盘口 D → 适配器超时 → TIMEOUT（超时不退款、不删单，只标记）
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reportRouteCode\":\"D\"}"))
                .andExpect(status().isOk());
        long timeoutBet = insertBet(accountA, "reportconsole-bet-timeout", "PENDING");
        mockMvc.perform(post("/api/admin/player-desk/report/orders/" + timeoutBet + "/resubmit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("TIMEOUT"));
        assertThat(reportStatusOf(timeoutBet)).isEqualTo("TIMEOUT");
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
    void submitForBetOnlyReportsWhenAutoAndOnline() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        long accountA = createPlayer(token, "自动上报玩家");
        long betId = insertBet(accountA, "reportconsole-bet-auto", "PENDING");

        // 自动上报开、但机器离线 → 不提交，状态不变
        mockMvc.perform(put("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(fullConfig("A", true, false, REPORT_SECRET)))
                .andExpect(status().isOk());
        assertThat(reportService.submitForBet(betId)).isEmpty();
        assertThat(reportStatusOf(betId)).isEqualTo("PENDING");

        // 本地模拟登录后 → 自动上报成功，并且重复调用是幂等的
        mockMvc.perform(post("/api/admin/player-desk/report/login").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertThat(reportService.submitForBet(betId)).contains("ACCEPTED");
        assertThat(reportStatusOf(betId)).isEqualTo("ACCEPTED");
        assertThat(reportService.submitForBet(betId)).isEmpty();

        // 不存在的注单直接跳过，不抛异常
        assertThat(reportService.submitForBet(999999999L)).isEmpty();
    }

    private static String fullConfig(String routeCode, boolean auto, boolean withdraw, String reportPassword) {
        return "{\"reportType\":\"TT\",\"reportAuto\":" + auto + ",\"reportWithdraw\":" + withdraw
                + ",\"reportUsername\":\"feidan-a\",\"reportPassword\":\"" + reportPassword + "\","
                + "\"reportUrl\":\"" + REPORT_URL + "\",\"reportNetworkCode\":\"NET_A\","
                + "\"reportRouteCode\":\"" + routeCode + "\"}";
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
                        (bet_id, agent_id, attempt_no, status, idempotency_key, sent_at, resolved_at)
                    SELECT ?, a.agent_id, 1, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                      FROM demo_user_account a WHERE a.id = ?
                    """, betId, reportStatus, "SEED-" + betCode, accountId);
        }
        return betId;
    }

    private String reportStatusOf(long betId) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT o.status FROM report_upstream_order o
                                  WHERE o.bet_id = b.id ORDER BY o.id DESC LIMIT 1), 'PENDING')
                  FROM game_bet b WHERE b.id = ?
                """, String.class, betId);
    }

    private boolean online(long machineId) {
        Boolean value = jdbc.queryForObject("SELECT report_online FROM agent WHERE id = ?", Boolean.class, machineId);
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
        if (!accounts.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
        }
        delete("DELETE FROM game_bet WHERE bet_code LIKE 'reportconsole-%'");
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
