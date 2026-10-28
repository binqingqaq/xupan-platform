package com.xupan.server.platformadmin.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.platformadmin.service.GameSettingsService;
import com.xupan.server.platformadmin.service.PlatformOnlinePlayerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAdminResourceControllerTest {

    private static final String ADMIN = "platform-admin-test-admin";
    private static final String PASSWORD = "AdminPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private PlatformOnlinePlayerService onlinePlayerService;

    @Autowired
    private GameSettingsService gameSettingsService;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "平台管理测试员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void createsBy220SubAccountAndMachineThenReadsMachinePlayers() throws Exception {
        String token = login(ADMIN, PASSWORD);

        MvcResult subResult = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_sub","rawPassword":"SubPassword123",
                                 "score":1000,"subAccountManage":true,
                                 "machineManage":true,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("platform_test_sub"))
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andReturn();
        long subId = ((Number) JsonPath.read(subResult.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM agent_group WHERE id=?", String.class, subId))
                .isEqualTo("platform_test_sub");

        mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_machine\",\"rawPassword\":\"MachinePassword123\","
                                + "\"groupId\":" + subId
                                + ",\"score\":500,\"boardOpen\":true,\"robotManage\":true,\"chaseEnabled\":false,"
                                + "\"botCount\":2,\"closeSeconds\":10,\"cancelSeconds\":5,\"rebateRate\":0.1,"
                                + "\"oddsRate\":1.95,\"specialRebateRate\":0.2,\"specialOddsRate\":18,"
                                + "\"totalLimit\":10000,\"positiveLimit\":1000,\"angleLimit\":1000,"
                                + "\"strictLimit\":1000,\"tongLimit\":1000,\"carLimit\":1000,"
                                + "\"specialLimit\":1000,\"oddEvenLimit\":1000,\"bigSmallLimit\":1000,"
                                + "\"fanLimit\":1000,\"addLimit\":1000,\"playerMaxStake\":500,"
                                + "\"playerMinStake\":10,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountUsername").value("platform_test_machine"))
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.groupUsername").value("platform_test_sub"))
                .andExpect(jsonPath("$.requestedBotCount").value(2));
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM agent WHERE agent_code='PLATFORM_TEST_MACHINE'", String.class))
                .isEqualTo("platform_test_machine");

        mockMvc.perform(get("/api/admin/sub-accounts").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].machineCount").value(1));
        MvcResult machines = mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].accountUsername").value("platform_test_machine"))
                .andReturn();
        long machineId = ((Number) JsonPath.read(machines.getResponse().getContentAsString(), "$[0].id")).longValue();
        mockMvc.perform(get("/api/admin/machines/" + machineId + "/players")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        MvcResult otherSubResult = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_sub_other","rawPassword":"SubPassword456",
                                 "displayName":"其他测试子账号","score":1000,"subAccountManage":true,
                                 "machineManage":true,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long otherSubId = ((Number) JsonPath.read(otherSubResult.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_machine_other\",\"rawPassword\":\"MachinePassword456\","
                                + "\"displayName\":\"其他测试机器\",\"groupId\":" + otherSubId
                                + ",\"score\":500,\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                                + "\"botCount\":0,\"closeSeconds\":10,\"cancelSeconds\":5,\"rebateRate\":0.1,"
                                + "\"oddsRate\":1.95,\"specialRebateRate\":0.2,\"specialOddsRate\":18,"
                                + "\"totalLimit\":10000,\"positiveLimit\":1000,\"angleLimit\":1000,"
                                + "\"strictLimit\":1000,\"tongLimit\":1000,\"carLimit\":1000,"
                                + "\"specialLimit\":1000,\"oddEvenLimit\":1000,\"bigSmallLimit\":1000,"
                                + "\"fanLimit\":1000,\"addLimit\":1000,\"playerMaxStake\":500,"
                                + "\"playerMinStake\":10,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk());

        String subToken = login("platform_test_sub", "SubPassword123");
        mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(subToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountUsername").value("platform_test_machine"));
        mockMvc.perform(get("/api/admin/reports/profit")
                        .header("Authorization", bearer(subToken)).param("day", "2026-10-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines.length()").value(1))
                .andExpect(jsonPath("$.machines[0].machineId").value(machineId));
        mockMvc.perform(get("/api/admin/sub-accounts").header("Authorization", bearer(subToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/draw-history").header("Authorization", bearer(subToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mockMvc.perform(get("/api/admin/reports/score-flow")
                        .header("Authorization", bearer(token)).param("day", "2026-10-02"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/reports/profit")
                        .header("Authorization", bearer(token)).param("day", "2026-10-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.day").value("2026-10-02"));
        mockMvc.perform(get("/api/admin/draw-history")
                        .header("Authorization", bearer(token)).param("pageSize", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    }

    /**
     * 回归：机器彩种（BY220 {@code x_robot.game}）必须能回显，且不指定彩种时不能清空已有配置。
     *
     * <p>以前 {@code updateMachine} 无条件 {@code DELETE FROM agent_game} 后只回插请求里的彩种，
     * 配合前端硬编码 {@code games:["AU8"]}，编辑机器任何字段都会把彩种清成只剩 AU8（静默丢数据）。</p>
     */
    @Test
    void keepsMachineGamesWhenUpdatingWithoutGamesField() throws Exception {
        String token = login(ADMIN, PASSWORD);
        String base = "{\"rawPassword\":\"MachineGamesPassword1\",\"displayName\":\"彩种测试机器\","
                + "\"score\":500,\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                + "\"botCount\":0,\"closeSeconds\":10,\"cancelSeconds\":5,\"rebateRate\":0.1,"
                + "\"oddsRate\":1.95,\"specialRebateRate\":0.2,\"specialOddsRate\":18,"
                + "\"totalLimit\":10000,\"positiveLimit\":1000,\"angleLimit\":1000,"
                + "\"strictLimit\":1000,\"tongLimit\":1000,\"carLimit\":1000,"
                + "\"specialLimit\":1000,\"oddEvenLimit\":1000,\"bigSmallLimit\":1000,"
                + "\"fanLimit\":1000,\"addLimit\":1000,\"playerMaxStake\":500,\"playerMinStake\":10";

        MvcResult created = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(base + ",\"username\":\"platform_test_games_machine\",\"games\":[\"AU8\",\"K28\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games", containsInAnyOrder("AU8", "K28")))
                .andReturn();
        long machineId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // 不传 games（等价于前端只改其它字段）：必须保留原有彩种
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(base + ",\"username\":\"platform_test_games_machine\",\"displayName\":\"改名后的机器\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games", containsInAnyOrder("AU8", "K28")));

        MvcResult listed = mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        List<List<String>> listedGames = JsonPath.read(listed.getResponse().getContentAsString(),
                "$[?(@.id == " + machineId + ")].games");
        assertThat(listedGames).hasSize(1);
        assertThat(listedGames.get(0)).containsExactlyInAnyOrder("AU8", "K28");

        // 账本原则 2：机器积分改动必须落流水。
        // 创建时 score=500 → 一条 MACHINE_INIT（0 → 500）
        assertThat(machineLedgerCount(machineId)).isEqualTo(1L);
        assertThat(machineLedgerType(machineId)).isEqualTo("MACHINE_INIT");
        assertThat(machineLedgerBalanceAfter(machineId)).isEqualByComparingTo("500.00");
        // 上面那次编辑没改 score（仍是 500）→ 不得凭空多一条流水
        assertThat(machineLedgerCount(machineId)).as("积分未变不应写流水").isEqualTo(1L);

        // 把积分改成 800 → 追加一条 MACHINE_ADJUST，变动前后可验算
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(base.replace("\"score\":500", "\"score\":800")
                                + ",\"username\":\"platform_test_games_machine\"}"))
                .andExpect(status().isOk());
        assertThat(machineLedgerCount(machineId)).isEqualTo(2L);
        assertThat(machineLedgerType(machineId)).isEqualTo("MACHINE_ADJUST");
        assertThat(machineLedgerBalanceBefore(machineId)).isEqualByComparingTo("500.00");
        assertThat(machineLedgerBalanceAfter(machineId)).isEqualByComparingTo("800.00");
        assertThat(machineLedgerAmount(machineId)).isEqualByComparingTo("300.00");

        // 显式传空列表表示"清空彩种"，此时才真的清空
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(base + ",\"username\":\"platform_test_games_machine\",\"games\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games").isEmpty());
    }

    @Test
    void rejectsStaleMachineEditAndPreservesTheFirstConfigurationSave() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineCreateBody("platform_test_version_machine", "版本竞争机器", null, "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").isNumber())
                .andReturn();
        long machineId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        int staleVersion = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.configVersion")).intValue();
        String base = "{\"username\":\"platform_test_version_machine\",\"groupId\":null,\"score\":0,"
                + "\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,\"botCount\":0,"
                + "\"closeSeconds\":10,\"cancelSeconds\":5,\"rebateRate\":0,\"oddsRate\":1.95,"
                + "\"specialRebateRate\":0,\"specialOddsRate\":18,\"totalLimit\":10000,"
                + "\"positiveLimit\":1000,\"angleLimit\":1000,\"strictLimit\":1000,\"tongLimit\":1000,"
                + "\"carLimit\":1000,\"specialLimit\":1000,\"oddEvenLimit\":1000,\"bigSmallLimit\":1000,"
                + "\"fanLimit\":1000,\"addLimit\":1000,\"playerMaxStake\":500,\"playerMinStake\":10,"
                + "\"games\":[\"AU8\"]}";

        mockMvc.perform(put("/api/admin/machines/" + machineId)
                        .queryParam("expectedConfigVersion", String.valueOf(staleVersion))
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(base.replace("\"oddsRate\":1.95", "\"oddsRate\":7.77")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(staleVersion + 1))
                .andExpect(jsonPath("$.oddsRate").value(7.77));

        mockMvc.perform(put("/api/admin/machines/" + machineId)
                        .queryParam("expectedConfigVersion", String.valueOf(staleVersion))
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(base.replace("\"oddsRate\":1.95", "\"oddsRate\":8.88")
                                .replace("\"totalLimit\":10000", "\"totalLimit\":12000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MACHINE_CONFIG_VERSION_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject("SELECT odds_rate FROM agent WHERE id=?", java.math.BigDecimal.class, machineId))
                .isEqualByComparingTo("7.7700");
        assertThat(jdbcTemplate.queryForObject("SELECT total_limit FROM agent WHERE id=?", java.math.BigDecimal.class, machineId))
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void subAccountEndpointsOmitLegacyReportConfiguration() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_subaccount_report_removed","rawPassword":"SubAccountPassword1",
                                 "score":1000,"subAccountManage":false,"machineManage":true,
                                 "unifiedReportEnabled":true,"reportUsername":"legacy-user",
                                 "reportNetworkCode":"K28","reportType":"K28"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subAccountManage").value(false))
                .andExpect(jsonPath("$.machineManage").value(true))
                .andExpect(jsonPath("$.reportUsername").doesNotExist())
                .andExpect(jsonPath("$.reportType").doesNotExist())
                .andExpect(jsonPath("$.reportUrl").doesNotExist())
                .andExpect(jsonPath("$.reportOnline").doesNotExist())
                .andReturn();
        long subId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/api/admin/sub-accounts").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + subId + ")].reportUsername").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == " + subId + ")].reportNetworkCode").doesNotExist());

        mockMvc.perform(put("/api/admin/sub-accounts/" + subId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_subaccount_report_removed_renamed","rawPassword":"",
                                 "score":1000,"subAccountManage":true,"machineManage":false,
                                 "unifiedReportEnabled":true,"reportUsername":"legacy-user",
                                 "reportNetworkCode":"K28","reportUrl":"https://old.invalid"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("platform_test_subaccount_report_removed_renamed"))
                .andExpect(jsonPath("$.subAccountManage").value(true))
                .andExpect(jsonPath("$.reportNetworkCode").doesNotExist());

        assertThat(jdbcTemplate.queryForObject("SELECT report_username FROM agent_group WHERE id = ?",
                String.class, subId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT report_url FROM agent_group WHERE id = ?",
                String.class, subId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT unified_report_enabled FROM agent_group WHERE id = ?",
                Boolean.class, subId)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM agent_group WHERE id = ?",
                String.class, subId)).isEqualTo("platform_test_subaccount_report_removed_renamed");
        assertThat(jdbcTemplate.queryForObject("SELECT u.display_name FROM sys_user u JOIN agent_group g "
                        + "ON g.sys_user_id = u.id WHERE g.id = ?", String.class, subId))
                .isEqualTo("platform_test_subaccount_report_removed_renamed");
    }

    @Test
    void forceSettlesPendingDrawHistoryWithWalletAndAudit() throws Exception {
        long playerUserId = userRepository.insert("platform-admin-test-draw-player", "历史强制结算玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_DRAW_PLAYER', '历史强制结算玩家', 100.00, 'ACTIVE', ?, 1,
                        'REAL', 'NORMAL', 'V-DRAW-FORCE-001')
                """, playerUserId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, issue_started_at, opened_at,
                     closed_at, settled_at, draw_result_version, draw_confirmed_at, draw_confirmed_by)
                VALUES ('39999946', 'CLOSED', 'SETTLED', 1, 2, 3, 4, 5, 6, 7, 18,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        1, CURRENT_TIMESTAMP,
                        (SELECT id FROM sys_user WHERE username = 'platform-admin-test-admin'))
                """);
        long betId = insertAndReturnId("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, 'PLATFORM_TEST_DRAW_FORCE_BET', 'platform-test-draw-force',
                        '39999946', 1, 'FAN', '1', 10.00, 3.850)
                """, accountId);
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/draw-history")
                        .header("Authorization", bearer(token)).param("issueNumber", "39999946"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].pendingBetCount").value(1));
        mockMvc.perform(post("/api/admin/draw-history/force-settle-all")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"confirm\":\"FORCE_SETTLE_ALL\",\"preview\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(true))
                .andExpect(jsonPath("$.histories").value(1))
                .andExpect(jsonPath("$.orders").value(1));

        mockMvc.perform(post("/api/admin/draw-history/39999946/force-settle")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId)).isEqualTo("WIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("138.50");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE related_bet_id = ? AND operation_type = 'SETTLEMENT_CREDIT'
                """, Long.class, betId)).isEqualTo(1);

        mockMvc.perform(post("/api/admin/draw-history/39999946/force-settle")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(0));
        mockMvc.perform(post("/api/admin/draw-history/force-settle-all")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"confirm\":\"FORCE_SETTLE_ALL\",\"preview\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories").value(0))
                .andExpect(jsonPath("$.orders").value(0));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE permission_code = 'DRAW_HISTORY_FORCE_SETTLE' AND resource_id = '39999946'
                """, Long.class)).isEqualTo(2);
    }

    @Test
    void confirmsDrawDraftSettlesBothLayersAndAllowsSafeRetry() throws Exception {
        long playerUserId = userRepository.insert("platform-admin-test-confirm-player", "开奖确认测试玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_CONFIRM_PLAYER', '开奖确认测试玩家', 100.00, 'ACTIVE', ?, 1,
                        'REAL', 'NORMAL', 'V-DRAW-CONFIRM-001')
                """, playerUserId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        jdbcTemplate.update("""
                INSERT INTO game_issue (issue_number, status, phase, issue_started_at)
                VALUES ('39999999', 'CLOSED', 'DRAWING', CURRENT_TIMESTAMP - INTERVAL '10' MINUTE)
                """);
        long betId = insertAndReturnId("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, 'PLATFORM_TEST_DRAW_CONFIRM_BET', 'platform-test-draw-confirm',
                        '39999999', 1, 'FAN', '1', 10.00, 3.850)
                """, accountId);
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(put("/api/admin/draw-history/AU8/39999999/draft")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(1));
        mockMvc.perform(post("/api/admin/draw-history/AU8/39999999/confirm")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(2))
                .andExpect(jsonPath("$.localOrders").value(1))
                .andExpect(jsonPath("$.centerOrders").value(0));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId)).isEqualTo("WIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("138.50");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM game_issue_event
                 WHERE game_code = 'AU8' AND issue_number = '39999999' AND event_type = 'DRAW_RESULT'
                """, Long.class)).isEqualTo(1);

        mockMvc.perform(post("/api/admin/draw-history/AU8/39999999/confirm")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(2))
                .andExpect(jsonPath("$.localOrders").value(0));
        mockMvc.perform(put("/api/admin/draw-history/AU8/39999999/draft")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"numbers\":[2,2,3,4,5,6,7,18]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAW_RESULT_LOCKED"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE related_bet_id = ? AND operation_type = 'SETTLEMENT_CREDIT'
                """, Long.class, betId)).isEqualTo(1);
    }

    @Test
    void forceSettlementPaysWinningSpecialSelectionAtItsUnitStake() throws Exception {
        long playerUserId = userRepository.insert("platform-admin-test-special-draw-player", "特码强制结算玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_SPECIAL_DRAW_PLAYER', '特码强制结算玩家', 0.00, 'ACTIVE', ?, 1,
                        'REAL', 'NORMAL', 'V-DRAW-SPECIAL-001')
                """, playerUserId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, issue_started_at, opened_at,
                     closed_at, settled_at, draw_result_version, draw_confirmed_at, draw_confirmed_by)
                VALUES ('39999947', 'CLOSED', 'SETTLED', 1, 2, 3, 4, 5, 6, 7, 18,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        1, CURRENT_TIMESTAMP,
                        (SELECT id FROM sys_user WHERE username = 'platform-admin-test-admin'))
                """);
        long betId = insertAndReturnId("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, 'PLATFORM_TEST_SPECIAL_DRAW_BET', 'platform-test-special-draw',
                        '39999947', 1, 'SPECIAL', '1,2', 200.00, 18.00)
                """, accountId);
        jdbcTemplate.update("""
                INSERT INTO game_bet_selection
                    (bet_id, selection_no, selection_key, quota_bucket, number_value, unit_stake)
                VALUES (?, 1, '1特', 'SPECIAL_NUMBER', 1, 100.00),
                       (?, 2, '2特', 'SPECIAL_NUMBER', 2, 100.00)
                """, betId, betId);

        String token = login(ADMIN, PASSWORD);
        mockMvc.perform(post("/api/admin/draw-history/39999947/force-settle")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId)).isEqualTo("WIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT net_profit FROM game_bet WHERE id = ?", java.math.BigDecimal.class, betId))
                .isEqualByComparingTo("1600.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("1800.00");
    }

    @Test
    void supplementsMissingDrawIssueAndSettlesPendingBet() throws Exception {
        long playerUserId = userRepository.insert("platform-admin-test-supplement-player", "补期测试玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_SUPPLEMENT_PLAYER', '补期测试玩家', 100.00, 'ACTIVE', ?, 1,
                        'REAL', 'NORMAL', 'V-SUPPLEMENT-001')
                """, playerUserId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        long betId = insertAndReturnId("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, 'PLATFORM_TEST_SUPPLEMENT_BET', 'platform-test-supplement',
                        '39999948', 1, 'FAN', '1', 10.00, 3.850)
                """, accountId);
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(post("/api/admin/draw-history/supplement")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"gameCode\":\"AU8\",\"issueNumber\":\"39999948\",\"numbers\":[1,2,3,4,5,6,7,18],"
                                + "\"openedAt\":\"2026-10-02T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.orders").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId)).isEqualTo("WIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("138.50");

        mockMvc.perform(post("/api/admin/draw-history/supplement")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"gameCode\":\"AU8\",\"issueNumber\":\"39999948\",\"numbers\":[1,2,3,4,5,6,7,18],"
                                + "\"openedAt\":\"2026-10-02T01:00:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAW_HISTORY_ALREADY_DRAWN"));
    }

    @Test
    void filtersDrawHistoryByGameAndSupplementsSelectedGame() throws Exception {
        long gameId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM game_definition", Long.class);
        jdbcTemplate.update("""
                INSERT INTO game_definition
                    (id, game_code, display_name, ball_indexes, sort_order, algorithm, switch_enabled,
                     special_enabled, special_model, keyboard_enabled, status)
                VALUES (?, 'PLATFORM_TEST_MULTI', '后台多彩种测试', '1,2,3,4,5,6,7,8', 99, 'SUM', FALSE,
                        TRUE, 'MODEL_ONE', TRUE, 'ACTIVE')
                """, gameId);
        long playerUserId = userRepository.insert("platform-admin-test-multi-player", "多彩种补期玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_MULTI_PLAYER', '多彩种补期玩家', 100.00, 'ACTIVE', ?, 1,
                        'REAL', 'NORMAL', 'V-MULTI-001')
                """, playerUserId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        long betId = insertAndReturnId("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES ('PLATFORM_TEST_MULTI', ?, 'PLATFORM_TEST_MULTI_BET', 'platform-test-multi',
                        '39999971', 1, 'FAN', '1', 10.00, 3.850, 'PENDING')
                """, accountId);
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(post("/api/admin/draw-history/supplement")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"gameCode\":\"PLATFORM_TEST_MULTI\",\"issueNumber\":\"39999971\","
                                + "\"numbers\":[1,2,3,4,5,6,7,18],\"openedAt\":\"2026-10-02T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.orders").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT game_code FROM game_issue WHERE issue_number = '39999971'", String.class))
                .isEqualTo("PLATFORM_TEST_MULTI");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId)).isEqualTo("WIN");

        mockMvc.perform(get("/api/admin/draw-history")
                        .header("Authorization", bearer(token)).param("gameCode", "PLATFORM_TEST_MULTI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].gameCode").value("PLATFORM_TEST_MULTI"))
                .andExpect(jsonPath("$.items[0].gameName").value("后台多彩种测试"))
                .andExpect(jsonPath("$.items[0].issueNumber").value("39999971"))
                .andExpect(jsonPath("$.items[0].betCount").value(1));
        mockMvc.perform(get("/api/admin/draw-history")
                        .header("Authorization", bearer(token)).param("gameCode", "AU8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void reportsBy220SingleDoubleFlowAndRebate() throws Exception {
        ReportFixture fixture = createReportFixture("win");
        long accountId = fixture.accountId();
        // 番赢单：双边流水取本金（BY220 common.php:3919-3920 else 分支），不是 3 倍
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_FAN", "FAN", "1", "10.00", "3.850",
                "28.50", "WIN", "1.0000", "0.0000");
        // 车赢单：双边流水 = 本金/3（BY220 common.php:3914 totalYing = score/3）
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_CAR_WIN", "CAR", "1,2,4", "300.00", "1.950",
                "95.00", "WIN", "1.0000", "0.0000");
        // 通赢单：双边流水 = 本金/2（BY220 common.php:3916）
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_TONG_WIN", "TONG", "1,2,3", "200.00", "1.950",
                "95.00", "WIN", "1.0000", "0.0000");
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/reports/profit")
                        .header("Authorization", bearer(token))
                        .param("day", "2026-10-02").param("subAccountId", Long.toString(fixture.groupId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines[0].totalFlow").value(510.00))
                .andExpect(jsonPath("$.machines[0].totalSingleFlow").value(218.50))
                // 双边流水：番 10.00 + 车 300/3=100.00 + 通 200/2=100.00
                .andExpect(jsonPath("$.machines[0].totalDoubleFlow").value(210.00))
                .andExpect(jsonPath("$.machines[0].totalProfit").value(218.50))
                // BY220先将1.5/100按两位小数截断为0.01，再乘番净赢28.50，结果为0.28。
                .andExpect(jsonPath("$.machines[0].totalFanShui").value(2.18))
                // 双边返水：番 10×1% = 0.10，车 100×1% = 1.00，通 100×1% = 1.00
                .andExpect(jsonPath("$.machines[0].rebateDouble").value(2.10))
                .andExpect(jsonPath("$.totalDoubleFlow").value(210.00))
                .andExpect(jsonPath("$.totalFanShui").value(2.18))
                .andExpect(jsonPath("$.totalRebateDouble").value(2.10))
                // 默认单边口径：生效返水取单边返水
                .andExpect(jsonPath("$.machines[0].rebateMode").value("SINGLE"))
                .andExpect(jsonPath("$.machines[0].effectiveRebate").value(2.18))
                .andExpect(jsonPath("$.totalEffectiveRebate").value(2.18));
    }

    @Test
    void reportsEffectiveRebateFollowsMachineRebateMode() throws Exception {
        ReportFixture fixture = createReportFixture("mode");
        long accountId = fixture.accountId();
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_MODE_FAN", "FAN", "1", "10.00", "3.850",
                "28.50", "WIN", "1.0000", "0.0000");
        // 双边口径：生效返水改为双边返水（BY220 x_robot.ls）
        jdbcTemplate.update("UPDATE agent SET rebate_mode = 'DOUBLE' WHERE id = ?", fixture.machineId());
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/reports/profit")
                        .header("Authorization", bearer(token))
                        .param("day", "2026-10-02").param("subAccountId", Long.toString(fixture.groupId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines[0].rebateMode").value("DOUBLE"))
                .andExpect(jsonPath("$.machines[0].totalFanShui").value(0.28))
                .andExpect(jsonPath("$.machines[0].rebateDouble").value(0.10))
                .andExpect(jsonPath("$.machines[0].effectiveRebate").value(0.10))
                .andExpect(jsonPath("$.totalEffectiveRebate").value(0.10));
    }

    @Test
    void reportsBy220LoseFlowAndTongRebateHalving() throws Exception {
        ReportFixture fixture = createReportFixture("lose");
        long accountId = fixture.accountId();
        // 车输单：双边流水 = 本金（BY220 common.php:4444-4447），双边返水基数 = 本金（common.php:3935）
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_CAR_LOSE", "CAR", "1,2,4", "300.00", "1.950",
                "-300.00", "LOSE", "1.0000", "0.0000");
        // 通输单：双边流水 = 本金（4444-4447 直接累加 score），但双边返水基数 = 本金/2（common.php:3938）
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_TONG_LOSE", "TONG", "1,2,3", "200.00", "1.950",
                "-200.00", "LOSE", "1.0000", "0.0000");
        // 3 参数无 = BY220 $tong 里的「12无3」（common.php:3909）：双边流水 = 本金，双边返水基数 = 本金/2
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_NONE_TONG_LOSE", "NONE", "1,2,3", "200.00", "1.950",
                "-200.00", "LOSE", "1.0000", "0.0000");
        // 2 参数无 = BY220 $wanfaArr 里的「1无2」三位无（common.php:3910）：双边流水和双边返水基数都取本金
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_NONE_LOSE", "NONE", "1,2", "200.00", "1.950",
                "-200.00", "LOSE", "1.0000", "0.0000");
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/reports/profit")
                        .header("Authorization", bearer(token))
                        .param("day", "2026-10-02").param("subAccountId", Long.toString(fixture.groupId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines[0].totalFlow").value(900.00))
                .andExpect(jsonPath("$.machines[0].totalSingleFlow").value(0.00))
                // 双边流水：败单一律全额本金（300 + 200 + 200 + 200）
                .andExpect(jsonPath("$.machines[0].totalDoubleFlow").value(900.00))
                .andExpect(jsonPath("$.machines[0].totalProfit").value(-900.00))
                // 单边返水只算赢单，输单为 0
                .andExpect(jsonPath("$.machines[0].totalFanShui").value(0.00))
                // 双边返水：车 300×1% = 3.00，通 200/2×1% = 1.00，3 参数无 200/2×1% = 1.00，2 参数无 200×1% = 2.00
                .andExpect(jsonPath("$.machines[0].rebateDouble").value(7.00))
                .andExpect(jsonPath("$.totalRebateDouble").value(7.00));
    }

    @Test
    void reportsBy220SpecialAndDrawRules() throws Exception {
        ReportFixture fixture = createReportFixture("special");
        long accountId = fixture.accountId();
        // 特码赢单：单边流水 = 本金×特码赔率；单边返水 = 本金×特码返水且不除 100（common.php:4410）；
        // 双边流水 = 本金（getShuang else 分支）；双边返水 = 本金×赔率×普通返水/100（common.php:4426 用 totalYing）
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_SPECIAL_WIN", "SPECIAL", "7", "50.00", "18.000",
                "850.00", "WIN", "1.0000", "0.1000");
        // 特码输单：双边流水仍取本金（4444-4447），但 totalYing 输单为 0（common.php:4126），双边返水 = 0
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_SPECIAL_LOSE", "SPECIAL", "8", "50.00", "18.000",
                "-50.00", "LOSE", "1.0000", "0.1000");
        // 特码和单：双边流水与双边返水都不计（common.php:4424 排除 ying==3，4444-4447 只处理 ying==0）
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_SPECIAL_DRAW", "SPECIAL", "9", "20.00", "18.000",
                "0.00", "DRAW", "1.0000", "0.1000");
        // 番和单：双边流水与双边返水都不计
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_FAN_DRAW", "FAN", "1", "30.00", "3.850",
                "0.00", "DRAW", "1.0000", "0.0000");
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/reports/profit")
                        .header("Authorization", bearer(token))
                        .param("day", "2026-10-02").param("subAccountId", Long.toString(fixture.groupId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines[0].totalFlow").value(150.00))
                // 单边流水只取特码赢单：50 × 18.000
                .andExpect(jsonPath("$.machines[0].totalSingleFlow").value(900.00))
                // 双边流水：特码赢单 50.00 + 特码输单 50.00，两个和单不计
                .andExpect(jsonPath("$.machines[0].totalDoubleFlow").value(100.00))
                .andExpect(jsonPath("$.machines[0].totalProfit").value(800.00))
                // 单边返水：特码赢单 50 × 0.1000 = 5.00（不除 100）
                .andExpect(jsonPath("$.machines[0].totalFanShui").value(5.00))
                // 双边返水：特码赢单 50 × 18.000 × 1% = 9.00
                .andExpect(jsonPath("$.machines[0].rebateDouble").value(9.00))
                .andExpect(jsonPath("$.totalRebateDouble").value(9.00));
    }
    @Test
    void listsAndSafelyDeletesUnsettledOrder() throws Exception {
        long groupId = insertAndReturnId("""
                INSERT INTO agent_group (group_code, display_name, status, username)
                VALUES ('PLATFORM_TEST_UNSETTLED_GROUP', '未结订单测试子账号', 'ACTIVE', 'platform_test_unsettled')
                """);
        long machineId = insertAndReturnId("""
                INSERT INTO agent (agent_code, display_name, group_id, system_owned, status)
                VALUES ('PLATFORM_TEST_UNSETTLED_MACHINE', '未结订单测试机器', ?, TRUE, 'ACTIVE')
                """, groupId);
        long playerUserId = userRepository.insert("platform-admin-test-player", "未结订单玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_UNSETTLED_PLAYER', '未结订单玩家', 1000.00, 'ACTIVE', ?, ?,
                        'REAL', 'NORMAL', 'V-UNSETTLED-001')
                """, playerUserId, machineId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        long betId = insertAndReturnId("""
                INSERT INTO game_bet
                    (user_id, bet_code, issue_number, ball_number, play_type, parameters_text,
                     stake, odds_snapshot)
                VALUES (?, 'PLATFORM_TEST_UNSETTLED_BET', '3006154', 1, 'FAN', '1', 100.00, 3.850)
                """, accountId);

        String token = login(ADMIN, PASSWORD);
        mockMvc.perform(get("/api/admin/unsettled-orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(betId))
                .andExpect(jsonPath("$.items[0].subAccount").value("platform_test_unsettled"))
                .andExpect(jsonPath("$.items[0].machineName").value("未结订单测试机器"))
                .andExpect(jsonPath("$.items[0].memberCode").value("V-UNSETTLED-001"))
                .andExpect(jsonPath("$.items[0].playerName").value("未结订单玩家"))
                .andExpect(jsonPath("$.items[0].command").value("1番100"))
                .andExpect(jsonPath("$.items[0].reportStatus").value("PENDING"));

        mockMvc.perform(delete("/api/admin/unsettled-orders/" + betId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
        // BY220 未结订单删除是物理删除注单行：注单不再存在，退款流水与审计保留。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE id = ?", Long.class, betId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT amount FROM demo_balance_ledger
                 WHERE related_bet_id = ? AND operation_type = 'SETTLEMENT_REVERSAL'
                """, java.math.BigDecimal.class, betId)).isEqualByComparingTo("100.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("1100.00");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE resource_id = ? AND result = 'SUCCESS'
                """, Long.class, Long.toString(betId))).isEqualTo(1);
        mockMvc.perform(get("/api/admin/unsettled-orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        // 注单已物理删除，重复删除不再命中记录。
        mockMvc.perform(delete("/api/admin/unsettled-orders/" + betId).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE permission_code = 'UNSETTLED_ORDER_DELETE' AND resource_id = ?
                """, Long.class, Long.toString(betId))).isEqualTo(1);
    }

    @Test
    void correctsPendingOrderWithWalletIdempotencyAndLimits() throws Exception {
        long groupId = insertAndReturnId("""
                INSERT INTO agent_group (group_code, display_name, status, username)
                VALUES ('PLATFORM_TEST_CORRECTION_GROUP', '改单测试子账号', 'ACTIVE', 'platform_test_correction')
                """);
        long machineId = insertAndReturnId("""
                INSERT INTO agent (agent_code, display_name, group_id, system_owned, status)
                VALUES ('PLATFORM_TEST_CORRECTION_MACHINE', '改单测试机器', ?, TRUE, 'ACTIVE')
                """, groupId);
        long playerUserId = userRepository.insert("platform-admin-test-correction-player", "改单测试玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_CORRECTION_PLAYER', '改单测试玩家', 400.00, 'ACTIVE', ?, ?,
                        'REAL', 'NORMAL', 'V-CORRECTION-001')
                """, playerUserId, machineId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (issue_number, status, phase, issue_started_at, betting_ends_at, draw_ends_at)
                VALUES ('3999991', 'OPEN', 'BETTING', CURRENT_TIMESTAMP, ?, ?)
                """, Timestamp.from(Instant.now().plusSeconds(3600)),
                Timestamp.from(Instant.now().plusSeconds(4200)));
        jdbcTemplate.update("""
                MERGE INTO game_odds (play_type, odds, draw_policy, enabled, version)
                KEY (play_type) VALUES ('ANGLE', 1.950, 'REFUND', TRUE, 1)
                """);
        long betId = insertAndReturnId("""
                INSERT INTO game_bet
                    (user_id, bet_code, issue_number, ball_number, play_type, parameters_text,
                     stake, odds_snapshot)
                VALUES (?, 'PLATFORM_TEST_CORRECTION_BET', '3999991', 1, 'FAN', '1', 100.00, 3.850)
                """, accountId);
        jdbcTemplate.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after, reason,
                     operator_name, related_bet_id, issue_number)
                VALUES (?, 'BET_DEBIT', -100.00, 500.00, 400.00, '测试下注扣款', 'SYSTEM', ?, '3999991')
                """, accountId, betId);

        String token = login(ADMIN, PASSWORD);
        mockMvc.perform(get("/api/admin/order-corrections").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(betId))
                .andExpect(jsonPath("$.items[0].machineName").value("改单测试机器"))
                .andExpect(jsonPath("$.items[0].playerName").value("改单测试玩家"))
                .andExpect(jsonPath("$.items[0].stake").value(100.00))
                .andExpect(jsonPath("$.items[0].command").value("1番100"));
        mockMvc.perform(get("/api/admin/order-corrections/" + betId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.command").value("1番100"))
                .andExpect(jsonPath("$.ballNumber").value(1));

        MvcResult correction = mockMvc.perform(post("/api/admin/order-corrections/" + betId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"command\":\"1番200\",\"ballNumber\":1,\"idempotencyKey\":\"test-correction-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("300.00");
        assertThat(JsonPath.read(correction.getResponse().getContentAsString(), "$.stake").toString()).isEqualTo("200.0");
        assertThat(JsonPath.read(correction.getResponse().getContentAsString(), "$.stakeDelta").toString()).isEqualTo("100.0");
        assertThat(JsonPath.read(correction.getResponse().getContentAsString(), "$.walletBalance").toString()).isEqualTo("300.0");
        assertThat((Object) JsonPath.read(correction.getResponse().getContentAsString(), "$.ledgerId")).isNotNull();
        mockMvc.perform(post("/api/admin/order-corrections/" + betId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"command\":\"1番200\",\"ballNumber\":1,\"idempotencyKey\":\"test-correction-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stake").value(200.00));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE related_bet_id = ? AND operation_type = 'BET_EDIT_DEBIT'
                """, Long.class, betId)).isEqualTo(1);

        mockMvc.perform(post("/api/admin/order-corrections/" + betId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"command\":\"2番100\",\"ballNumber\":2,\"idempotencyKey\":\"test-correction-ball-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playType").value("FAN"))
                .andExpect(jsonPath("$.stakeDelta").value(-100.00))
                .andExpect(jsonPath("$.walletBalance").value(400.00));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ball_number FROM game_bet WHERE id = ?", Integer.class, betId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT new_ball_number FROM game_bet_edit_record
                 WHERE idempotency_key = 'test-correction-ball-2'
                """, Integer.class)).isEqualTo(2);
        mockMvc.perform(post("/api/admin/order-corrections/" + betId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"command\":\"12角100\",\"ballNumber\":1,\"idempotencyKey\":\"test-correction-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playType").value("ANGLE"))
                .andExpect(jsonPath("$.stakeDelta").value(0.00))
                .andExpect(jsonPath("$.walletBalance").value(400.00));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT play_type FROM game_bet WHERE id = ?", String.class, betId)).isEqualTo("ANGLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ball_number FROM game_bet WHERE id = ?", Integer.class, betId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT edit_version FROM game_bet WHERE id = ?", Integer.class, betId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE related_bet_id = ? AND operation_type = 'BET_EDIT_REFUND'
                """, Long.class, betId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet_edit_record WHERE bet_id = ?", Long.class, betId)).isEqualTo(3);
        jdbcTemplate.update("UPDATE game_issue SET phase = 'SETTLED', status = 'CLOSED' WHERE issue_number = '3999991'");
        mockMvc.perform(post("/api/admin/order-corrections/" + betId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"command\":\"3番50\",\"ballNumber\":1,\"idempotencyKey\":\"test-correction-after-draw\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void listsDisconnectsAndMessagesOnlinePlayer() throws Exception {
        long playerUserId = userRepository.insert("platform-admin-test-online-player", "在线测试玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_ONLINE_PLAYER', '在线测试玩家', 250.00, 'ACTIVE', ?, 1,
                        'REAL', 'NORMAL', 'V-ONLINE-001')
                """, playerUserId);
        jdbcTemplate.update("""
                INSERT INTO auth_session
                    (session_id, user_id, access_token_hash, access_expires_at,
                     refresh_token_hash, refresh_expires_at, last_seen_at)
                VALUES ('platform-online-test-session', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, playerUserId, "a".repeat(64), Timestamp.from(Instant.now().plusSeconds(3600)),
                "b".repeat(64), Timestamp.from(Instant.now().plusSeconds(7200)));

        String token = login(ADMIN, PASSWORD);
        MvcResult onlineResult = mockMvc.perform(get("/api/admin/online-players")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> items = JsonPath.read(onlineResult.getResponse().getContentAsString(), "$");
        Map<String, Object> player = items.stream()
                .filter(item -> ((Number) item.get("userId")).longValue() == playerUserId)
                .findFirst().orElseThrow();
        assertThat(player.get("userType")).isEqualTo("玩家");
        assertThat(player.get("displayName")).isEqualTo("在线测试玩家");
        assertThat(((Number) player.get("score")).doubleValue()).isEqualTo(250.0);

        mockMvc.perform(post("/api/admin/online-players/" + playerUserId + "/disconnect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disconnectedSessions").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM auth_session WHERE session_id = 'platform-online-test-session'",
                Timestamp.class)).isNotNull();

        String messageBody = "{\"title\":\"管理员消息\",\"content\":\"测试通知内容\","
                + "\"idempotencyKey\":\"test-online-message-1\"}";
        mockMvc.perform(post("/api/admin/online-players/" + playerUserId + "/messages")
                        .header("Authorization", bearer(token)).contentType("application/json").content(messageBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("管理员消息"))
                .andExpect(jsonPath("$.content").value("测试通知内容"));
        mockMvc.perform(post("/api/admin/online-players/" + playerUserId + "/messages")
                        .header("Authorization", bearer(token)).contentType("application/json").content(messageBody))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM player_admin_notice WHERE recipient_user_id = ?",
                Long.class, playerUserId)).isEqualTo(1);
        PlatformOnlinePlayerService.AdminNoticeView notice = onlinePlayerService
                .listUnreadNotices(playerUserId).get(0);
        assertThat(notice.title()).isEqualTo("管理员消息");
        assertThat(notice.content()).isEqualTo("测试通知内容");
        assertThat(onlinePlayerService.listUnreadNotices(playerUserId)).isEmpty();
    }

    @Test
    void readsUpdatesAndProtectsPlatformSettings() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult beforeResult = mockMvc.perform(get("/api/admin/settings")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        long version = ((Number) JsonPath.read(beforeResult.getResponse().getContentAsString(), "$.version")).longValue();

        String body = "{\"siteTitle\":\"测试群聊标题\",\"announcement\":\"测试公告\","
                + "\"domainLinks\":\"https://example.invalid\",\"chatWarning\":\"测试警告\","
                + "\"information\":\"测试信息\",\"headerEnabled\":false,\"statusBarEnabled\":false,"
                + "\"keyboardMode\":true,\"version\":" + version + "}";
        mockMvc.perform(put("/api/admin/settings")
                        .header("Authorization", bearer(token)).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteTitle").value("测试群聊标题"))
                .andExpect(jsonPath("$.headerEnabled").value(false))
                .andExpect(jsonPath("$.statusBarEnabled").value(false))
                .andExpect(jsonPath("$.keyboardMode").value(true))
                .andExpect(jsonPath("$.version").value(version + 1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM chat_room WHERE room_code = 'main'", String.class))
                .isEqualTo("测试群聊标题");

        mockMvc.perform(get("/api/platform/public-settings").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteTitle").value("测试群聊标题"))
                .andExpect(jsonPath("$.announcement").value("测试公告"))
                .andExpect(jsonPath("$.keyboardMode").value(true));
        mockMvc.perform(put("/api/admin/settings")
                        .header("Authorization", bearer(token)).contentType("application/json").content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @Transactional
    void clearsDataBeforeCutoffWithBy220ConfirmationAndAudit() throws Exception {
        long playerUserId = userRepository.insert("platform_test_clear_player", "清空数据玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_CLEAR_PLAYER', '清空数据玩家', 100.00, 'ACTIVE', ?,
                        'REAL', 'NORMAL', 'TP-CLEAR-PLAYER')
                """, playerUserId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, opened_at, created_at)
                VALUES ('AU8', 'CLEAR-OLD-ISSUE', 'CLOSED', 'SETTLED', 1, 2, 3, 4, 5, 6, 7, 8,
                        '2026-01-01 00:00:00', '2026-01-01 00:00:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, opened_at, created_at)
                VALUES ('AU8', 'CLEAR-NEW-ISSUE', 'CLOSED', 'SETTLED', 1, 2, 3, 4, 5, 6, 7, 8,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status, created_at)
                VALUES ('AU8', ?, 'CLEAR-OLD-BET', 'platform-test-clear-old', 'CLEAR-OLD-ISSUE', 1,
                        'FAN', '1', 10.00, 3.850, 'WIN', '2026-01-01 01:00:00')
                """, accountId);
        jdbcTemplate.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status, created_at)
                VALUES ('AU8', ?, 'CLEAR-NEW-BET', 'platform-test-clear-new', 'CLEAR-NEW-ISSUE', 1,
                        'FAN', '1', 10.00, 3.850, 'WIN', CURRENT_TIMESTAMP)
                """, accountId);
        jdbcTemplate.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after, reason,
                     operator_name, created_at)
                VALUES (?, 'ADMIN_GRANT', 10.00, 0.00, 10.00, '清空测试旧流水', '测试管理员',
                        '2026-01-01 02:00:00')
                """, accountId);
        jdbcTemplate.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after, reason,
                     operator_name, created_at)
                VALUES (?, 'ADMIN_GRANT', 10.00, 10.00, 20.00, '清空测试新流水', '测试管理员',
                        CURRENT_TIMESTAMP)
                """, accountId);
        long adminUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
        jdbcTemplate.update("""
                INSERT INTO sys_login_log (username_snapshot, user_id, result, created_at)
                VALUES (?, ?, 'SUCCESS', '2026-01-01 03:00:00')
                """, ADMIN, adminUserId);
        jdbcTemplate.update("""
                INSERT INTO sys_login_log (username_snapshot, user_id, result, created_at)
                VALUES (?, ?, 'SUCCESS', CURRENT_TIMESTAMP)
                """, ADMIN, adminUserId);

        String token = login(ADMIN, PASSWORD);
        mockMvc.perform(post("/api/admin/settings/clear-data")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"time\":\"2026-07-01T00:00:00Z\",\"confirm\":\"WRONG\",\"preview\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLATFORM_CLEAR_DATA_CONFIRM_REQUIRED"));

        mockMvc.perform(post("/api/admin/settings/clear-data")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"time\":\"2026-07-01T00:00:00Z\",\"confirm\":\"CONFIRM_CLEAR\",\"preview\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(true))
                .andExpect(jsonPath("$.counts.orders").value(1))
                .andExpect(jsonPath("$.counts.drawIssues").value(1))
                .andExpect(jsonPath("$.counts.balanceLedger").value(1))
                .andExpect(jsonPath("$.counts.loginLogs").value(1))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)));

        mockMvc.perform(post("/api/admin/settings/clear-data")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"time\":\"2026-07-01T00:00:00Z\",\"confirm\":\"CONFIRM_CLEAR\",\"preview\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已清理")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE bet_code = 'CLEAR-OLD-BET'", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE bet_code = 'CLEAR-NEW-BET'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_issue WHERE issue_number = 'CLEAR-OLD-ISSUE'", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_issue WHERE issue_number = 'CLEAR-NEW-ISSUE'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE reason = '清空测试旧流水'
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE permission_code = 'PLATFORM_SETTINGS_WRITE'
                   AND request_path = '/api/admin/settings/clear-data'
                   AND operator_user_id = ?
                """, Long.class, adminUserId)).isEqualTo(1);
    }

    @Test
    @Transactional
    void clearDataCannotDeleteConfirmedDrawHistory() throws Exception {
        long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, opened_at, created_at,
                     draw_result_version, draw_confirmed_at, draw_confirmed_by)
                VALUES ('AU8', 'CLEAR-CONFIRMED-ISSUE', 'CLOSED', 'SETTLED', 1, 2, 3, 4,
                        5, 6, 7, 8, '2026-01-01 00:00:00', '2026-01-01 00:00:00',
                        1, '2026-01-01 00:00:00', ?)
                """, adminId);
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(post("/api/admin/settings/clear-data")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"time\":\"2026-07-01T00:00:00Z\",\"confirm\":\"CONFIRM_CLEAR\",\"preview\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLEAR_DATA_CONFIRMED_DRAWS_EXIST"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM game_issue
                 WHERE game_code = 'AU8' AND issue_number = 'CLEAR-CONFIRMED-ISSUE'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @Transactional
    void previewsAndPhysicallyDeletesAllNonRootAccountsWithBy220Confirmation() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult subResult = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_bulk_sub","rawPassword":"SubPassword123",
                                 "displayName":"全量删除子账号","score":0,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long subId = ((Number) JsonPath.read(subResult.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult machineResult = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_bulk_machine\",\"rawPassword\":\"MachinePassword123\","
                                + "\"displayName\":\"全量删除机器\",\"groupId\":" + subId
                                + ",\"score\":0,\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                                + "\"botCount\":0,\"closeSeconds\":0,\"cancelSeconds\":0,\"rebateRate\":0,"
                                + "\"oddsRate\":0,\"specialRebateRate\":0,\"specialOddsRate\":0,"
                                + "\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,"
                                + "\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":0,"
                                + "\"playerMinStake\":0,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk()).andReturn();
        long machineId = ((Number) JsonPath.read(machineResult.getResponse().getContentAsString(), "$.id")).longValue();
        long playerUserId = userRepository.insert("platform_test_bulk_player", "全量删除玩家",
                passwordPolicy.encode("PlayerPassword123"), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, identity_type, player_kind,
                     member_code, agent_id)
                VALUES ('PLATFORM_TEST_BULK_PLAYER', '全量删除玩家', 0, 'ACTIVE', ?, 'REAL', 'NORMAL',
                        'TP-BULK-PLAYER', ?)
                """, playerUserId, machineId);

        // 执行者本人也挂着一行玩家账号（本机真实数据就是这样）：全量删除不得把自己删掉，
        // 否则删除后写审计会因 sys_operation_log 外键指向已删用户而失败（本机 MySQL 实测踩过）。
        long adminUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, identity_type, player_kind,
                     member_code, agent_id)
                VALUES ('PLATFORM_TEST_BULK_ADMIN', '执行者的玩家档案', 0, 'ACTIVE', ?, 'REAL', 'NORMAL',
                        'TP-BULK-ADMIN', 1)
                """, adminUserId);

        mockMvc.perform(post("/api/admin/settings/delete-all-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"confirm\":\"WRONG\",\"preview\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELETE_ALL_ACCOUNTS_CONFIRM_REQUIRED"));

        mockMvc.perform(post("/api/admin/settings/delete-all-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"confirm\":\"DELETE_ALL_ACCOUNTS\",\"preview\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(true))
                .andExpect(jsonPath("$.counts.admins").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.counts.robots").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.counts.players").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(post("/api/admin/settings/delete-all-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"confirm\":\"DELETE_ALL_ACCOUNTS\",\"preview\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已删除")));

        // 物理删除：子账号、机器、玩家全部真的没了（对齐 BY220 Gd.php::userDels）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_group WHERE id = ?", Long.class, subId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE id = ?", Long.class, machineId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = 'platform_test_bulk_sub'", Long.class)).isZero();
        // 执行者与平台直属主机器保留
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE username = ?", String.class, ADMIN))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id = ?", Long.class, adminUserId))
                .as("执行者自己的玩家档案必须保留，否则删除后审计写入会外键失败").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE system_owned = TRUE AND deleted_at IS NULL", Long.class))
                .isEqualTo(1);

        mockMvc.perform(post("/api/admin/settings/delete-all-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"confirm\":\"DELETE_ALL_ACCOUNTS\",\"preview\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.admins").value(0))
                .andExpect(jsonPath("$.counts.robots").value(0))
                // 只剩执行者自己那行玩家档案（保护执行者，不能连自己一起删）
                .andExpect(jsonPath("$.counts.players").value(1));
    }
    @Test
    void platformAdminSpaRoutesForwardToFrontend() throws Exception {
        mockMvc.perform(get("/platform-admin/unsettled-orders"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/platform-admin/order-corrections"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/platform-admin/online-players"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/platform-admin/settings"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/platform-admin/report-networks"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/platform-admin/games"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/platform-admin/password"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void managesReportNetworkSettingsWithVersionProtection() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/admin/report-networks")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"code\":\"platform_test_network\",\"name\":\"测试网盘\","
                                + "\"websiteUrl\":\"https://example.invalid\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("platform_test_network"))
                .andExpect(jsonPath("$.name").value("测试网盘"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/api/admin/report-networks").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("platform_test_network"));

        MvcResult statusChanged = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/admin/report-networks/" + id + "/status").param("version", "0")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        long version = ((Number) JsonPath.read(statusChanged.getResponse().getContentAsString(), "$.version")).longValue();
        mockMvc.perform(put("/api/admin/report-networks/" + id).param("version", "0")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"code\":\"platform_test_network\",\"name\":\"重复版本\","
                                + "\"websiteUrl\":\"https://example.invalid\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/admin/report-networks/" + id).param("version", Long.toString(version))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM report_network WHERE id = ?", Timestamp.class, id)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM report_network WHERE id = ? AND deleted_at IS NULL
                """, Long.class, id)).isZero();
    }
    @Test
    void managesMultipleGameDefinitionsAndRouteOdds() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult listResult = mockMvc.perform(get("/api/admin/game-settings")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].gameCode").value("AU8"))
                .andExpect(jsonPath("$[0].oddsAte").isNumber())
                .andReturn();
        long version = ((Number) JsonPath.read(listResult.getResponse().getContentAsString(), "$[0].version")).longValue();

        String primaryBody = "{\"gameCode\":\"AU8\",\"displayName\":\"澳8测试彩种\","
                + "\"ballIndexes\":\"1,2,3,4,5,6,7,8\",\"drawSourceUrl\":null,"
                + "\"sortOrder\":2,\"algorithm\":\"CONCAT\",\"playPrefix\":\"测试\","
                + "\"switchEnabled\":true,\"specialEnabled\":false,\"specialModel\":\"MODEL_TWO\","
                + "\"keyboardEnabled\":false,\"status\":\"ACTIVE\","
                + "\"oddsAte\":18.50,\"oddsAdx\":2.00,\"oddsBte\":17.50,\"oddsBdx\":1.90,"
                + "\"oddsCte\":16.50,\"oddsCdx\":1.80,\"oddsDte\":15.50,\"oddsDdx\":1.70}";
        mockMvc.perform(put("/api/admin/game-settings/1").param("version", Long.toString(version))
                        .header("Authorization", bearer(token)).contentType("application/json").content(primaryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("澳8测试彩种"))
                .andExpect(jsonPath("$.algorithm").value("CONCAT"))
                .andExpect(jsonPath("$.oddsAte").value(18.50))
                .andExpect(jsonPath("$.oddsDdx").value(1.70))
                .andExpect(jsonPath("$.version").value(version + 1));
        mockMvc.perform(put("/api/admin/game-settings/1").param("version", Long.toString(version))
                        .header("Authorization", bearer(token)).contentType("application/json").content(primaryBody))
                .andExpect(status().isConflict());

        String createBody = "{\"gameCode\":\"PLATFORM_TEST_GAME\",\"displayName\":\"测试多彩种\","
                + "\"ballIndexes\":\"1,2,3,4,5,6,7,8\",\"drawSourceUrl\":null,"
                + "\"sortOrder\":9,\"algorithm\":\"SUM\",\"playPrefix\":\"\","
                + "\"switchEnabled\":false,\"specialEnabled\":true,\"specialModel\":\"MODEL_ONE\","
                + "\"keyboardEnabled\":true,\"status\":\"ACTIVE\","
                + "\"oddsAte\":19.00,\"oddsAdx\":2.10,\"oddsBte\":19.10,\"oddsBdx\":2.20,"
                + "\"oddsCte\":19.20,\"oddsCdx\":2.30,\"oddsDte\":19.30,\"oddsDdx\":2.40}";
        MvcResult created = mockMvc.perform(post("/api/admin/game-settings")
                        .header("Authorization", bearer(token)).contentType("application/json").content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameCode").value("PLATFORM_TEST_GAME"))
                .andExpect(jsonPath("$.oddsAte").value(19.00))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        long gameId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/api/admin/game-settings/" + gameId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oddsDdx").value(2.40));
        mockMvc.perform(delete("/api/admin/game-settings/1").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GAME_PRIMARY_DELETE_FORBIDDEN"));
        mockMvc.perform(delete("/api/admin/game-settings/" + gameId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/game-settings/" + gameId).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }
    @Test
    void changesCurrentPlatformPasswordAndInvalidatesExistingSession() throws Exception {
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/password").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN));

        String wrongOldPassword = "{\"oldPassword\":\"WrongPassword123\","
                + "\"newPassword\":\"ChangedPassword123\",\"confirmPassword\":\"ChangedPassword123\"}";
        mockMvc.perform(put("/api/admin/password").header("Authorization", bearer(token))
                        .contentType("application/json").content(wrongOldPassword))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLATFORM_PASSWORD_OLD_MISMATCH"));

        String mismatch = "{\"oldPassword\":\"" + PASSWORD + "\","
                + "\"newPassword\":\"ChangedPassword123\",\"confirmPassword\":\"DifferentPassword123\"}";
        mockMvc.perform(put("/api/admin/password").header("Authorization", bearer(token))
                        .contentType("application/json").content(mismatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLATFORM_PASSWORD_CONFIRM_MISMATCH"));

        String valid = "{\"oldPassword\":\"" + PASSWORD + "\","
                + "\"newPassword\":\"ChangedPassword123\",\"confirmPassword\":\"ChangedPassword123\"}";
        mockMvc.perform(put("/api/admin/password").header("Authorization", bearer(token))
                        .contentType("application/json").content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("1"))
                .andExpect(jsonPath("$.passwordChanged").value(true))
                .andExpect(jsonPath("$.usernameChanged").value(false));

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(login(ADMIN, "ChangedPassword123")).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE operator_user_id = (SELECT id FROM sys_user WHERE username = ?)
                   AND permission_code = 'PLATFORM_PASSWORD_MANAGE'
                   AND request_path = '/api/admin/password'
                """, Long.class, ADMIN)).isEqualTo(1);
    }
    /** 报表测试脚手架：建一个子账号（agent_group）+ 一台机器（agent）+ 一个玩家，返回三者主键。 */
    private ReportFixture createReportFixture(String suffix) {
        long groupId = insertAndReturnId("""
                INSERT INTO agent_group (group_code, display_name, status, username)
                VALUES (?, ?, 'ACTIVE', ?)
                """, "PLATFORM_TEST_REPORT_GROUP_" + suffix, "报表测试子账号" + suffix,
                "platform_test_report_" + suffix);
        long machineUserId = userRepository.insert("platform-admin-test-report-machine-" + suffix,
                "报表测试机器" + suffix, passwordPolicy.encode(PASSWORD), "ACTIVE");
        long machineId = insertAndReturnId("""
                INSERT INTO agent (agent_code, display_name, group_id, account_user_id, system_owned, status)
                VALUES (?, ?, ?, ?, FALSE, 'ACTIVE')
                """, "PLATFORM_TEST_REPORT_MACHINE_" + suffix, "报表测试机器" + suffix, groupId, machineUserId);
        long playerUserId = userRepository.insert("platform-admin-test-report-player-" + suffix,
                "报表测试玩家" + suffix, passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES (?, ?, 100.00, 'ACTIVE', ?, ?, 'REAL', 'NORMAL', ?)
                """, "PLATFORM_TEST_REPORT_PLAYER_" + suffix, "报表测试玩家" + suffix, playerUserId, machineId,
                "V-REPORT-" + suffix);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        return new ReportFixture(groupId, machineId, accountId);
    }

    private record ReportFixture(long groupId, long machineId, long accountId) {}

    private void insertReportBet(long accountId, String betCode, String playType, String parameters,
                                 String stake, String odds, String netProfit, String status,
                                 String rebateRate, String specialRebateRate) {
        // created_at/settled_at 必须固定在查询所用的业务日（2026-10-02）之内：
        // 报表按业务日过滤，若用 CURRENT_TIMESTAMP，测试只在该业务日当天通过，跨日即失败。
        jdbcTemplate.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, net_profit,
                     settlement_status, rebate_rate_snapshot, special_rebate_rate_snapshot,
                     created_at, settled_at)
                VALUES (?, ?, ?, '39999949', 1, ?, ?, ?, ?, ?, ?, ?, ?,
                        '2026-10-02 12:00:00', '2026-10-02 12:00:00')
                """, accountId, betCode, betCode, playType, parameters, new java.math.BigDecimal(stake),
                new java.math.BigDecimal(odds), new java.math.BigDecimal(netProfit), status,
                new java.math.BigDecimal(rebateRate), new java.math.BigDecimal(specialRebateRate));
    }
    private long insertAndReturnId(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * 删除机器/子账号对齐 BY220：**物理删除，且不留任何业务残留**。
     *
     * <p>使用者 2026-10-03 确认：「完全物理删除、不要保留」——BY220 {@code admin.php} 删机器会连带
     * {@code x_rbuser}（玩家/托）与 {@code x_record}，删子账号会连带其 {@code x_robot}；
     * 「有用流水后续再跑就有了」，所以注单、余额流水、机器积分流水一并删除。
     * 本用例固定住：①机器删掉后名下玩家的账号、注单、流水全部消失，登录名可立即重建；
     * ②子账号删掉会连带名下机器；③审计行保留但操作者置空。</p>
     */
    @Test
    void physicallyDeletesMachineWithPlayersAndSubAccountWithMachines() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult subResult = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_delete_sub","rawPassword":"SubPassword123",
                                 "displayName":"删除测试子账号","score":1000,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long subId = ((Number) JsonPath.read(subResult.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult machineResult = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_machine\",\"rawPassword\":\"MachinePassword123\","
                                + "\"displayName\":\"删除测试机器\",\"groupId\":" + subId
                                + ",\"score\":100,\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                                + "\"botCount\":0,\"closeSeconds\":0,\"cancelSeconds\":0,\"rebateRate\":0,"
                                + "\"oddsRate\":0,\"specialRebateRate\":0,\"specialOddsRate\":0,"
                                + "\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,"
                                + "\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":0,"
                                + "\"playerMinStake\":0,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk()).andReturn();
        long machineId = ((Number) JsonPath.read(machineResult.getResponse().getContentAsString(), "$.id")).longValue();

        // 造一个带注单、带余额流水的玩家/托，验证级联物理删除
        long playerUserId = userRepository.insert("platform_test_delete_player", "删除依赖玩家",
                passwordPolicy.encode("PlayerPassword123"), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, identity_type, player_kind,
                     member_code, agent_id)
                VALUES ('PLATFORM_TEST_DELETE_PLAYER', '删除依赖玩家', 100, 'ACTIVE', ?, 'REAL', 'NORMAL',
                        'TP-DELETE-PLAYER', ?)
                """, playerUserId, machineId);
        long playerAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        jdbcTemplate.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, 'PLATFORM_TEST_DELETE_BET', 'platform-test-delete-bet',
                        '39999971', 1, 'FAN', '1', 10.00, 3.850)
                """, playerAccountId);
        jdbcTemplate.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after, reason, operator_name)
                VALUES (?, 'GRANT', 100.00, 0.00, 100.00, '删除级联验证', ?)
                """, playerAccountId, ADMIN);

        // 给该玩家造一条 WS 票据：票据的 session_id 外键指向 auth_session，
        // 若清理顺序写成"先删会话再删票据"，这里就会撞 fk_auth_ws_ticket_session
        // （本机 MySQL 实测暴露过；顺手把这条顺序固定下来）。
        String playerToken = login("platform_test_delete_player", "PlayerPassword123");
        jdbcTemplate.update("""
                INSERT INTO auth_ws_ticket (ticket_hash, user_id, session_id, room_code, expires_at)
                SELECT ?, ?, s.session_id, 'main', DATEADD('MINUTE', 10, CURRENT_TIMESTAMP)
                  FROM auth_session s
                 WHERE s.user_id = ?
                 ORDER BY s.id DESC
                 LIMIT 1
                """, "platform-test-delete-ticket", playerUserId, playerUserId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_ws_ticket WHERE user_id = ?", Long.class, playerUserId)).isEqualTo(1);
        assertThat(playerToken).isNotBlank();

        mockMvc.perform(delete("/api/admin/machines/" + machineId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + machineId + ")]").isEmpty());

        // 机器、玩家账号、注单、余额流水、机器积分流水全部物理删除
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE id = ?", Long.class, machineId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = ?", Long.class, playerUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE id = ?", Long.class, playerAccountId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, playerAccountId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_balance_ledger WHERE user_id = ?", Long.class, playerAccountId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ?", Long.class, machineId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_ws_ticket WHERE user_id = ?", Long.class, playerUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE user_id = ?", Long.class, playerUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = 'platform_test_delete_machine'", Long.class)).isZero();
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_machine\",\"password\":\"MachinePassword123\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_player\",\"password\":\"PlayerPassword123\"}"))
                .andExpect(status().isUnauthorized());

        // 同名可以立刻重建
        mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_machine\",\"rawPassword\":\"MachinePassword456\","
                                + "\"displayName\":\"重建同名机器\",\"score\":0,\"boardOpen\":true,"
                                + "\"robotManage\":false,\"chaseEnabled\":false,\"botCount\":0,\"closeSeconds\":0,"
                                + "\"cancelSeconds\":0,\"rebateRate\":0,\"oddsRate\":0,\"specialRebateRate\":0,"
                                + "\"specialOddsRate\":0,\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,"
                                + "\"strictLimit\":0,\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,"
                                + "\"oddEvenLimit\":0,\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,"
                                + "\"playerMaxStake\":0,\"playerMinStake\":0,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountUsername").value("platform_test_delete_machine"));

        // 删除子账号：连带名下机器（BY220 Users.php 删子账号会删掉其机器人）
        MvcResult machine2 = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_machine_child\",\"rawPassword\":\"MachinePassword789\","
                                + "\"displayName\":\"子账号下的机器\",\"groupId\":" + subId
                                + ",\"score\":0,\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                                + "\"botCount\":0,\"closeSeconds\":0,\"cancelSeconds\":0,\"rebateRate\":0,"
                                + "\"oddsRate\":0,\"specialRebateRate\":0,\"specialOddsRate\":0,"
                                + "\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,"
                                + "\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":0,"
                                + "\"playerMinStake\":0,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk()).andReturn();
        long childMachineId = ((Number) JsonPath.read(machine2.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(delete("/api/admin/sub-accounts/" + subId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_group WHERE id = ?", Long.class, subId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE id = ?", Long.class, childMachineId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = 'platform_test_delete_sub'", Long.class)).isZero();
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_sub\",\"password\":\"SubPassword123\"}"))
                .andExpect(status().isUnauthorized());

        // 子账号同名同样可以立刻重建
        mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_delete_sub","rawPassword":"SubPassword456",
                                 "displayName":"重建同名子账号","score":0,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("platform_test_delete_sub"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE http_method = 'DELETE'
                   AND (request_path = ? OR request_path = ?)
                """, Long.class, "/api/admin/machines/" + machineId,
                "/api/admin/sub-accounts/" + subId)).isEqualTo(2);
    }
    @Test
    void rejectsExpirationBeyondMysqlTimestampRangeBeforeWriting() throws Exception {
        String token = login(ADMIN, PASSWORD);
        mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_expiry_range","rawPassword":"SubPassword123",
                                 "displayName":"过期范围验证","score":0,"expiresAt":"2099-01-01T00:00:00Z",
                                 "subAccountManage":false,"machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_EXPIRY_OUT_OF_RANGE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_group WHERE username = 'platform_test_expiry_range'", Long.class))
                .isZero();
    }
    /**
     * 审计日志查询（账本原则 3：{@code AUDIT_READ} 落地）。
     *
     * <p>以前审计只能写不能查，{@code AUDIT_READ} 是死权限；这个用例固定住：
     * 有权限能查到刚写下的审计、无权限被拒、过滤条件与分页校验生效。</p>
     */
    @Test
    void auditLogQueryRequiresPermissionAndFiltersEntries() throws Exception {
        String token = login(ADMIN, PASSWORD);

        // 先制造一条可查的审计：创建一个机器（写 MACHINE_MANAGE 审计）
        mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_audit_machine\",\"rawPassword\":\"AuditPassword123\","
                                + "\"displayName\":\"审计测试机器\",\"score\":100,\"boardOpen\":true,"
                                + "\"robotManage\":false,\"chaseEnabled\":false,\"botCount\":0,\"closeSeconds\":10,"
                                + "\"cancelSeconds\":5,\"rebateRate\":0.1,\"oddsRate\":1.95,\"specialRebateRate\":0.2,"
                                + "\"specialOddsRate\":18,\"totalLimit\":10000,\"positiveLimit\":1000,"
                                + "\"angleLimit\":1000,\"strictLimit\":1000,\"tongLimit\":1000,\"carLimit\":1000,"
                                + "\"specialLimit\":1000,\"oddEvenLimit\":1000,\"bigSmallLimit\":1000,"
                                + "\"fanLimit\":1000,\"addLimit\":1000,\"playerMaxStake\":500,"
                                + "\"playerMinStake\":10,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk());

        // 按权限码过滤：能查到 MACHINE_MANAGE 的审计
        mockMvc.perform(get("/api/admin/audit-logs").param("permissionCode", "MACHINE_MANAGE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items[0].permissionCode").value("MACHINE_MANAGE"))
                .andExpect(jsonPath("$.items[0].httpMethod").value("POST"))
                .andExpect(jsonPath("$.items[0].result").value("SUCCESS"));

        // 关键词匹配请求路径
        mockMvc.perform(get("/api/admin/audit-logs").param("keyword", "/api/admin/machines")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // 非法 result → 400
        mockMvc.perform(get("/api/admin/audit-logs").param("result", "BOGUS")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        // 分页越界 → 400
        mockMvc.perform(get("/api/admin/audit-logs").param("pageSize", "1000")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUDIT_QUERY_INVALID"));

        // 无 AUDIT_READ 的子账号 → 403（审计不是所有登录用户都能看）
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(subAccountToken())))
                .andExpect(status().isForbidden());
    }

    /** 造一个只有子账号角色（无 AUDIT_READ）的登录账号，用于验证审计查询的权限门。 */
    private String subAccountToken() throws Exception {
        String username = "platform_test_audit_sub";
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", username);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", username);
        jdbcTemplate.update("INSERT INTO sys_user (username, display_name, password_hash, status, auth_mode, "
                        + "internal_code) VALUES (?, '审计无权限子账号', ?, 'ACTIVE', 'PASSWORD', ?)",
                username, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD),
                "AUDIT-SUB-" + System.nanoTime());
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) "
                + "SELECT ?, id FROM sys_role WHERE role_code = 'SUB_ACCOUNT'", userId);
        return login(username, PASSWORD);
    }

    /**
     * 机器账号被锁死时必须看得见、能解锁。
     *
     * <p>缺陷背景：{@code sys_user.status='LOCKED'} 且 {@code locked_until} 为空时
     * {@code UserAccount#canLogin} 永远返回 false，而登录失败被统一伪装成"用户名或密码错误"，
     * 机器管理又只显示 BY220 的机器状态，于是密码正确也进不去、且没有解锁入口
     * （本机遗留机器账号 localadmin 就是这样卡死的）。本用例固定住两点：
     * 列表必须返回登录态 {@code accountStatus}；把状态置回 ACTIVE 必须真正解锁并能登录。</p>
     */
    @Test
    void exposesLockedMachineAccountAndUnlocksItThroughStatusEndpoint() throws Exception {
        String token = login(ADMIN, PASSWORD);
        mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_locked_machine\",\"rawPassword\":\"MachinePassword123\","
                                + "\"displayName\":\"锁定机器\",\"score\":100,\"boardOpen\":true,"
                                + "\"robotManage\":false,\"chaseEnabled\":false,\"botCount\":0,\"closeSeconds\":10,"
                                + "\"cancelSeconds\":5,\"rebateRate\":0.1,\"oddsRate\":1.95,\"specialRebateRate\":0.2,"
                                + "\"specialOddsRate\":18,\"totalLimit\":10000,\"positiveLimit\":1000,"
                                + "\"angleLimit\":1000,\"strictLimit\":1000,\"tongLimit\":1000,\"carLimit\":1000,"
                                + "\"specialLimit\":1000,\"oddEvenLimit\":1000,\"bigSmallLimit\":1000,"
                                + "\"fanLimit\":1000,\"addLimit\":1000,\"playerMaxStake\":500,"
                                + "\"playerMinStake\":10,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk());
        Long machineId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent WHERE UPPER(agent_code) = 'PLATFORM_TEST_LOCKED_MACHINE'", Long.class);
        Long accountUserId = jdbcTemplate.queryForObject(
                "SELECT account_user_id FROM agent WHERE id = ?", Long.class, machineId);

        // 管理端把账号置为"锁定"时写的就是 LOCKED + locked_until 为空（UserRepository.updateManagedStatus）
        jdbcTemplate.update(
                "UPDATE sys_user SET status='LOCKED', locked_until=NULL, failed_login_count=0 WHERE id=?",
                accountUserId);
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"platform_test_locked_machine\",\"password\":\"MachinePassword123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].accountStatus").value("LOCKED"));

        mockMvc.perform(patch("/api/admin/machines/" + machineId + "/status")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE id = ?", String.class, accountUserId)).isEqualTo("ACTIVE");
        // 密码一直没有变，之前只是被锁死；解锁后必须能登录
        assertThat(login("platform_test_locked_machine", "MachinePassword123")).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE request_path = ? AND request_summary LIKE '%unlockAccount=true%'
                """, Long.class, "/api/admin/machines/" + machineId + "/status")).isEqualTo(1);
    }

    /** 子账号与机器账号共用登录账号锁定语义，解锁入口同样要落在子账号管理上。 */
    @Test
    void exposesLockedSubAccountAndUnlocksItThroughStatusEndpoint() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_locked_sub","rawPassword":"SubPassword123",
                                 "displayName":"锁定子账号","score":100,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long subId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        Long accountUserId = jdbcTemplate.queryForObject(
                "SELECT sys_user_id FROM agent_group WHERE id = ?", Long.class, subId);

        jdbcTemplate.update(
                "UPDATE sys_user SET status='LOCKED', locked_until=NULL, failed_login_count=0 WHERE id=?",
                accountUserId);
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"platform_test_locked_sub\",\"password\":\"SubPassword123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/sub-accounts").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].accountStatus").value("LOCKED"));

        mockMvc.perform(patch("/api/admin/sub-accounts/" + subId + "/status")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE id = ?", String.class, accountUserId)).isEqualTo("ACTIVE");
        assertThat(login("platform_test_locked_sub", "SubPassword123")).isNotBlank();
    }

    /**
     * 子账号积分是名下机器的上游资金池（BY220 语义）。
     *
     * <p>BY220 {@code Rb.php} 子账号建机器时 {@code setDec('score')} 扣子账号积分，
     * {@code common.php::rbUpdown} 给机器上分时扣子账号、下分时退回子账号。本用例固定住：
     * ①建机器从池划出、池余额不足直接 409；②机器积分调高继续从池划出、调低退回池；
     * ③机器改挂子账号时积分退回原池；④改挂到无子账号（超管直连机器）不再涉及资金池；
     * ⑤每次划拨都在 {@code agent_ledger} 留下成对流水（{@code MACHINE_ALLOCATE}/{@code MACHINE_RETURN}）。</p>
     */
    @Test
    void allocatesMachineScoreFromSubAccountPoolAndReturnsItOnReduction() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult subResult = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_pool_sub","rawPassword":"SubPassword123",
                                 "displayName":"资金池子账号","score":1000,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long subId = ((Number) JsonPath.read(subResult.getResponse().getContentAsString(), "$.id")).longValue();

        // ① 建机器：从池划出 300
        MvcResult machineResult = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_pool_machine\",\"rawPassword\":\"MachinePassword123\","
                                + "\"displayName\":\"资金池机器\",\"groupId\":" + subId
                                + ",\"score\":300,\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                                + "\"botCount\":0,\"closeSeconds\":0,\"cancelSeconds\":0,\"rebateRate\":0,"
                                + "\"oddsRate\":0,\"specialRebateRate\":0,\"specialOddsRate\":0,"
                                + "\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,"
                                + "\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":0,"
                                + "\"playerMinStake\":0,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk()).andReturn();
        long machineId = ((Number) JsonPath.read(machineResult.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(groupScore(subId)).isEqualByComparingTo("700.00");
        assertThat(machineScore(machineId)).isEqualByComparingTo("300.00");

        // ② 池不够就拒绝，且不产生任何余额变动
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineUpdateBody(9999, subId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_SCORE_INSUFFICIENT"));
        assertThat(groupScore(subId)).isEqualByComparingTo("700.00");
        assertThat(machineScore(machineId)).isEqualByComparingTo("300.00");

        // ③ 机器调低：差额 200 退回池
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineUpdateBody(100, subId)))
                .andExpect(status().isOk());
        assertThat(groupScore(subId)).isEqualByComparingTo("900.00");
        assertThat(machineScore(machineId)).isEqualByComparingTo("100.00");

        // ④ 机器改挂到无子账号（超管直连机器）：现有积分退回原池，机器按目标值直接设定
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineUpdateBody(100, null)))
                .andExpect(status().isOk());
        assertThat(groupScore(subId)).isEqualByComparingTo("1000.00");
        assertThat(machineScore(machineId)).isEqualByComparingTo("100.00");

        // ⑤ 成对流水：子账号侧与机器侧各一条，方向相反
        assertThat(ledgerCount("SELECT COUNT(*) FROM agent_ledger WHERE group_id = " + subId
                + " AND entry_type = 'MACHINE_ALLOCATE' AND direction = 'OUT'")).isEqualTo(1);
        assertThat(ledgerCount("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = " + machineId
                + " AND entry_type = 'MACHINE_ALLOCATE' AND direction = 'IN'")).isEqualTo(1);
        assertThat(ledgerCount("SELECT COUNT(*) FROM agent_ledger WHERE group_id = " + subId
                + " AND entry_type = 'MACHINE_RETURN' AND direction = 'IN'")).isEqualTo(2);
        assertThat(ledgerCount("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = " + machineId
                + " AND entry_type = 'MACHINE_RETURN' AND direction = 'OUT'")).isEqualTo(2);
    }

    /**
     * 积分精度（计划 §3.5）：子账号池 → 机器的小数划拨必须端到端可用，且账面**精确到分**。
     *
     * <p>修复前 {@code AgentLedgerService.scaled} 用 {@code value.setScale(2)}（无舍入模式），
     * 目标值带 3 位小数会抛 {@code ArithmeticException} 落到全局兜底 500；本用例固定住
     * ①0.01 / 0.10 的划拨可用；②池与机器的余额、成对流水金额都是两位小数；
     * ③读接口回显仍是两位小数。</p>
     */
    @Test
    void allocatesFractionalMachineScoreWithExactLedgerAmounts() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long subId = createSubAccount(token, "platform_test_scale_sub", "精度子账号", "1000");

        // ① 建机器 score=0.01：池 −0.01、机器 +0.01
        MvcResult machineResult = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineCreateBody("platform_test_scale_machine", "精度机器", subId, "0.01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.01))
                .andReturn();
        long machineId = ((Number) JsonPath.read(machineResult.getResponse().getContentAsString(), "$.id")).longValue();

        assertThat(groupScore(subId)).isEqualByComparingTo("999.99");
        assertThat(machineScore(machineId)).isEqualByComparingTo("0.01");
        assertThat(machineLedgerAmountOfType(machineId, "MACHINE_ALLOCATE").toPlainString())
                .as("机器侧划入流水必须精确到 0.01").isEqualTo("0.01");
        assertThat(machineLedgerBalanceAfterOfType(machineId, "MACHINE_ALLOCATE").toPlainString())
                .as("机器侧变动后余额必须是两位小数").isEqualTo("0.01");
        assertThat(groupLedgerAmountOfType(subId, "MACHINE_ALLOCATE").toPlainString())
                .as("子账号池划出流水必须精确到 0.01").isEqualTo("0.01");
        assertThat(groupLedgerBalanceAfterOfType(subId, "MACHINE_ALLOCATE").toPlainString())
                .as("池变动后余额必须是两位小数").isEqualTo("999.99");

        // ② 机器改到 0.11：差额 0.10 继续从池划出
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineUpdateBody("0.11", subId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.11));
        assertThat(groupScore(subId)).isEqualByComparingTo("999.89");
        assertThat(machineScore(machineId)).isEqualByComparingTo("0.11");
        assertThat(machineLedgerAmountOfType(machineId, "MACHINE_ALLOCATE").toPlainString())
                .as("第二次划拨的差额必须是 0.10").isEqualTo("0.10");
        assertThat(groupLedgerAmountOfType(subId, "MACHINE_ALLOCATE").toPlainString()).isEqualTo("0.10");

        // ③ 列表读接口仍按两位小数回显（不允许出现 0.1099999 之类的浮点尾巴）
        mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + machineId + ")].score").value(0.11));
        mockMvc.perform(get("/api/admin/sub-accounts").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + subId + ")].score").value(999.89));
    }

    /**
     * 超精度一律 400，且**余额与流水都不变**（计划 §3.5）。
     *
     * <p>覆盖四个入口：机器目标值、子账号目标值、子账号上分、子账号下分。
     * 修复前机器/子账号目标值会冒 500，上分/下分会把 0.001 静默吞成 0.00 并当作"本次不改动"。</p>
     */
    @Test
    void rejectsScoresWithMoreThanTwoDecimalsWithoutAnyChange() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long subId = createSubAccount(token, "platform_test_scale_reject_sub", "精度拒绝子账号", "100");
        MvcResult machineResult = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineCreateBody("platform_test_scale_reject_machine", "精度拒绝机器", subId, "50")))
                .andExpect(status().isOk()).andReturn();
        long machineId = ((Number) JsonPath.read(machineResult.getResponse().getContentAsString(), "$.id")).longValue();

        long machineLedgerBefore = machineLedgerCount(machineId);
        long groupLedgerBefore = groupLedgerCount(subId);

        // ① 机器目标值 0.001 → 400（不再是 500）
        mockMvc.perform(machinePut(machineId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(machineUpdateBody("0.001", subId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MACHINE_SCORE_INVALID"))
                .andExpect(jsonPath("$.message").value("机器积分最多两位小数"));

        // ② 子账号目标值 0.001（建号即设定初始积分）→ 400，且不留下半成品子账号
        mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_scale_reject_new","rawPassword":"SubPassword123",
                                 "displayName":"精度拒绝新子账号","score":0.001,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_SCORE_INVALID"))
                .andExpect(jsonPath("$.message").value("子账号积分最多两位小数"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_group WHERE username = 'platform_test_scale_reject_new'", Long.class))
                .as("超精度应在同一事务内回滚，不留下子账号").isZero();

        // ③ 子账号上分 0.001 → 400
        mockMvc.perform(put("/api/admin/sub-accounts/" + subId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(subAccountAdjustBody("platform_test_scale_reject_sub", "精度拒绝子账号", "0.001", "up")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_SCORE_INVALID"))
                .andExpect(jsonPath("$.message").value("子账号积分最多两位小数"));

        // ④ 子账号下分 0.001 → 400
        mockMvc.perform(put("/api/admin/sub-accounts/" + subId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(subAccountAdjustBody("platform_test_scale_reject_sub", "精度拒绝子账号", "0.001", "down")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_SCORE_INVALID"))
                .andExpect(jsonPath("$.message").value("子账号积分最多两位小数"));

        // 四个入口全部拒绝后：余额与流水一行都不许变
        // 注意：子账号初始 100，建机器时已按 BY220 资金池语义划出 50，所以此刻池应为 50.00（不是 100.00）。
        assertThat(groupScore(subId)).isEqualByComparingTo("50.00");
        assertThat(machineScore(machineId)).isEqualByComparingTo("50.00");
        assertThat(machineLedgerCount(machineId)).isEqualTo(machineLedgerBefore);
        assertThat(groupLedgerCount(subId)).isEqualTo(groupLedgerBefore);

        // 同为两位小数的 0.01 上分仍然可用（证明拒绝的是精度，不是功能）
        // 池的基线是 50.00（初始 100 已按资金池语义划出 50 建机器），因此上分 0.01 后是 50.01。
        mockMvc.perform(put("/api/admin/sub-accounts/" + subId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(subAccountAdjustBody("platform_test_scale_reject_sub", "精度拒绝子账号", "0.01", "up")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(50.01));
        assertThat(groupScore(subId)).isEqualByComparingTo("50.01");
        assertThat(groupLedgerAmountOfType(subId, "SUB_ACCOUNT_ADJUST").toPlainString()).isEqualTo("0.01");
    }

    private long createSubAccount(String token, String username, String displayName, String score) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"rawPassword\":\"SubPassword123\","
                                + "\"displayName\":\"" + displayName + "\",\"score\":" + score
                                + ",\"subAccountManage\":false,\"machineManage\":false,"
                                + "\"unifiedReportEnabled\":false}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** 子账号上分/下分请求体（BY220 {@code sub/form.html} 的 up/down 字段）。 */
    private static String subAccountAdjustBody(String username, String displayName, String amount, String direction) {
        return "{\"username\":\"" + username + "\",\"displayName\":\"" + displayName + "\",\"score\":null,"
                + "\"" + direction + "\":" + amount + ",\"subAccountManage\":false,\"machineManage\":false,"
                + "\"unifiedReportEnabled\":false}";
    }

    /** 机器新建请求体（decimal 积分版）。 */
    private static String machineCreateBody(String username, String displayName, Long groupId, String score) {
        return "{\"username\":\"" + username + "\",\"rawPassword\":\"MachinePassword123\","
                + "\"displayName\":\"" + displayName + "\",\"groupId\":" + groupId + ",\"score\":" + score
                + ",\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                + "\"botCount\":0,\"closeSeconds\":0,\"cancelSeconds\":0,\"rebateRate\":0,"
                + "\"oddsRate\":0,\"specialRebateRate\":0,\"specialOddsRate\":0,"
                + "\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,"
                + "\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":0,"
                + "\"playerMinStake\":0,\"games\":[\"AU8\"]}";
    }

    private long machineLedgerCount(long agentId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ?", Long.class, agentId);
        return total == null ? 0L : total;
    }

    private long groupLedgerCount(long groupId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_ledger WHERE group_id = ?", Long.class, groupId);
        return total == null ? 0L : total;
    }

    private java.math.BigDecimal machineLedgerAmountOfType(long agentId, String entryType) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM agent_ledger WHERE agent_id = ? AND entry_type = ? ORDER BY id DESC LIMIT 1",
                java.math.BigDecimal.class, agentId, entryType);
    }

    private java.math.BigDecimal machineLedgerBalanceAfterOfType(long agentId, String entryType) {
        return jdbcTemplate.queryForObject(
                "SELECT balance_after FROM agent_ledger WHERE agent_id = ? AND entry_type = ? ORDER BY id DESC LIMIT 1",
                java.math.BigDecimal.class, agentId, entryType);
    }

    private java.math.BigDecimal groupLedgerAmountOfType(long groupId, String entryType) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM agent_ledger WHERE group_id = ? AND entry_type = ? ORDER BY id DESC LIMIT 1",
                java.math.BigDecimal.class, groupId, entryType);
    }

    private java.math.BigDecimal groupLedgerBalanceAfterOfType(long groupId, String entryType) {
        return jdbcTemplate.queryForObject(
                "SELECT balance_after FROM agent_ledger WHERE group_id = ? AND entry_type = ? ORDER BY id DESC LIMIT 1",
                java.math.BigDecimal.class, groupId, entryType);
    }

    /** 机器编辑请求体（只关心积分与所属子账号，其余字段沿用 0/默认值）。 */
    private static String machineUpdateBody(int score, Long groupId) {
        return machineUpdateBody(Integer.toString(score), groupId);
    }

    /** 机器编辑请求体（decimal 积分版）。 */
    private static String machineUpdateBody(String score, Long groupId) {
        return "{\"username\":\"platform_test_pool_machine\",\"displayName\":\"资金池机器\",\"groupId\":"
                + (groupId == null ? "null" : groupId)
                + ",\"score\":" + score + ",\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                + "\"botCount\":0,\"closeSeconds\":0,\"cancelSeconds\":0,\"rebateRate\":0,"
                + "\"oddsRate\":0,\"specialRebateRate\":0,\"specialOddsRate\":0,"
                + "\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,"
                + "\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":0,"
                + "\"playerMinStake\":0,\"games\":[\"AU8\"]}";
    }

    private java.math.BigDecimal groupScore(long groupId) {
        return jdbcTemplate.queryForObject(
                "SELECT score FROM agent_group WHERE id = ?", java.math.BigDecimal.class, groupId);
    }

    private java.math.BigDecimal machineScore(long machineId) {
        return jdbcTemplate.queryForObject(
                "SELECT score FROM agent WHERE id = ?", java.math.BigDecimal.class, machineId);
    }

    private long ledgerCount(String sql) {
        Long total = jdbcTemplate.queryForObject(sql, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * 子账号列表与写操作按 BY220 收窄：子账号只能看到/操作自己那一行。
     *
     * <p>BY220 {@code Users.php::GetUserList()} 对非根管理员
     * {@code where('UserName', session('user_id'))}；而它的编辑/删除入口没有归属校验
     * （{@code subxiugai} 那段被注释掉），照抄会留越权口子，所以本项目额外加了归属校验
     * （比 BY220 更严，属于安全偏离）。</p>
     *
     * <p>说明：生产环境的 {@code SUB_ACCOUNT} 角色**默认没有** {@code SUB_ACCOUNT_MANAGE}
     * （见验收记录：BY220 会给子账号看「子账号」菜单，但照抄会开出「自己给自己加积分 / 无限建号」的口子，
     * 因此本项目有意不给）。本用例显式授予该权限，用来固定"万一被授予也必须被收窄"的防线；
     * 用例带 {@code @Transactional}，授予与数据都会回滚。</p>
     */
    @Test
    @Transactional
    void narrowsSubAccountListToSelfAndRejectsCrossSubAccountWrites() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_role_permission (role_id, permission_id)
                SELECT r.id, p.id FROM sys_role r, sys_permission p
                 WHERE r.role_code = 'SUB_ACCOUNT' AND p.permission_code = 'SUB_ACCOUNT_MANAGE'
                """);
        String token = login(ADMIN, PASSWORD);
        MvcResult first = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_scope_one","rawPassword":"SubPassword123",
                                 "displayName":"范围子账号一","score":100,"subAccountManage":true,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long firstId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult second = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_scope_two","rawPassword":"SubPassword123",
                                 "displayName":"范围子账号二","score":100,"subAccountManage":true,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long secondId = ((Number) JsonPath.read(second.getResponse().getContentAsString(), "$.id")).longValue();

        String subToken = login("platform_test_scope_one", "SubPassword123");
        // 只看得到自己
        mockMvc.perform(get("/api/admin/sub-accounts").header("Authorization", bearer(subToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstId));
        // 改不了别人
        mockMvc.perform(put("/api/admin/sub-accounts/" + secondId)
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_scope_two","displayName":"越权改名","score":null,
                                 "subAccountManage":true,"machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_SCOPE_FORBIDDEN"));
        // 删不了别人
        mockMvc.perform(delete("/api/admin/sub-accounts/" + secondId)
                        .header("Authorization", bearer(subToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_SCOPE_FORBIDDEN"));
        // 停用不了别人
        mockMvc.perform(patch("/api/admin/sub-accounts/" + secondId + "/status")
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_SCOPE_FORBIDDEN"));
        // 自己的还是能改（上分）
        mockMvc.perform(put("/api/admin/sub-accounts/" + firstId)
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_scope_one","displayName":"范围子账号一","score":null,
                                 "up":50,"subAccountManage":true,"machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT score FROM agent_group WHERE id = ?", java.math.BigDecimal.class, firstId))
                .isEqualByComparingTo("150.00");
    }

    /**
     * BY220 `Users.php:79` 在编辑分支 `unset($data['score'], ...)`：编辑表单里的积分是 readonly，
     * **编辑接口不接受直接设定积分**，只能走上分/下分。这里固定该口径：传了 score 也被忽略。
     */
    @Test
    void ignoresDirectScoreOnSubAccountUpdateBecauseBy220EditIsReadOnly() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_score_readonly","rawPassword":"SubPassword123",
                                 "displayName":"只读积分子账号","score":100,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long subId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(put("/api/admin/sub-accounts/" + subId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_score_readonly","displayName":"只读积分子账号",
                                 "score":99999,"subAccountManage":false,"machineManage":false,
                                 "unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(100));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT score FROM agent_group WHERE id = ?", java.math.BigDecimal.class, subId))
                .isEqualByComparingTo("100.00");
        // 只有上分/下分能改积分
        mockMvc.perform(put("/api/admin/sub-accounts/" + subId)
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_score_readonly","displayName":"只读积分子账号",
                                 "up":25,"subAccountManage":false,"machineManage":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(125));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private MockHttpServletRequestBuilder machinePut(long machineId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT betting_config_version FROM agent WHERE id=?", Integer.class, machineId);
        return put("/api/admin/machines/" + machineId)
                .queryParam("expectedConfigVersion", String.valueOf(version));
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM game_issue WHERE issue_number = 'CLEAR-CONFIRMED-ISSUE'");
        jdbcTemplate.update("DELETE FROM report_network WHERE network_code LIKE 'platform_test_%'");
        jdbcTemplate.update("DELETE FROM game_route_odds WHERE game_id IN (SELECT id FROM game_definition WHERE game_code LIKE 'PLATFORM_TEST_%')");
        jdbcTemplate.update("UPDATE game_route_odds SET special_odds = 0, big_small_odds = 0 WHERE game_id = 1");
        jdbcTemplate.update("UPDATE game_definition SET game_code = 'AU8', display_name = '澳8番摊', "
                + "ball_indexes = '1,2,3,4,5,6,7,8', draw_source_url = NULL, sort_order = 1, "
                + "algorithm = 'SUM', play_prefix = NULL, switch_enabled = FALSE, special_enabled = TRUE, "
                + "special_model = 'MODEL_ONE', keyboard_enabled = TRUE, status = 'ACTIVE', updated_by = NULL WHERE id = 1");
        jdbcTemplate.update("UPDATE platform_setting SET site_title = '公开大厅', announcement = NULL, "
                + "domain_links = NULL, chat_warning = '仅供本地研究', information = NULL, "
                + "header_enabled = TRUE, status_bar_enabled = TRUE, keyboard_mode = FALSE, "
                + "updated_by = NULL WHERE id = 1");
        // 审计权限门用例自建的子账号：先解角色再删用户，避免外键残留
        jdbcTemplate.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_AUDIT%')");
        jdbcTemplate.update("DELETE FROM agent_game WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_AUDIT%')");
        jdbcTemplate.update("DELETE FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_AUDIT%'");
        jdbcTemplate.update("DELETE FROM sys_login_log WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'platform_test_audit_sub%')");
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'platform_test_audit_sub%')");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'platform_test_audit_sub%')");
        jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'platform_test_audit_sub%')");
        jdbcTemplate.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'platform_test_audit_sub%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'platform_test_audit_sub%'");
        cleanupByUsers("DELETE FROM sys_operation_log WHERE operator_user_id IN %s");
        cleanupByUsers("DELETE FROM sys_login_log WHERE user_id IN %s");
        cleanupByUsers("DELETE FROM player_admin_notice WHERE recipient_user_id IN %s OR sender_user_id IN %s", true);
        cleanupByUsers("DELETE FROM game_bet_edit_record WHERE account_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN %s)");
        cleanupByUsers("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN %s)");
        cleanupByUsers("DELETE FROM game_bet WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN %s)");
        jdbcTemplate.update("DELETE FROM game_bet WHERE issue_number LIKE '399999%'");
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch WHERE game_event_id IN "
                + "(SELECT id FROM game_issue_event WHERE issue_number LIKE '399999%')");
        jdbcTemplate.update("DELETE FROM game_issue_event WHERE issue_number LIKE '399999%'");
        jdbcTemplate.update("DELETE FROM game_issue WHERE issue_number LIKE '399999%'");
        jdbcTemplate.update("DELETE FROM game_definition WHERE game_code LIKE 'PLATFORM_TEST_%'");
        jdbcTemplate.update("DELETE FROM agent_game WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_%')");
        cleanupByUsers("DELETE FROM demo_user_account WHERE sys_user_id IN %s");
        jdbcTemplate.update("DELETE FROM agent_ledger WHERE agent_id IN (SELECT id FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_%')");
        jdbcTemplate.update("DELETE FROM agent_ledger WHERE group_id IN (SELECT id FROM agent_group WHERE username LIKE 'platform_test_%')");
        jdbcTemplate.update("DELETE FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_%'");
        jdbcTemplate.update("DELETE FROM agent_group WHERE username LIKE 'platform_test_%'");
        cleanupByUsers("DELETE FROM auth_session WHERE user_id IN %s");
        cleanupByUsers("DELETE FROM sys_user_role WHERE user_id IN %s");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'platform-admin-test-%' "
                + "OR username LIKE 'platform_test_%'");
    }

    private String machineLedgerType(long agentId) {
        return jdbcTemplate.queryForObject("""
                SELECT entry_type FROM agent_ledger WHERE agent_id = ? ORDER BY id DESC LIMIT 1
                """, String.class, agentId);
    }

    private java.math.BigDecimal machineLedgerBalanceBefore(long agentId) {
        return jdbcTemplate.queryForObject("""
                SELECT balance_before FROM agent_ledger WHERE agent_id = ? ORDER BY id DESC LIMIT 1
                """, java.math.BigDecimal.class, agentId);
    }

    private java.math.BigDecimal machineLedgerBalanceAfter(long agentId) {
        return jdbcTemplate.queryForObject("""
                SELECT balance_after FROM agent_ledger WHERE agent_id = ? ORDER BY id DESC LIMIT 1
                """, java.math.BigDecimal.class, agentId);
    }

    private java.math.BigDecimal machineLedgerAmount(long agentId) {
        return jdbcTemplate.queryForObject("""
                SELECT amount FROM agent_ledger WHERE agent_id = ? ORDER BY id DESC LIMIT 1
                """, java.math.BigDecimal.class, agentId);
    }

    private void cleanupByUsers(String template, boolean twice) {
        String users = "(SELECT id FROM sys_user WHERE username LIKE 'platform-admin-test-%' "
                + "OR username LIKE 'platform_test_%')";
        String sql = template.formatted(users, users);
        jdbcTemplate.update(sql);
    }

    private void cleanupByUsers(String template) {
        cleanupByUsers(template, false);
    }
}



