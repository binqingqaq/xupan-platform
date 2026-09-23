package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.matchesPattern;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class TestPlayerAdminControllerTest {

    private static final String ADMIN = "test-player-admin";
    private static final String MEMBER = "test-player-member";
    private static final String PASSWORD = "TestPlayerPassword123";
    private static final String PLAYER_CODE = "TP-001";

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
        long adminId = userRepository.insert(ADMIN, "测试玩家管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        walletService.ensureWalletForUser(adminId, "测试玩家管理员");
        long memberId = userRepository.insert(MEMBER, "普通成员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(memberId, "USER");
        walletService.ensureWalletForUser(memberId, "普通成员");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void adminCreatesAndQueriesExplicitTestPlayerWhileMemberIsDenied() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);

        mockMvc.perform(post("/api/admin/test-players").header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content("{\"userCode\":\"TP-001\",\"displayName\":\"透明测试玩家\","
                                + "\"avatarKey\":\"avatar-test-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalCode").value(matchesPattern("^wxid_[A-Za-z0-9]{16}$")))
                .andExpect(jsonPath("$.memberCode").value(matchesPattern("^v[0-9]+$")))
                .andExpect(jsonPath("$.userCode").value(PLAYER_CODE))
                .andExpect(jsonPath("$.identityType").value("TEST"))
                .andExpect(jsonPath("$.userStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0.00));

        mockMvc.perform(get("/api/admin/test-players").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].identityType").value("TEST"));

        mockMvc.perform(get("/api/admin/test-players/" + PLAYER_CODE)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalCode").value(matchesPattern("^wxid_[A-Za-z0-9]{16}$")))
                .andExpect(jsonPath("$.memberCode").value(matchesPattern("^v[0-9]+$")))
                .andExpect(jsonPath("$.avatarKey").value("avatar-test-1"))
                .andExpect(jsonPath("$.ledger").isEmpty());

        String memberToken = login(MEMBER, PASSWORD);
        mockMvc.perform(get("/api/admin/test-players").header("Authorization", bearer(memberToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_type FROM sys_user WHERE username = ?", String.class, PLAYER_CODE))
                .isEqualTo("TEST");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT identity_type FROM demo_user_account WHERE user_code = ?", String.class, PLAYER_CODE))
                .isEqualTo("TEST");
    }

    @Test
    void balanceGrantResetAndStatusChangesUseWalletLedgerAndAudit() throws Exception {
        String token = login(ADMIN, PASSWORD);
        createPlayer(token);

        mockMvc.perform(post("/api/admin/test-players/" + PLAYER_CODE + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100.00,\"reason\":\"测试初始化\","
                                + "\"idempotencyKey\":\"TP-GRANT-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));

        mockMvc.perform(post("/api/admin/test-players/" + PLAYER_CODE + "/balance/reset")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"reason\":\"测试归零\",\"idempotencyKey\":\"TP-RESET-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.ledger[0].operationType").value("ADMIN_RESET"));

        mockMvc.perform(patch("/api/admin/test-players/" + PLAYER_CODE + "/status")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.userStatus").value("DISABLED"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger l
                 JOIN demo_user_account a ON a.id = l.user_id
                WHERE a.user_code = ? AND l.operation_type IN ('ADMIN_GRANT', 'ADMIN_RESET')
                """, Integer.class, PLAYER_CODE)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE request_path LIKE '/api/admin/test-players%'
                """, Integer.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void adminManualBetUsesExistingGameAndWalletPathForTestPlayer() throws Exception {
        String token = login(ADMIN, PASSWORD);
        createPlayer(token);

        mockMvc.perform(post("/api/admin/test-players/" + PLAYER_CODE + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100.00,\"reason\":\"下注初始化\","
                                + "\"idempotencyKey\":\"TP-BET-GRANT-001\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/test-players/" + PLAYER_CODE + "/bets")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],"
                                + "\"stake\":10.00,\"idempotencyKey\":\"TP-BET-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementStatus").value("PENDING"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM game_bet b
                 JOIN demo_user_account a ON a.id = b.user_id
                WHERE a.user_code = ?
                """, Integer.class, PLAYER_CODE)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger l
                 JOIN demo_user_account a ON a.id = l.user_id
                WHERE a.user_code = ? AND l.operation_type = 'BET_DEBIT'
                """, Integer.class, PLAYER_CODE)).isEqualTo(1);
    }

    private void createPlayer(String token) throws Exception {
        mockMvc.perform(post("/api/admin/test-players").header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"userCode\":\"TP-001\",\"displayName\":\"透明测试玩家\"}"))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM game_issue");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE identity_type = 'TEST' OR sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MEMBER, PLAYER_CODE);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE user_type = 'TEST' OR username IN (?, ?, ?))",
                ADMIN, MEMBER, PLAYER_CODE);
        jdbcTemplate.update("DELETE FROM sys_user WHERE user_type = 'TEST' OR username IN (?, ?, ?)",
                ADMIN, MEMBER, PLAYER_CODE);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
