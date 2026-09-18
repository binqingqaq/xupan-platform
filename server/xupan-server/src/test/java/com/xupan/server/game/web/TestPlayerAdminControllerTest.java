package com.xupan.server.game.web;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestPlayerAdminControllerTest {

    private static final String ADMIN = "test-player-admin";
    private static final String ADMIN_PASSWORD = "TestPlayerAdmin123";
    private static final String MEMBER = "test-player-member";
    private static final String MEMBER_PASSWORD = "TestPlayerMember123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private VirtualWalletService walletService;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "测试玩家管理员",
                passwordPolicy.encode(ADMIN_PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        walletService.ensureWalletForUser(adminId, "测试玩家管理员");
        long memberId = userRepository.insert(MEMBER, "普通成员",
                passwordPolicy.encode(MEMBER_PASSWORD), "ACTIVE");
        userRepository.assignRole(memberId, "USER");
        walletService.ensureWalletForUser(memberId, "普通成员");
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM game_issue");
        jdbcTemplate.update("DELETE FROM game_odds");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void adminManagesTransparentTestPlayerAndUsesExistingBetSettlementChain() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/admin/test-players")
                        .with(bearer(adminToken))
                        .contentType("application/json")
                        .content("{\"displayName\":\"透明玩家一号\",\"userCode\":\"TP-ONE\","
                                + "\"avatarKey\":\"avatar-test-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityType").value("TEST"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andReturn();
        long playerId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.accountId")).longValue();
        String playerCode = "TP-ONE";

        mockMvc.perform(get("/api/admin/test-players").with(bearer(adminToken))
                        .param("keyword", "TP-ONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].identityType").value("TEST"))
                .andExpect(jsonPath("$.items[0].avatarKey").value("avatar-test-1"));

        mockMvc.perform(post("/api/admin/test-players/" + playerCode + "/balance/grants")
                        .with(bearer(adminToken)).contentType("application/json")
                        .content("{\"amount\":100.00,\"reason\":\"测试初始额度\","
                                + "\"idempotencyKey\":\"TP-GRANT-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00))
                .andExpect(jsonPath("$.ledger[0].operationType").value("ADMIN_GRANT"));

        mockMvc.perform(post("/api/admin/test-players/" + playerCode + "/balance/reset")
                        .with(bearer(adminToken)).contentType("application/json")
                        .content("{\"reason\":\"测试重置余额\",\"idempotencyKey\":\"TP-RESET-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.ledger[0].operationType").value("ADMIN_RESET"))
                .andExpect(jsonPath("$.ledger[0].amount").value(-100.00));

        mockMvc.perform(post("/api/admin/test-players/" + playerCode + "/balance/grants")
                        .with(bearer(adminToken)).contentType("application/json")
                        .content("{\"amount\":100.00,\"reason\":\"下注额度\","
                                + "\"idempotencyKey\":\"TP-GRANT-2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/game/current").with(bearer(adminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/test-players/" + playerCode + "/bets")
                        .with(bearer(adminToken)).contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],"
                                + "\"stake\":10.00,\"idempotencyKey\":\"TP-BET-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementStatus").value("PENDING"));

        mockMvc.perform(post("/api/demo/game/admin/draw").with(bearer(adminToken))
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,8]}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT balance FROM demo_user_account WHERE id = ?
                """, BigDecimal.class, playerId)).isEqualByComparingTo("128.50");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE user_id = ? AND operation_type IN ('ADMIN_GRANT', 'ADMIN_RESET', 'BET_DEBIT', 'SETTLEMENT_CREDIT')
                """, Integer.class, playerId)).isEqualTo(5);

        mockMvc.perform(patch("/api/admin/test-players/" + playerCode + "/status")
                        .with(bearer(adminToken)).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(post("/api/admin/test-players/" + playerCode + "/bets")
                        .with(bearer(adminToken)).contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],"
                                + "\"stake\":10.00,\"idempotencyKey\":\"TP-BET-DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_PLAYER_DISABLED"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE request_path LIKE '/api/admin/test-players/%'
                   AND result = 'SUCCESS'
                """, Integer.class)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void ordinaryUserCannotListOrCreateTestPlayers() throws Exception {
        String memberToken = login(MEMBER, MEMBER_PASSWORD);
        mockMvc.perform(get("/api/admin/test-players").with(bearer(memberToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
        mockMvc.perform(post("/api/admin/test-players").with(bearer(memberToken))
                        .contentType("application/json")
                        .content("{\"displayName\":\"越权\",\"userCode\":\"TP-DENIED\"}"))
                .andExpect(status().isForbidden());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM demo_user_account");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM game_issue");
        jdbcTemplate.update("DELETE FROM game_odds");
    }
}
