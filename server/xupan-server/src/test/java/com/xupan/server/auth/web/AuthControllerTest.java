package com.xupan.server.auth.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    private static final String USERNAME = "auth-test-user";
    private static final String USER_PASSWORD = "AuthPassword123";
    private static final String ADMIN_USERNAME = "auth-test-admin";
    private static final String ADMIN_PASSWORD = "AdminPassword123";

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
        cleanUsers();
        insertUser(USERNAME, USER_PASSWORD, "USER");
        insertUser(ADMIN_USERNAME, ADMIN_PASSWORD, "ADMIN");
    }

    @AfterEach
    void tearDown() {
        cleanUsers();
    }

    @Test
    void loginReturnsAccessTokenAndHttpOnlyRefreshCookieWithoutRefreshTokenBody() throws Exception {
        MvcResult result = loginResult(USERNAME, USER_PASSWORD);

        assertThat(result.getResponse().getCookie(AuthController.REFRESH_COOKIE)).satisfies(cookie -> {
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getSecure()).isFalse();
            assertThat(cookie.getPath()).isEqualTo("/api/auth");
            assertThat(cookie.getMaxAge()).isEqualTo(30 * 24 * 60 * 60);
        });
        assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken", USER_PASSWORD);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken")).isNotBlank();
        assertThat((java.util.List<?>) JsonPath.read(result.getResponse().getContentAsString(), "$.user.roles"))
                .isEqualTo(java.util.List.of("USER"));
        assertThat((java.util.List<String>) JsonPath.read(result.getResponse().getContentAsString(), "$.user.permissions"))
                .contains("GAME_CURRENT_READ");
    }

    @Test
    void loginFailureDoesNotRevealWhetherUsernameExists() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"WrongPassword123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn();
        MvcResult unknownUser = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"missing-auth-user\",\"password\":\"WrongPassword123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn();

        assertThat(wrongPassword.getResponse().getContentAsString())
                .doesNotContain(USER_PASSWORD, "password_hash", "accessToken");
        assertThat(unknownUser.getResponse().getContentAsString())
                .doesNotContain(USER_PASSWORD, "password_hash", "accessToken");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE username_snapshot = ?", Integer.class, USERNAME))
                .isEqualTo(1);
    }

    @Test
    void disabledUserCannotLoginAndFifthFailureLocksTheAccount() throws Exception {
        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE username = ?", USERNAME);
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        jdbcTemplate.update("UPDATE sys_user SET status = 'ACTIVE' WHERE username = ?", USERNAME);
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"" + USERNAME + "\",\"password\":\"WrongPassword123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE username = ?", String.class, USERNAME))
                .isEqualTo("LOCKED");
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void refreshRotatesCookieAndOldCookieCannotBeReplayed() throws Exception {
        MvcResult login = loginResult(USERNAME, USER_PASSWORD);
        Cookie originalCookie = login.getResponse().getCookie(AuthController.REFRESH_COOKIE);

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(originalCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        assertThat(refreshed.getResponse().getCookie(AuthController.REFRESH_COOKIE)).isNotNull();
        assertThat((String) JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken"))
                .isNotEqualTo((String) JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken"));
        mockMvc.perform(post("/api/auth/refresh").cookie(originalCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REVOKED"));
    }

    @Test
    void meReadsCurrentServerUserAndLogoutRevokesAccessToken() throws Exception {
        MvcResult login = loginResult(USERNAME, USER_PASSWORD);
        String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/auth/me").with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.permissions").isArray());

        mockMvc.perform(post("/api/auth/logout").with(bearer(accessToken)))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getCookie(AuthController.REFRESH_COOKIE)
                        .getMaxAge()).isZero());
        mockMvc.perform(get("/api/auth/me").with(bearer(accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        mockMvc.perform(post("/api/auth/logout").with(bearer(accessToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void wsTicketIsReturnedOnceAndIsBoundToAuthenticatedSession() throws Exception {
        MvcResult login = loginResult(USERNAME, USER_PASSWORD);
        String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        MvcResult result = mockMvc.perform(post("/api/auth/ws-ticket")
                        .param("roomCode", "room-a").with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(60))
                .andReturn();

        String ticket = JsonPath.read(result.getResponse().getContentAsString(), "$.ticket");
        assertThat(result.getRequest().getRequestURI()).isEqualTo("/api/auth/ws-ticket");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ticket_hash FROM auth_ws_ticket WHERE user_id = (SELECT id FROM sys_user WHERE username = ?)",
                String.class, USERNAME)).isNotEqualTo(ticket).hasSize(64);
    }

    @Test
    void anonymousRequestsReturn401AndOrdinaryUserCannotCallAdminApi() throws Exception {
        mockMvc.perform(get("/api/demo/game/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));

        String accessToken = JsonPath.read(loginResult(USERNAME, USER_PASSWORD)
                .getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/demo/game/current").with(bearer(accessToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/admin/accounts").with(bearer(accessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
    }

    @Test
    void adminCanAccessProtectedDemoAdministrativeApi() throws Exception {
        String accessToken = JsonPath.read(loginResult(ADMIN_USERNAME, ADMIN_PASSWORD)
                .getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/demo/admin/accounts").with(bearer(accessToken)))
                .andExpect(status().isOk());
    }

    private MvcResult loginResult(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void insertUser(String username, String password, String roleCode) {
        long userId = userRepository.insert(username, username, passwordPolicy.encode(password), "ACTIVE");
        assertThat(userRepository.assignRole(userId, roleCode)).isEqualTo(1);
        walletService.ensureWalletForUser(userId, username);
    }

    private void cleanUsers() {
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE user_code <> 'DEMO-USER'");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
