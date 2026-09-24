package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerQuickBetPreferenceControllerTest {

    private static final String USERNAME = "quick-bet-preference-user";
    private static final String PASSWORD = "QuickBetPreference123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @BeforeEach
    void setUp() {
        clean();
        long userId = userRepository.insert(USERNAME, "快捷偏好玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "USER");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void returnsDefaultsThenPersistsPerUserAmounts() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/me/quick-bet-preferences")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amounts[0]").value(50.00))
                .andExpect(jsonPath("$.amounts[4]").value(1000.00));

        mockMvc.perform(put("/api/me/quick-bet-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amounts\":[10,20,30,40,50]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amounts[0]").value(10.00))
                .andExpect(jsonPath("$.amounts[4]").value(50.00));

        mockMvc.perform(get("/api/me/quick-bet-preferences")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amounts[2]").value(30.00));
    }

    @Test
    void requiresAuthenticationAndRejectsInvalidAmountCounts() throws Exception {
        mockMvc.perform(get("/api/me/quick-bet-preferences"))
                .andExpect(status().isUnauthorized());

        String token = login();
        mockMvc.perform(put("/api/me/quick-bet-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amounts\":[10,20]}"))
                .andExpect(status().isBadRequest());
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void clean() {
        jdbc.update("DELETE FROM player_quick_bet_preference WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM sys_login_log");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
