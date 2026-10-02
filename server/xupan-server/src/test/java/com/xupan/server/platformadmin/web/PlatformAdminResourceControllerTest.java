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
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                                 "displayName":"测试子账号","score":1000,"subAccountManage":true,
                                 "machineManage":true,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("platform_test_sub"))
                .andReturn();
        long subId = ((Number) JsonPath.read(subResult.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_machine\",\"rawPassword\":\"MachinePassword123\","
                                + "\"displayName\":\"测试机器\",\"groupId\":" + subId
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
                .andExpect(jsonPath("$.groupUsername").value("platform_test_sub"))
                .andExpect(jsonPath("$.requestedBotCount").value(2));

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
                     closed_at, settled_at)
                VALUES ('39999946', 'CLOSED', 'SETTLED', 1, 2, 3, 4, 5, 6, 7, 18,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
        long groupId = insertAndReturnId("""
                INSERT INTO agent_group (group_code, display_name, status, username)
                VALUES ('PLATFORM_TEST_REPORT_GROUP', '报表测试子账号', 'ACTIVE', 'platform_test_report')
                """);
        long machineUserId = userRepository.insert("platform-admin-test-report-machine", "报表测试机器",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        long machineId = insertAndReturnId("""
                INSERT INTO agent (agent_code, display_name, group_id, account_user_id, system_owned, status)
                VALUES ('PLATFORM_TEST_REPORT_MACHINE', '报表测试机器', ?, ?, FALSE, 'ACTIVE')
                """, groupId, machineUserId);
        long playerUserId = userRepository.insert("platform-admin-test-report-player", "报表测试玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind, member_code)
                VALUES ('PLATFORM_TEST_REPORT_PLAYER', '报表测试玩家', 100.00, 'ACTIVE', ?, ?,
                        'REAL', 'NORMAL', 'V-REPORT-001')
                """, playerUserId, machineId);
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_FAN", "FAN", "1", "10.00", "3.850",
                "28.50", "WIN", "0.1000", "0.0000");
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_CAR", "CAR", "1,2,4", "12.00", "1.950",
                "-12.00", "LOSE", "0.1000", "0.0000");
        insertReportBet(accountId, "PLATFORM_TEST_REPORT_TONG", "NONE", "1,2,3", "20.00", "1.950",
                "10.00", "WIN", "0.2000", "0.0000");
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/reports/profit")
                        .header("Authorization", bearer(token))
                        .param("day", "2026-10-02").param("subAccountId", Long.toString(groupId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines[0].totalFlow").value(42.00))
                .andExpect(jsonPath("$.machines[0].totalSingleFlow").value(38.50))
                .andExpect(jsonPath("$.machines[0].totalDoubleFlow").value(52.00))
                .andExpect(jsonPath("$.machines[0].totalProfit").value(26.50))
                .andExpect(jsonPath("$.machines[0].totalFanShui").value(0.05))
                .andExpect(jsonPath("$.totalDoubleFlow").value(52.00))
                .andExpect(jsonPath("$.totalFanShui").value(0.05));
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
                .andExpect(jsonPath("$.items[0].reportStatus").value("UNREPORTED"));

        mockMvc.perform(delete("/api/admin/unsettled-orders/" + betId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId))
                .isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT amount FROM demo_balance_ledger
                 WHERE related_bet_id = ? AND operation_type = 'SETTLEMENT_REVERSAL'
                """, java.math.BigDecimal.class, betId)).isEqualByComparingTo("100.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("1100.00");
        mockMvc.perform(get("/api/admin/unsettled-orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(delete("/api/admin/unsettled-orders/" + betId).header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
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
    void previewsAndSoftDeletesAllNonRootAccountsWithBy220Confirmation() throws Exception {
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
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已软删除")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_group WHERE id = ? AND deleted_at IS NOT NULL", Long.class, subId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE id = ? AND deleted_at IS NOT NULL", Long.class, machineId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM demo_user_account WHERE sys_user_id = ?", String.class, playerUserId))
                .isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE username = 'platform_test_bulk_sub'", String.class))
                .isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE username = ?", String.class, ADMIN))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE system_owned = TRUE AND deleted_at IS NULL", Long.class))
                .isEqualTo(1);

        mockMvc.perform(post("/api/admin/settings/delete-all-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"confirm\":\"DELETE_ALL_ACCOUNTS\",\"preview\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.admins").value(0))
                .andExpect(jsonPath("$.counts.robots").value(0))
                .andExpect(jsonPath("$.counts.players").value(0));
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
    private void insertReportBet(long accountId, String betCode, String playType, String parameters,
                                 String stake, String odds, String netProfit, String status,
                                 String rebateRate, String specialRebateRate) {
        jdbcTemplate.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, net_profit,
                     settlement_status, rebate_rate_snapshot, special_rebate_rate_snapshot,
                     created_at, settled_at)
                VALUES (?, ?, ?, '39999949', 1, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, accountId, betCode, betCode, playType, parameters, new java.math.BigDecimal(stake),
                new java.math.BigDecimal(odds), new java.math.BigDecimal(netProfit), status,
                new java.math.BigDecimal(rebateRate), new java.math.BigDecimal(specialRebateRate));
    }
    private long insertAndReturnId(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void softDeletesEmptyMachineAndSubAccountButBlocksSubAccountWithMachines() throws Exception {
        String token = login(ADMIN, PASSWORD);
        MvcResult subResult = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("""
                                {"username":"platform_test_delete_sub","rawPassword":"SubPassword123",
                                 "displayName":"删除测试子账号","score":0,"subAccountManage":false,
                                 "machineManage":false,"unifiedReportEnabled":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        long subId = ((Number) JsonPath.read(subResult.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult machineResult = mockMvc.perform(post("/api/admin/machines")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_machine\",\"rawPassword\":\"MachinePassword123\","
                                + "\"displayName\":\"删除测试机器\",\"groupId\":" + subId
                                + ",\"score\":0,\"boardOpen\":true,\"robotManage\":false,\"chaseEnabled\":false,"
                                + "\"botCount\":0,\"closeSeconds\":0,\"cancelSeconds\":0,\"rebateRate\":0,"
                                + "\"oddsRate\":0,\"specialRebateRate\":0,\"specialOddsRate\":0,"
                                + "\"totalLimit\":0,\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,"
                                + "\"bigSmallLimit\":0,\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":0,"
                                + "\"playerMinStake\":0,\"games\":[\"AU8\"]}"))
                .andExpect(status().isOk()).andReturn();
        long machineId = ((Number) JsonPath.read(machineResult.getResponse().getContentAsString(), "$.id")).longValue();

        long playerUserId = userRepository.insert("platform_test_delete_player", "删除依赖玩家",
                passwordPolicy.encode("PlayerPassword123"), "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (user_code, display_name, balance, status, sys_user_id, identity_type, player_kind,
                     member_code, agent_id)
                VALUES ('PLATFORM_TEST_DELETE_PLAYER', '删除依赖玩家', 0, 'ACTIVE', ?, 'REAL', 'NORMAL',
                        'TP-DELETE-PLAYER', ?)
                """, playerUserId, machineId);

        mockMvc.perform(delete("/api/admin/machines/" + machineId).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MACHINE_HAS_PLAYERS"));
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id = ?", playerUserId);

        mockMvc.perform(delete("/api/admin/sub-accounts/" + subId).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUB_ACCOUNT_HAS_MACHINES"));

        mockMvc.perform(delete("/api/admin/machines/" + machineId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + machineId + ")]").isEmpty());
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_machine\",\"password\":\"MachinePassword123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/admin/sub-accounts/" + subId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/sub-accounts").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + subId + ")]").isEmpty());
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"platform_test_delete_sub\",\"password\":\"SubPassword123\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE id = ? AND deleted_at IS NOT NULL", Long.class, machineId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_group WHERE id = ? AND deleted_at IS NOT NULL", Long.class, subId))
                .isEqualTo(1);
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
    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
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
        jdbcTemplate.update("DELETE FROM game_issue WHERE issue_number LIKE '399999%'");
        jdbcTemplate.update("DELETE FROM game_definition WHERE game_code LIKE 'PLATFORM_TEST_%'");
        jdbcTemplate.update("DELETE FROM agent_game WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_%')");
        cleanupByUsers("DELETE FROM demo_user_account WHERE sys_user_id IN %s");
        jdbcTemplate.update("DELETE FROM agent WHERE UPPER(agent_code) LIKE 'PLATFORM_TEST_%'");
        jdbcTemplate.update("DELETE FROM agent_group WHERE username LIKE 'platform_test_%'");
        cleanupByUsers("DELETE FROM auth_session WHERE user_id IN %s");
        cleanupByUsers("DELETE FROM sys_user_role WHERE user_id IN %s");
        cleanupByUsers("DELETE FROM sys_user WHERE id IN %s");
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



