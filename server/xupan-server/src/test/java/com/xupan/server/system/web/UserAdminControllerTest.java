package com.xupan.server.system.web;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAdminControllerTest {

    private static final String ADMIN = "user-admin-controller-admin";
    private static final String MEMBER = "user-admin-controller-member";
    private static final String ADMIN_PASSWORD = "AdminPassword123";
    private static final String MEMBER_PASSWORD = "MemberPassword123";

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
        insertUser(ADMIN, "用户管理员", ADMIN_PASSWORD, "ADMIN");
        insertUser(MEMBER, "测试成员", MEMBER_PASSWORD, "USER");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void adminCanListDetailAndReadActiveRolesWithoutSecrets() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token))
                        .param("keyword", "测试成员").param("page", "1").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value(MEMBER))
                .andExpect(jsonPath("$.items[0].roles[0]").value("USER"))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.total").value(1));

        long memberId = memberId();
        mockMvc.perform(get("/api/admin/users/" + memberId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(MEMBER))
                .andExpect(jsonPath("$.wallet.balance").value(0.00))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(get("/api/admin/roles").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'USER')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.code == 'ADMIN')]").isNotEmpty());
    }

    @Test
    void adminCreatesNormalUserWithZeroWalletAndRejectsDuplicateAndInvalidQuery() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        String body = "{\"username\":\"user-admin-controller-created\","
                + "\"displayName\":\"新成员\",\"rawPassword\":\"CreatedPassword123\","
                + "\"operatorUserId\":999,\"walletBalance\":999999,\"initialBalance\":999999,"
                + "\"passwordHash\":\"bad\"}";

        mockMvc.perform(post("/api/admin/users").header("Authorization", bearer(token))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.wallet.balance").value(0.00))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(post("/api/admin/users").header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"username\":\"user-admin-controller-created\","
                                + "\"displayName\":\"重复\",\"rawPassword\":\"CreatedPassword123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_EXISTS"));

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token))
                        .param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_QUERY_INVALID"));
    }

    @Test
    void ordinaryUserCannotUseAnyUserManagementEndpoint() throws Exception {
        String token = login(MEMBER, MEMBER_PASSWORD);
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
        mockMvc.perform(get("/api/admin/roles").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
    }

    @Test
    void statusPasswordAndRoleChangesRevokeExistingAccessTokens() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        String memberToken = login(MEMBER, MEMBER_PASSWORD);
        long memberId = memberId();

        mockMvc.perform(patch("/api/admin/users/" + memberId + "/status")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(memberToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/admin/users/" + memberId + "/status")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/users/" + memberId + "/password")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"rawPassword\":\"ChangedPassword123\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + MEMBER + "\",\"password\":\"" + MEMBER_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        String changedToken = login(MEMBER, "ChangedPassword123");
        mockMvc.perform(put("/api/admin/users/" + memberId + "/roles")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"roleCodes\":[\"OPERATOR\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(changedToken)))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE operator_user_id = ?", Integer.class,
                adminId())).isGreaterThanOrEqualTo(4);
    }

    @Test
    void protectsSelfAndLastAdministratorOperationsWithStableErrors() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        long adminId = adminId();

        mockMvc.perform(patch("/api/admin/users/" + adminId + "/status")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_SELF_OPERATION_FORBIDDEN"));

        mockMvc.perform(put("/api/admin/users/" + adminId + "/roles")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"roleCodes\":[\"USER\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_SELF_OPERATION_FORBIDDEN"));
    }

    @Test
    void rejectsInvalidPasswordRoleStatusAndMissingUserWithStableErrors() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(post("/api/admin/users/" + memberId() + "/password")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"rawPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_PASSWORD_INVALID"));
        mockMvc.perform(put("/api/admin/users/" + memberId() + "/roles")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"roleCodes\":[\"MISSING_ROLE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_ROLE_INVALID"));
        mockMvc.perform(patch("/api/admin/users/999999/status")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void insertUser(String username, String displayName, String password, String role) {
        long id = userRepository.insert(username, displayName, passwordPolicy.encode(password), "ACTIVE");
        assertThat(userRepository.assignRole(id, role)).isEqualTo(1);
        walletService.ensureWalletForUser(id, displayName);
    }

    private long adminId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
    }

    private long memberId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, MEMBER);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("DELETE FROM demo_user_account");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
