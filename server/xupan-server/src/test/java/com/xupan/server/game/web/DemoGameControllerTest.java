package com.xupan.server.game.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.game.service.VirtualWalletService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class DemoGameControllerTest {

    private static final String TEST_USERNAME = "game-test-admin";
    private static final String TEST_PASSWORD = "GamePassword123";
    private static final String SECOND_USERNAME = "game-test-member";
    private static final String SECOND_PASSWORD = "GameMemberPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VirtualWalletService walletService;

    private String accessToken;
    private long testUserId;

    @BeforeEach
    void cleanDatabase() throws Exception {
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM sys_login_log WHERE username_snapshot IN (?, ?)",
                TEST_USERNAME, SECOND_USERNAME);
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", TEST_USERNAME, SECOND_USERNAME);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", TEST_USERNAME, SECOND_USERNAME);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN (?, ?)", TEST_USERNAME, SECOND_USERNAME);
        testUserId = userRepository.insert(TEST_USERNAME, "游戏回归管理员",
                passwordPolicyService.encode(TEST_PASSWORD), "ACTIVE");
        userRepository.assignRole(testUserId, "ADMIN");
        walletService.ensureWalletForUser(testUserId, "游戏回归管理员");
        walletService.grant(testUserId, testUserId, new java.math.BigDecimal("1000.00"),
                "游戏回归测试初始化", "GAME-TEST-SETUP");
        accessToken = login();
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
    }

    private String login() throws Exception {
        return login(TEST_USERNAME, TEST_PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    @Test
    void completesBetDrawAndRejectsClosedOperations() throws Exception {
        mockMvc.perform(get("/api/demo/game/current").with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueNumber").value("3000000"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(put("/api/demo/game/admin/odds/FAN").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"odds\":3.850}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.odds").value(3.85));

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":15.00,\"idempotencyKey\":\"BET-REQUEST-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementStatus").value("PENDING"))
                .andExpect(jsonPath("$.odds").value(3.85));

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":15.00,\"idempotencyKey\":\"BET-REQUEST-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementStatus").value("PENDING"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE request_idempotency_key = 'BET-REQUEST-001'", Integer.class))
                .isEqualTo(1);

        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, testUserId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM game_bet WHERE issue_number = '3000000'", Long.class))
                .isEqualTo(accountId);

        mockMvc.perform(get("/api/demo/game/current").with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.balance").value(985.00));

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balls[7].number").value(18))
                .andExpect(jsonPath("$.bets[0].settlementStatus").value("WIN"))
                .andExpect(jsonPath("$.bets[0].netProfit").value(42.75))
                .andExpect(jsonPath("$.account.balance").value(1042.75));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger l
                 JOIN demo_user_account a ON a.id = l.user_id
                WHERE a.sys_user_id = ? AND l.operation_type = 'SETTLEMENT_CREDIT'
                """, Integer.class, testUserId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger l
                 JOIN demo_user_account a ON a.id = l.user_id
                WHERE a.sys_user_id = ? AND l.operation_type = 'BET_DEBIT'
                """, Integer.class, testUserId)).isEqualTo(1);

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.00,\"idempotencyKey\":\"BET-CLOSED-001\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void manualSettlementCreditsEachBetOwnerWallet() throws Exception {
        long secondUserId = userRepository.insert(SECOND_USERNAME, "第二游戏用户",
                passwordPolicyService.encode(SECOND_PASSWORD), "ACTIVE");
        userRepository.assignRole(secondUserId, "USER");
        walletService.ensureWalletForUser(secondUserId, "第二游戏用户");
        walletService.grant(testUserId, secondUserId, new java.math.BigDecimal("1000.00"),
                "双用户结算测试初始化", "GAME-TEST-SECOND-SETUP");
        String secondAccessToken = login(SECOND_USERNAME, SECOND_PASSWORD);

        mockMvc.perform(put("/api/demo/game/admin/odds/FAN").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"odds\":3.850}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":10.00,\"idempotencyKey\":\"GAME-TEST-TWO-A\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/demo/game/bets").with(bearer(secondAccessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":10.00,\"idempotencyKey\":\"GAME-TEST-TWO-B\"}"))
                .andExpect(status().isCreated());

        Long firstAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, testUserId);
        Long secondAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, secondUserId);

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class,
                firstAccountId)).isEqualByComparingTo("1028.50");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", java.math.BigDecimal.class,
                secondAccountId)).isEqualByComparingTo("1028.50");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE user_id IN (?, ?)", Integer.class,
                firstAccountId, secondAccountId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE operation_type = 'SETTLEMENT_CREDIT'
                   AND user_id IN (?, ?)
                """, Integer.class, firstAccountId, secondAccountId)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidBetAndDrawPayloads() throws Exception {
        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":9,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.00,\"idempotencyKey\":\"BET-INVALID-BALL\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.001,\"idempotencyKey\":\"BET-INVALID-SCALE\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,21]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsReplayWithDifferentBetParametersUsingStableConflictCode() throws Exception {
        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":15.00,\"idempotencyKey\":\"BET-CONFLICT-001\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":16.00,\"idempotencyKey\":\"BET-CONFLICT-001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_IDEMPOTENCY_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE request_idempotency_key = 'BET-CONFLICT-001'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rollsBackBetWhenAuthenticatedUsersWalletCannotCoverStake() throws Exception {
        walletService.adjust(testUserId, testUserId, new java.math.BigDecimal("-1000.00"),
                "游戏回归测试清空余额", "GAME-TEST-EMPTY");

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":15.00,\"idempotencyKey\":\"BET-INSUFFICIENT-001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_INSUFFICIENT_BALANCE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_bet WHERE user_id = "
                        + "(SELECT id FROM demo_user_account WHERE sys_user_id = ?)",
                Integer.class, testUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger l
                 JOIN demo_user_account a ON a.id = l.user_id
                WHERE a.sys_user_id = ? AND l.operation_type = 'BET_DEBIT'
                """, Integer.class, testUserId)).isZero();
    }

    @Test
    void rejectsLegacyDemoBalanceAdjustmentInsteadOfBypassingWalletService() throws Exception {
        mockMvc.perform(post("/api/demo/admin/accounts/DEMO-USER/balance").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"amount\":125.50,\"reason\":\"验收初始化\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_LEGACY_ENDPOINT_DISABLED"));
    }
}
