package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.web.AuthController;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 阶段 3（严格对齐 BY220）：{@code /api/admin/player-desk/**} **只认机器账号**。
 *
 * <p>BY220 依据：玩家与托的建、上下分、审批、链接、资料、报网等**全部**在
 * {@code application/rbt/controller/Index.php}（代理后台 {@code robot.php}，机器账号）；
 * 超级管理后台 {@code application/admin/controller/*} 查 {@code db('user')} **零命中**，
 * 即超管对玩家没有任何操作面。权威依据：
 * {@code docs/plans/2026-10-08-严格对齐BY220-玩家运营归属机器账号与超管后台收口计划.md} §3.3。</p>
 *
 * <p>本用例锁定三点：</p>
 * <ol>
 *   <li>超管（持 {@code USER_MANAGE}）与子账号（持 {@code AGENT_PLAYER_MANAGE}、且已配好分组）
 *       调 {@code /api/admin/player-desk/**} 的**读与写一律 403**，且不产生任何玩家数据；</li>
 *   <li>机器账号（{@code AGENT_CONSOLE_READ}，代理入口登录）读与写都正常，范围收敛为 {@code MACHINE}；</li>
 *   <li>{@code /api/agent/**}（BY220 robot.php 接口面）仍然只认机器账号。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerDeskEntryPermissionTest {

    private static final String ADMIN = "entryperm-admin";
    private static final String SUB_ACCOUNT = "entryperm-sub";
    private static final String MACHINE_USER = "entryperm-machine";
    private static final String GROUP_CODE = "ENTRYPERM_GROUP";
    private static final String MACHINE_CODE = "ENTRYPERM_MACHINE";
    private static final String PASSWORD = "EntryPermPassword123";
    private static final String PLAYER_NAME = "入口权限玩家";

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
    private long subAccountId;
    private long groupId;
    private long machineId;

    @BeforeEach
    void setUp() {
        clean();
        adminId = insertUser(ADMIN, "入口权限管理员", "ADMIN");
        subAccountId = insertUser(SUB_ACCOUNT, "入口权限子账号", "SUB_ACCOUNT");
        jdbc.update("""
                INSERT INTO agent_group (group_code, display_name, status, username, sys_user_id, created_by)
                VALUES (?, '入口权限分组', 'ACTIVE', ?, ?, ?)
                """, GROUP_CODE, SUB_ACCOUNT, subAccountId, adminId);
        groupId = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, GROUP_CODE);
        machineId = agentAdminService.createAgent(
                MACHINE_CODE, MACHINE_USER, "入口权限机器", PASSWORD, groupId, adminId).id();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /**
     * 超管与子账号都被挡在玩家运营接口之外（读与写都要覆盖）。
     *
     * <p>子账号这里刻意配好了 {@code agent_group.sys_user_id}（GROUP 范围本可解析），
     * 因此 403 只能来自 URL 层权限，而不是"范围解析失败"。</p>
     */
    @Test
    void platformAdminAndSubAccountAreRejectedFromPlayerDesk() throws Exception {
        // 前提事实：两者都不持有 AGENT_CONSOLE_READ（V76 已从 ADMIN/SUB_ACCOUNT 撤销）
        assertThat(permissionCodesFor(adminId)).contains("USER_MANAGE").doesNotContain("AGENT_CONSOLE_READ");
        assertThat(permissionCodesFor(subAccountId)).contains("AGENT_PLAYER_MANAGE")
                .doesNotContain("AGENT_CONSOLE_READ");

        for (String username : List.of(ADMIN, SUB_ACCOUNT)) {
            String token = login(username, PASSWORD);

            // 读：范围/汇总/列表/最近上下分/报网 一律 403
            mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/player-desk/summary").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/player-desk/point-operations/recent").param("kind", "NORMAL")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/player-desk/report").param("machineId", String.valueOf(machineId))
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());

            // 写：建玩家 / 上下分 / 审批 / 机器级配置 一律 403
            mockMvc.perform(post("/api/admin/player-desk/players/normal")
                            .header("Authorization", bearer(token)).contentType("application/json")
                            .content("{\"displayName\":\"" + PLAYER_NAME + "-" + username
                                    + "\",\"machineId\":" + machineId + "}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/player-desk/players/1/balance/grants")
                            .header("Authorization", bearer(token)).contentType("application/json")
                            .content("{\"amount\":10,\"reason\":\"越权测试\",\"idempotencyKey\":\"entryperm-forbidden-1\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/player-desk/point-requests/1/approve")
                            .header("Authorization", bearer(token)).contentType("application/json")
                            .content("{\"reason\":\"越权测试\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/admin/player-desk/machine-config")
                            .header("Authorization", bearer(token)).contentType("application/json")
                            .content("{\"machineId\":" + machineId + ",\"oddsRate\":95}"))
                    .andExpect(status().isForbidden());
            // 删除玩家（BY220 robot.php DelMember 的平移端点）同样是机器账号专属：
            // 超管/子账号一律 403，且不得产生任何余额退回或玩家删除
            mockMvc.perform(MockMvcRequestBuilders.delete("/api/admin/player-desk/players/1")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());

            // 被拒不得留下任何玩家数据
            assertThat(playerCountFor(machineId)).as("%s 不得创建任何玩家", username).isZero();
        }

        // 机器后台接口（/api/agent/**）对子账号同样是 403
        mockMvc.perform(get("/api/agent/me").header("Authorization", bearer(login(SUB_ACCOUNT, PASSWORD))))
                .andExpect(status().isForbidden());
    }

    /** 机器账号（代理入口登录）读与写都正常，且数据范围收敛为 MACHINE（只能操作自己那台）。 */
    @Test
    void machineAccountReadsAndWritesPlayerDeskAndAgentConsole() throws Exception {
        String token = loginWithConsoleEntry(MACHINE_USER, PASSWORD);

        // 读
        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MACHINE"))
                .andExpect(jsonPath("$.machineId").value((int) machineId));
        mockMvc.perform(get("/api/admin/player-desk/summary").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // 写：MACHINE 范围忽略传入的其它机器，强制归属自己那台
        mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"displayName\":\"" + PLAYER_NAME + "\",\"machineId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("PLAYER_LINK"))
                .andExpect(jsonPath("$.displayName").value(PLAYER_NAME));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE agent_id = ? AND player_kind = 'NORMAL'",
                Integer.class, machineId)).isEqualTo(1);

        // 审计权限码口径（PlayerLinkAuthenticationService：非全局范围记 AGENT_PLAYER_MANAGE）
        assertThat(jdbc.queryForList("""
                SELECT permission_code FROM sys_operation_log
                 WHERE operator_user_id = ? AND request_path LIKE '%/access-links'
                """, String.class, machineUserId())).contains("AGENT_PLAYER_MANAGE");

        // 机器后台接口（/api/agent/**，BY220 robot.php）机器账号仍然可用
        mockMvc.perform(get("/api/agent/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(MACHINE_CODE));
        mockMvc.perform(get("/api/agent/players").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private long machineUserId() {
        return jdbc.queryForObject("SELECT account_user_id FROM agent WHERE id = ?", Long.class, machineId);
    }

    private int playerCountFor(long agentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE agent_id = ?", Integer.class, agentId);
    }

    private long insertUser(String username, String displayName, String roleCode) {
        long userId = userRepository.insert(username, displayName, passwordPolicy.encode(PASSWORD), "ACTIVE");
        assertThat(userRepository.assignRole(userId, roleCode)).isEqualTo(1);
        return userId;
    }

    private List<String> permissionCodesFor(long userId) {
        return jdbc.queryForList("""
                SELECT p.permission_code FROM sys_user_role ur
                  JOIN sys_role_permission rp ON rp.role_id = ur.role_id
                  JOIN sys_permission p ON p.id = rp.permission_id
                 WHERE ur.user_id = ?
                """, String.class, userId);
    }

    private String login(String username, String password) throws Exception {
        return accessToken(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    /** 机器账号按 /console 的入口（显式 CONSOLE）登录：与前端第二批的契约一致。 */
    private String loginWithConsoleEntry(String username, String password) throws Exception {
        return accessToken(post("/api/auth/login")
                .contentType("application/json")
                .header(AuthController.REFRESH_AUDIENCE_HEADER, "CONSOLE")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private String accessToken(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** 只清理本用例造出的账号、机器、分组与玩家，避免给其它测试留脏数据。 */
    private void clean() {
        List<Long> groupIds = jdbc.queryForList(
                "SELECT id FROM agent_group WHERE group_code = ?", Long.class, GROUP_CODE);
        List<Long> machineIds = jdbc.queryForList(
                "SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);

        List<Long> accountIds = machineIds.isEmpty() ? List.of()
                : jdbc.queryForList("SELECT id FROM demo_user_account WHERE agent_id IN ("
                        + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray());

        List<Long> owners = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username IN (?, ?, ?)", Long.class,
                ADMIN, SUB_ACCOUNT, MACHINE_USER));
        if (!accountIds.isEmpty()) {
            owners.addAll(jdbc.queryForList("SELECT sys_user_id FROM demo_user_account WHERE id IN ("
                    + placeholders(accountIds.size()) + ")", Long.class, accountIds.toArray()));
        }
        List<Long> userIds = owners.stream().filter(Objects::nonNull).distinct().toList();

        if (!accountIds.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accountIds);
            delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accountIds);
            delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accountIds);
            delete("DELETE FROM demo_user_account WHERE id IN (%s)", accountIds);
        }
        if (!userIds.isEmpty()) {
            delete("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", userIds, userIds);
            delete("DELETE FROM player_name_change_record WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM player_link_expiration_config WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", userIds);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!groupIds.isEmpty()) {
            delete("UPDATE agent_group SET sys_user_id = NULL WHERE id IN (%s)", groupIds);
            delete("DELETE FROM agent_ledger WHERE group_id IN (%s)", groupIds);
            delete("DELETE FROM agent_group WHERE id IN (%s)", groupIds);
        }
        if (!userIds.isEmpty()) {
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_user WHERE id IN (%s)", userIds);
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
