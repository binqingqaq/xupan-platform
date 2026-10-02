package com.xupan.server.system;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营后台（{@code /console}，BY220 {@code robot.php}）身份与数据范围验证。
 *
 * <p>用户 2026-10-02 已确认：机器账号登录代理后台后只能看和操作自己名下的玩家/托；
 * 平台管理员保留全局视图；子账号限定在自己分组的机器内。范围外的玩家统一按"不存在"（404）处理，
 * 避免通过 403 暴露跨机器数据的存在性。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerDeskScopeTest {

    private static final String ADMIN = "playerscope-admin";
    private static final String MACHINE_A_USER = "playerscope-agent-a";
    private static final String MACHINE_B_USER = "playerscope-agent-b";
    private static final String SUB_USER = "playerscope-sub";
    private static final String PLAYER_ROLE_USER = "playerscope-player-role";
    private static final String PASSWORD = "PlayerScopePassword123";
    private static final String GROUP_CODE = "PLAYERSCOPE_GROUP";
    private static final String MACHINE_A_CODE = "PLAYERSCOPE_A";
    private static final String MACHINE_B_CODE = "PLAYERSCOPE_B";

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
    private long machineAId;
    private long machineBId;
    private long groupId;

    /** 通过接口创建的玩家用户名由后端自动生成，必须显式登记才能可靠清理。 */
    private final List<Long> createdPlayerUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdPlayerUserIds.clear();
        clean();
        adminId = userRepository.insert(ADMIN, "范围测试管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        groupId = agentAdminService.createGroup(GROUP_CODE, "范围测试分组", adminId).id();
        machineAId = agentAdminService.createAgent(MACHINE_A_CODE, MACHINE_A_USER, "范围机器A", PASSWORD, null, adminId).id();
        machineBId = agentAdminService.createAgent(MACHINE_B_CODE, MACHINE_B_USER, "范围机器B", PASSWORD, groupId, adminId).id();

        // 子账号：绑定到分组；AGENT 角色只用于让 URL 层放行（V56 才会正式授予子账号访问权限）。
        long subUserId = userRepository.insert(SUB_USER, "范围子账号", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(subUserId, "AGENT");
        jdbc.update("UPDATE agent_group SET sys_user_id=? WHERE id=?", subUserId, groupId);

        // 仅有普通玩家角色的账号：用于验证原有权限没有被放宽。
        long playerRoleId = userRepository.insert(PLAYER_ROLE_USER, "范围普通玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(playerRoleId, "USER");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void machineAccountSeesOnlyOwnPlayersAndItsOwnProfile() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long playerA1 = createNormalPlayer(adminToken, "范围玩家A1", machineAId);
        long playerA2 = createNormalPlayer(adminToken, "范围玩家A2", machineAId);
        long playerB1 = createNormalPlayer(adminToken, "范围玩家B1", machineBId);
        long platformDirectPlayer = createNormalPlayer(adminToken, "范围平台直属玩家", null);
        String tokenA = login(MACHINE_A_USER, PASSWORD);

        // 头部身份档案来自机器自己的数据（BY220 robot.php 顶部栏语义）
        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MACHINE"))
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.machineCode").value(MACHINE_A_CODE))
                .andExpect(jsonPath("$.machineName").value("范围机器A"))
                .andExpect(jsonPath("$.machineStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.playerCount").value(2))
                .andExpect(jsonPath("$.botCount").value(0));

        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("范围玩家A1", "范围玩家A2")));

        // 自己名下的玩家可读
        mockMvc.perform(get("/api/admin/player-desk/players/" + playerA1).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("范围玩家A1"));
        mockMvc.perform(get("/api/admin/player-desk/players/" + playerA2).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());

        // 其它机器与平台直属玩家：按“不存在”处理，不泄露存在性
        mockMvc.perform(get("/api/admin/player-desk/players/" + playerB1).header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAYER_NOT_FOUND"));
        // 平台直属玩家（未指定机器创建）同样不在机器账号范围内
        mockMvc.perform(get("/api/admin/player-desk/players/" + platformDirectPlayer)
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound());

        // 跨机器写操作同样按不存在处理，且不产生任何流水
        long before = ledgerCount(playerB1);
        mockMvc.perform(post("/api/admin/player-desk/players/" + playerB1 + "/balance/grants")
                        .header("Authorization", bearer(tokenA)).contentType("application/json")
                        .content("{\"amount\":10,\"reason\":\"跨机器越权测试\",\"idempotencyKey\":\"scope-cross-1\"}"))
                .andExpect(status().isNotFound());
        assertThat(ledgerCount(playerB1)).isEqualTo(before);
    }

    @Test
    void groupAccountSeesOnlyItsOwnGroupMachinePlayers() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long playerA1 = createNormalPlayer(adminToken, "范围玩家A1", machineAId);
        long playerB1 = createNormalPlayer(adminToken, "范围玩家B1", machineBId);

        String subToken = login(SUB_USER, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(subToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("GROUP"))
                .andExpect(jsonPath("$.groupId").value((int) groupId))
                .andExpect(jsonPath("$.groupName").value("范围测试分组"));

        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(subToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("范围玩家B1"));

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerB1).header("Authorization", bearer(subToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/player-desk/players/" + playerA1).header("Authorization", bearer(subToken)))
                .andExpect(status().isNotFound());

        // 子账号建玩家必须指定本分组机器
        mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("{\"displayName\":\"范围子账号玩家-无机器\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAYER_DESK_MACHINE_REQUIRED"));

        mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("{\"displayName\":\"范围子账号玩家-跨组机器\",\"machineId\":" + machineAId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAYER_DESK_MACHINE_INVALID"));

        MvcResult created = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("{\"displayName\":\"范围子账号玩家\",\"machineId\":" + machineBId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long createdUserId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.userId")).longValue();
        createdPlayerUserIds.add(createdUserId);
        assertThat(agentIdOf(createdUserId)).isEqualTo(machineBId);
    }

    @Test
    void platformAdminKeepsGlobalScopeAndCanTargetAnyActiveMachine() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        createNormalPlayer(adminToken, "范围玩家A1", machineAId);
        createNormalPlayer(adminToken, "范围玩家B1", machineBId);

        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("GLOBAL"));

        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void machineAccountCreationForcesOwnAgentIdEvenWhenAnotherMachineIsRequested() throws Exception {
        String tokenA = login(MACHINE_A_USER, PASSWORD);

        MvcResult created = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(tokenA)).contentType("application/json")
                        .content("{\"displayName\":\"范围机器A新建玩家\",\"machineId\":" + machineBId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long createdUserId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.userId")).longValue();
        createdPlayerUserIds.add(createdUserId);
        assertThat(agentIdOf(createdUserId)).isEqualTo(machineAId);

        // 新建的玩家只能被本机器看到，另一个机器看不到
        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("范围机器A新建玩家")));
        String tokenB = login(MACHINE_B_USER, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/players/" + createdUserId).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void plainPlayerRoleIsStillRejectedFromConsoleEndpoints() throws Exception {
        String playerToken = login(PLAYER_ROLE_USER, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(playerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(playerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent").param("kind", "NORMAL")
                        .header("Authorization", bearer(playerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/betting-config").header("Authorization", bearer(playerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void machineAccountCannotWriteGlobalBettingConfig() throws Exception {
        String tokenA = login(MACHINE_A_USER, PASSWORD);

        // 全局赔率/限额属于平台管理员；机器账号的机器级配置入口在阶段 3 单独提供
        mockMvc.perform(get("/api/admin/player-desk/betting-config").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/admin/player-desk/betting-config")
                        .header("Authorization", bearer(tokenA)).contentType("application/json")
                        .content("{\"oddsRate\":99}"))
                .andExpect(status().isForbidden());
    }

    private long createNormalPlayer(String adminToken, String displayName, Long machineId) throws Exception {
        String body = machineId == null
                ? "{\"displayName\":\"" + displayName + "\"}"
                : "{\"displayName\":\"" + displayName + "\",\"machineId\":" + machineId + "}";
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(adminToken)).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn();
        long userId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
        createdPlayerUserIds.add(userId);
        return userId;
    }

    private Long agentIdOf(long userId) {
        return jdbc.queryForObject("""
                SELECT a.agent_id FROM demo_user_account a WHERE a.sys_user_id = ?
                """, Long.class, userId);
    }

    private long ledgerCount(long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger l
                  JOIN demo_user_account a ON a.id = l.user_id WHERE a.sys_user_id = ?
                """, Long.class, userId);
        return count == null ? 0L : count;
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

    /**
     * 清理本测试产生的数据。
     *
     * <p>通过接口创建的玩家用户名由后端自动生成、不带本测试前缀，因此必须按"机器归属"反查其账号与账号所有者，
     * 并按外键顺序（链接 → 记录 → 账号 → 机器/分组 → 用户）删除。</p>
     */
    private void clean() {
        List<Long> machineIds = jdbc.queryForList(
                "SELECT id FROM agent WHERE agent_code IN (?, ?)", Long.class, MACHINE_A_CODE, MACHINE_B_CODE);
        List<Long> prefixedUserIds = jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username LIKE 'playerscope-%'", Long.class);
        List<Long> targetUserIds = new ArrayList<>(prefixedUserIds);
        targetUserIds.addAll(createdPlayerUserIds);

        List<Long> accountIds = new ArrayList<>();
        if (!machineIds.isEmpty()) {
            accountIds.addAll(jdbc.queryForList(
                    "SELECT id FROM demo_user_account WHERE agent_id IN (" + placeholders(machineIds.size()) + ")",
                    Long.class, machineIds.toArray()));
        }
        if (!targetUserIds.isEmpty()) {
            accountIds.addAll(jdbc.queryForList(
                    "SELECT id FROM demo_user_account WHERE sys_user_id IN (" + placeholders(targetUserIds.size()) + ")",
                    Long.class, targetUserIds.toArray()));
        }
        List<Long> userIds = new ArrayList<>(targetUserIds);
        if (!accountIds.isEmpty()) {
            userIds.addAll(jdbc.queryForList(
                    "SELECT sys_user_id FROM demo_user_account WHERE id IN (" + placeholders(accountIds.size()) + ")",
                    Long.class, accountIds.toArray()));
        }
        List<Long> owners = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> accounts = accountIds.stream().distinct().toList();

        if (!owners.isEmpty()) {
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", owners);
            delete("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", owners, owners);
            delete("DELETE FROM player_name_change_record WHERE user_id IN (%s)", owners);
            delete("DELETE FROM player_link_expiration_config WHERE user_id IN (%s)", owners);
            delete("DELETE FROM player_admin_notice WHERE recipient_user_id IN (%s) OR sender_user_id IN (%s)", owners, owners);
            delete("DELETE FROM game_bet_edit_record WHERE operator_user_id IN (%s)", owners);
            delete("DELETE FROM chat_user_mute WHERE user_id IN (%s) OR operator_user_id IN (%s) OR revoked_by IN (%s)", owners, owners, owners);
            delete("DELETE FROM chat_read_cursor WHERE user_id IN (%s)", owners);
        }
        if (!accounts.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
            delete("DELETE FROM game_bet WHERE user_id IN (%s)", accounts);
            delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM demo_user_account WHERE id IN (%s)", accounts);
        }
        if (!owners.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE operator_user_id IN (%s)", owners);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        jdbc.update("UPDATE agent_group SET sys_user_id = NULL, deleted_by = NULL, created_by = NULL WHERE group_code = ?", GROUP_CODE);
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        if (!owners.isEmpty()) {
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", owners);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", owners);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", owners);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", owners);
            delete("DELETE FROM sys_user WHERE id IN (%s)", owners);
        }
    }

    private void delete(String sqlTemplate, List<Long>... idGroups) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        String[] parts = sqlTemplate.split("%s", -1);
        for (int i = 0; i < parts.length; i++) {
            sql.append(parts[i]);
            if (i < parts.length - 1) {
                List<Long> group = idGroups[Math.min(i, idGroups.length - 1)];
                sql.append(placeholders(group.size()));
                args.addAll(group);
            }
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(Math.max(count, 1), "?"));
    }
}
