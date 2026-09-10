package com.xupan.server.game.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.repository.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoGameControllerTest {

    private static final String TEST_USERNAME = "game-test-admin";
    private static final String TEST_PASSWORD = "GamePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired
    private UserRepository userRepository;

    private String accessToken;

    @BeforeEach
    void cleanDatabase() throws Exception {
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)",
                TEST_USERNAME);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", TEST_USERNAME);
        long userId = userRepository.insert(TEST_USERNAME, "游戏回归管理员",
                passwordPolicyService.encode(TEST_PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "ADMIN");
        accessToken = login();
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("UPDATE demo_user_account SET balance = 1000.00 WHERE user_code = 'DEMO-USER'");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + TEST_USERNAME + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
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
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":15.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementStatus").value("PENDING"))
                .andExpect(jsonPath("$.odds").value(3.85));

        mockMvc.perform(get("/api/demo/account").with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(985.00));

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balls[7].number").value(18))
                .andExpect(jsonPath("$.bets[0].settlementStatus").value("WIN"))
                .andExpect(jsonPath("$.bets[0].netProfit").value(42.75));

        mockMvc.perform(get("/api/demo/account").with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1042.75));

        mockMvc.perform(get("/api/demo/admin/accounts/DEMO-USER/ledger").with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationType").value("SETTLEMENT_CREDIT"))
                .andExpect(jsonPath("$[1].operationType").value("BET_DEBIT"));

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidBetAndDrawPayloads() throws Exception {
        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":9,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.00}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/bets").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.001}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(accessToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,21]}"))
                .andExpect(status().isBadRequest());
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
