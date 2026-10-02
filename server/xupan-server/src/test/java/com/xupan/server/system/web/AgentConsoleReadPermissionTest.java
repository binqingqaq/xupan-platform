package com.xupan.server.system.web;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 机器账号在代理后台（{@code /console}，BY220 {@code robot.php}）的看图能力边界。
 *
 * <p>用户 2026-10-02 实测发现机器账号登录 {@code /console} 后顶部报
 * “当前账号没有执行此操作的权限”，且期号/开奖/倒计时为空。根因是 {@code /console} 顶部状态栏与下注榜
 * 依赖 {@code /api/demo/game/current} 与 {@code /api/demo/game/catalog}，二者要求
 * {@code PERM_GAME_CURRENT_READ}，而该权限此前只授给了 ADMIN 与 SUB_ACCOUNT（V57 已补齐）。
 * BY220 的 {@code robot.php} 本身就能看到当前期号与开奖，因此这是平移缺失。</p>
 *
 * <p>本用例同时锁定“补齐只读权限不等于放开管理能力”：机器账号仍不能访问全局赔率写入、
 * 用户管理、机器管理、角色权限与平台设置等管理员专属接口。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class AgentConsoleReadPermissionTest {

    private static final String ADMIN = "consoleperm-admin";
    private static final String MACHINE_USER = "consoleperm-agent";
    private static final String MACHINE_CODE = "CONSOLEPERM_A";
    private static final String PASSWORD = "ConsolePermPassword123";

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

    private long adminId;
    private long machineId;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "看图权限管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "看图权限机器", PASSWORD, null, adminId).id();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void machineAccountCanReadCurrentIssueAndCatalogForConsoleTopBar() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);

        // /console 顶部状态栏依赖的两个接口此前对机器账号返回 403（V57 修的正是这个）。
        // H2 测试环境没有开奖期数据，接口可能因“当前没有期次”返回 404，因此这里断言的是
        // **权限已放行**（不再是 403），而不是业务数据一定存在；有数据时顺带校验字段形状。
        MvcResult current = mockMvc.perform(get("/api/demo/game/current").param("gameCode", "AU8")
                        .header("Authorization", bearer(token)))
                .andReturn();
        assertThat(current.getResponse().getStatus())
                .as("/api/demo/game/current 不应再因权限被拒")
                .isNotEqualTo(403);
        if (current.getResponse().getStatus() == 200) {
            assertThat(current.getResponse().getContentAsString()).contains("issueNumber");
        }

        MvcResult catalog = mockMvc.perform(get("/api/demo/game/catalog").header("Authorization", bearer(token)))
                .andReturn();
        assertThat(catalog.getResponse().getStatus())
                .as("/api/demo/game/catalog 不应再因权限被拒")
                .isNotEqualTo(403);

        // 下注榜（BY220 robot.php 左栏）：机器账号在范围内可读
        mockMvc.perform(get("/api/admin/player-desk/bet-board").param("kind", "NORMAL")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void machineAccountStillCannotReachAdministratorCapabilities() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);

        // 平台管理员专属：机器管理、用户管理、角色权限、平台设置、全局报表
        mockMvc.perform(get("/api/admin/machines").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/roles").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/settings").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports/profit").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        // 全局赔率/限额的写入仍只允许平台管理员
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/admin/player-desk/betting-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"oddsRate\":99}"))
                .andExpect(status().isForbidden());

        // 下注接口属于玩家前台，代理后台账号不应持有
        mockMvc.perform(post("/api/demo/game/bets").header("Authorization", bearer(token))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentRoleHasCurrentIssueReadButKeepsMinimalConsolePermissions() {
        List<String> permissions = jdbc.queryForList("""
                SELECT p.permission_code FROM sys_role r
                  JOIN sys_role_permission rp ON rp.role_id = r.id
                  JOIN sys_permission p ON p.id = rp.permission_id
                 WHERE r.role_code = 'AGENT'
                """, String.class);

        org.assertj.core.api.Assertions.assertThat(permissions)
                .contains("GAME_CURRENT_READ", "AGENT_CONSOLE_READ", "AGENT_PLAYER_MANAGE")
                .doesNotContain("USER_MANAGE", "MACHINE_MANAGE", "GAME_ODDS_WRITE", "GAME_BET_PLACE",
                        "PLATFORM_SETTINGS_WRITE", "REPORT_READ", "SUB_ACCOUNT_MANAGE", "WALLET_ADJUST");
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
        List<Long> machineIds = jdbc.queryForList("SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);
        List<Long> users = jdbc.queryForList("SELECT id FROM sys_user WHERE username LIKE 'consoleperm-%'", Long.class);
        if (!users.isEmpty()) {
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", users);
            delete("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", users, users);
            delete("DELETE FROM player_name_change_record WHERE user_id IN (%s)", users);
            delete("DELETE FROM player_link_expiration_config WHERE user_id IN (%s)", users);
        }
        if (!machineIds.isEmpty()) {
            List<Long> accounts = jdbc.queryForList("SELECT id FROM demo_user_account WHERE agent_id IN ("
                    + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray());
            if (!accounts.isEmpty()) {
                delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
                delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accounts);
                delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accounts);
                delete("DELETE FROM demo_user_account WHERE id IN (%s)", accounts);
            }
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
        delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!users.isEmpty()) {
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", users);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", users);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", users);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", users);
            delete("DELETE FROM sys_user WHERE id IN (%s)", users);
        }
    }

    private void delete(String sqlTemplate, List<Long>... groups) {
        String[] parts = sqlTemplate.split("%s", -1);
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            sql.append(parts[i]);
            if (i < parts.length - 1) {
                List<Long> group = groups[Math.min(i, groups.length - 1)];
                sql.append(placeholders(group.size()));
                args.addAll(group);
            }
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(count, 1), "?"));
    }
}
