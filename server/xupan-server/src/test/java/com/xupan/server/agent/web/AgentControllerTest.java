package com.xupan.server.agent.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentControllerTest {

    private static final String ADMIN = "agent-controller-admin";
    private static final String AGENT_USER = "agent-controller-user";
    private static final String ADMIN_PASSWORD = "AdminPassword123";
    private static final String AGENT_PASSWORD = "AgentPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    private long adminId;
    private long agentId;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "代理管理测试员", passwordPolicy.encode(ADMIN_PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        agentId = agentAdminService.createAgent("AGENT_CONTROLLER", AGENT_USER, "代理测试账号",
                AGENT_PASSWORD, null, adminId).id();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void agentCanReadOwnConsoleButCannotUsePlatformManagement() throws Exception {
        String agentToken = login(AGENT_USER, AGENT_PASSWORD);

        mockMvc.perform(get("/api/agent/me").header("Authorization", bearer(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AGENT_CONTROLLER"))
                .andExpect(jsonPath("$.displayName").value("代理测试账号"));
        mockMvc.perform(get("/api/agent/players").header("Authorization", bearer(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/admin/agents").header("Authorization", bearer(agentToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
    }

    @Test
    void platformAdminCanUseAgentManagementEndpoints() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/admin/agents").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code == 'AGENT_CONTROLLER')]").isNotEmpty());
        mockMvc.perform(post("/api/admin/agent-groups").header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content("{\"code\":\"CONTROLLER_GROUP\",\"displayName\":\"控制器渠道组\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CONTROLLER_GROUP"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN ('agent-controller-admin', 'agent-controller-user'))");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agent-controller-%'))");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agent-controller-%')");
        jdbcTemplate.update("DELETE FROM agent WHERE UPPER(agent_code) IN ('AGENT_CONTROLLER')");
        jdbcTemplate.update("DELETE FROM agent_group WHERE group_code = 'CONTROLLER_GROUP'");
        jdbcTemplate.update("DELETE FROM sys_login_log WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agent-controller-%')");
        jdbcTemplate.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agent-controller-%')");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agent-controller-%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'agent-controller-%'");
    }
}
