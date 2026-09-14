package com.xupan.server.config;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "management.endpoints.web.exposure.include=health,info,metrics")
class ActuatorSecurityTest {

    private static final String USERNAME = "actuator-test-user";
    private static final String USER_PASSWORD = "ActuatorUserPassword123";
    private static final String ADMIN_USERNAME = "actuator-test-admin";
    private static final String ADMIN_PASSWORD = "ActuatorAdminPassword123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordPolicyService passwordPolicy;

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
    void healthIsPublicButInfoAndMetricsRequireMonitorPermission() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());

        String userToken = accessToken(USERNAME, USER_PASSWORD);
        mockMvc.perform(get("/actuator/info").with(bearer(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").with(bearer(userToken)))
                .andExpect(status().isForbidden());

        String adminToken = accessToken(ADMIN_USERNAME, ADMIN_PASSWORD);
        mockMvc.perform(get("/actuator/info").with(bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics/xupan_chat_connections").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("xupan_chat_connections"));
    }

    @Test
    void actuatorResponsesDoNotExposeCredentialsOrEnvironmentDump() throws Exception {
        String adminToken = accessToken(ADMIN_USERNAME, ADMIN_PASSWORD);
        MvcResult result = mockMvc.perform(get("/actuator/metrics").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(USER_PASSWORD, ADMIN_PASSWORD, "XUPAN_DB_PASSWORD", "accessToken", "refreshToken");
    }

    private String accessToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void insertUser(String username, String password, String roleCode) {
        long userId = userRepository.insert(username, username, passwordPolicy.encode(password), "ACTIVE");
        assertThat(userRepository.assignRole(userId, roleCode)).isEqualTo(1);
    }

    private void cleanUsers() {
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
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
