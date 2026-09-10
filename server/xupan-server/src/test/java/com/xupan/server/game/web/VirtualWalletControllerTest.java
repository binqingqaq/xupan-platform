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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VirtualWalletControllerTest {

    private static final String ADMIN = "wallet-controller-test-admin";
    private static final String MEMBER = "wallet-controller-test-member";
    private static final String PASSWORD = "WalletPassword123";

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
        long adminId = userRepository.insert(ADMIN, "钱包管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        long memberId = userRepository.insert(MEMBER, "钱包成员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(memberId, "USER");
        walletService.ensureWalletForUser(adminId, "钱包管理员");
        walletService.ensureWalletForUser(memberId, "钱包成员");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void adminCanGrantWalletAndReplayDoesNotIncreaseBalanceAgain() throws Exception {
        String token = login(ADMIN);
        String body = "{\"amount\":1000.00,\"reason\":\"测试分配\",\"idempotencyKey\":\"wallet-controller-grant-1\"}";

        mockMvc.perform(post("/api/admin/users/" + memberId() + "/wallet/grants")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00))
                .andExpect(jsonPath("$.operationType").value("ADMIN_GRANT"));

        mockMvc.perform(post("/api/admin/users/" + memberId() + "/wallet/grants")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE sys_user_id = ?", BigDecimal.class, memberId()))
                .isEqualByComparingTo("1000.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_balance_ledger WHERE idempotency_key = ?", Integer.class,
                "wallet-controller-grant-1")).isEqualTo(1);
    }

    @Test
    void conflictingIdempotencyKeyIsRejectedWithStableConflictCode() throws Exception {
        String token = login(ADMIN);
        String path = "/api/admin/users/" + memberId() + "/wallet/grants";
        mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"amount\":10.00,\"reason\":\"第一次\",\"idempotencyKey\":\"wallet-controller-conflict-1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"amount\":20.00,\"reason\":\"第二次\",\"idempotencyKey\":\"wallet-controller-conflict-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void ordinaryUserCannotGrantAndCanReadOnlyOwnWallet() throws Exception {
        String token = login(MEMBER);
        mockMvc.perform(post("/api/admin/users/" + memberId() + "/wallet/grants")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"amount\":1.00,\"reason\":\"越权\",\"idempotencyKey\":\"wallet-controller-denied-1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));

        MvcResult result = mockMvc.perform(get("/api/me/wallet")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(memberId()))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andReturn();
        assertThat((Object) JsonPath.read(result.getResponse().getContentAsString(), "$.accountId")).isNotNull();
    }

    @Test
    void legacyBalanceAdjustmentIsRejectedInsteadOfBypassingWalletService() throws Exception {
        String token = login(ADMIN);
        mockMvc.perform(post("/api/demo/admin/accounts/DEMO-USER/balance")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"amount\":1.00,\"reason\":\"旧接口\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_LEGACY_ENDPOINT_DISABLED"));
    }

    @Test
    void serviceResolvesAccountIdBackToSysUserIdForSettlementContract() {
        long memberId = memberId();
        long accountId = walletService.getForAdmin(memberId).accountId();
        assertThat(walletService.getByAccountId(accountId).userId()).isEqualTo(memberId);
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private long memberId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, MEMBER);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM sys_login_log WHERE username_snapshot IN (?, ?)", ADMIN, MEMBER);
        jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, MEMBER);
        jdbcTemplate.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, MEMBER);
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, MEMBER);
        jdbcTemplate.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?)))", ADMIN, MEMBER);
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, MEMBER);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, MEMBER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN (?, ?)", ADMIN, MEMBER);
    }
}
