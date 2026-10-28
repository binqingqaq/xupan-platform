package com.xupan.server.report.service;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Center account sharing and machine-scoped login session behavior. */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class UnifiedReportInheritanceTest {

    private static final String ADMIN = "ufinh-admin";
    private static final String PASSWORD = "UnifiedReportTest123";
    private static final String CENTER_USERNAME = "ufinh-center-account";
    private static final String CENTER_PASSWORD = "UnifiedCenterPassword123";
    private static final String GROUP_ALPHA_CODE = "UFINH_GROUP_ALPHA";
    private static final String GROUP_BETA_CODE = "UFINH_GROUP_BETA";

    private static final String MACHINE_A_USER = "ufinh-machine-a";
    private static final String MACHINE_B_USER = "ufinh-machine-b";
    private static final String MACHINE_C_USER = "ufinh-machine-c";
    private static final String MACHINE_A_CODE = "UFINH_A";
    private static final String MACHINE_B_CODE = "UFINH_B";
    private static final String MACHINE_C_CODE = "UFINH_C";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private CenterUpstreamAccountService centerAccountService;

    private long adminId;
    private long groupAlphaId;
    private long groupBetaId;
    private long machineAId;
    private long machineBId;
    private long machineCId;
    private long machineAUserId;
    private long machineBUserId;
    private long machineCUserId;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "中心账号测试管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        groupAlphaId = agentAdminService.createGroup(GROUP_ALPHA_CODE, "中心账号共享组", adminId).id();
        groupBetaId = agentAdminService.createGroup(GROUP_BETA_CODE, "中心账号隔离组", adminId).id();
        machineAId = createMachine(MACHINE_A_CODE, MACHINE_A_USER, "中心测试机器A", groupAlphaId);
        machineBId = createMachine(MACHINE_B_CODE, MACHINE_B_USER, "中心测试机器B", groupAlphaId);
        machineCId = createMachine(MACHINE_C_CODE, MACHINE_C_USER, "中心测试机器C", groupBetaId);
        machineAUserId = accountUserId(machineAId);
        machineBUserId = accountUserId(machineBId);
        machineCUserId = accountUserId(machineCId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void sameGroupMachinesShareCenterAccountButKeepIndependentSessions() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long centerAccountId = createCenterAccount(adminToken, groupAlphaId);
        String machineAToken = login(MACHINE_A_USER, PASSWORD);
        String machineBToken = login(MACHINE_B_USER, PASSWORD);

        reportLogin(machineAToken);
        reportLogin(machineBToken);

        assertThat(activeSessionCount(centerAccountId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT center_agent_id) "
                        + "FROM center_machine_session WHERE center_agent_id = ? AND revoked_at IS NULL",
                Long.class, centerAccountId)).isEqualTo(1L);
        CenterUpstreamAccountService.AccountView machineAAccount =
                centerAccountService.currentForMachine(machineAUserId).orElseThrow();
        CenterUpstreamAccountService.AccountView machineBAccount =
                centerAccountService.currentForMachine(machineBUserId).orElseThrow();
        assertThat(machineAAccount.accountId()).isEqualTo(centerAccountId);
        assertThat(machineBAccount.accountId()).isEqualTo(centerAccountId);
        assertThat(machineAAccount.onlineMachineCount()).isEqualTo(2);
        assertThat(machineBAccount.onlineMachineCount()).isEqualTo(2);

        mockMvc.perform(post("/api/admin/player-desk/report/logout")
                        .header("Authorization", bearer(machineAToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(false));

        assertThat(centerAccountService.currentForMachine(machineAUserId).orElseThrow().online()).isFalse();
        CenterUpstreamAccountService.AccountView afterLogout =
                centerAccountService.currentForMachine(machineBUserId).orElseThrow();
        assertThat(afterLogout.online()).isTrue();
        assertThat(afterLogout.onlineMachineCount()).isEqualTo(1);
        mockMvc.perform(get("/api/admin/player-desk/report")
                        .header("Authorization", bearer(machineBToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true))
                .andExpect(jsonPath("$.centerAccountUsername").value(CENTER_USERNAME));
    }

    @Test
    void machineFromAnotherGroupCannotLoginToTheCenterAccount() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long centerAccountId = createCenterAccount(adminToken, groupAlphaId);
        String otherGroupMachineToken = login(MACHINE_C_USER, PASSWORD);

        mockMvc.perform(post("/api/admin/player-desk/report/login")
                        .header("Authorization", bearer(otherGroupMachineToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + CENTER_USERNAME + "\",\"password\":\""
                                + CENTER_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CENTER_UPSTREAM_AUTH_INVALID"));

        assertThat(activeSessionCount(centerAccountId)).isZero();
        assertThat(centerAccountService.currentForMachine(machineCUserId)).isEmpty();
    }

    private long createCenterAccount(String adminToken, long groupId) throws Exception {
        MvcResult response = mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"username\":\"" + CENTER_USERNAME
                                + "\",\"password\":\"" + CENTER_PASSWORD
                                + "\",\"initialQuota\":500.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.groupId").value(groupId))
                .andExpect(jsonPath("$.account.username").value(CENTER_USERNAME))
                .andReturn();
        return ((Number) JsonPath.read(response.getResponse().getContentAsString(),
                "$.account.accountId")).longValue();
    }

    private void reportLogin(String machineToken) throws Exception {
        mockMvc.perform(post("/api/admin/player-desk/report/login")
                        .header("Authorization", bearer(machineToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + CENTER_USERNAME + "\",\"password\":\""
                                + CENTER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true))
                .andExpect(jsonPath("$.centerAccountUsername").value(CENTER_USERNAME));
    }

    private long createMachine(String code, String username, String displayName, long groupId) {
        return agentAdminService.createAgent(code, username, displayName, PASSWORD, groupId, adminId).id();
    }

    private long accountUserId(long machineId) {
        return jdbc.queryForObject("SELECT account_user_id FROM agent WHERE id = ?", Long.class, machineId);
    }

    private long activeSessionCount(long centerAccountId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_machine_session
                 WHERE center_agent_id = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                """, Long.class, centerAccountId);
        return count == null ? 0L : count;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_risk_hit WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_machine_session WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_USERNAME);
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_USERNAME);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code LIKE 'UFINH_%')");
        jdbc.update("DELETE FROM agent WHERE agent_code LIKE 'UFINH_%'");
        jdbc.update("DELETE FROM agent_group WHERE group_code LIKE 'UFINH_GROUP_%'");
        List<Long> userIds = jdbc.queryForList("SELECT id FROM sys_user WHERE username LIKE 'ufinh-%'", Long.class);
        if (!userIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
            Object[] ids = userIds.toArray();
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM auth_ws_ticket WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (" + placeholders + ")", ids);
            jdbc.update("DELETE FROM sys_user WHERE id IN (" + placeholders + ")", ids);
        }
    }
}
