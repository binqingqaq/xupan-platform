package com.xupan.server.system;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营后台（{@code /console}，BY220 {@code robot.php}）机器账号的身份与数据范围验证。
 *
 * <p>阶段 3（严格对齐 BY220，2026-10-08 使用者拍板）：{@code /api/admin/player-desk/**} 只认机器账号
 * （{@code AGENT_CONSOLE_READ}），超管与子账号一律 403，因此"平台全局视图 / 子账号分组视图"
 * 在玩家运营面上**不再存在**（依据：
 * {@code docs/plans/2026-10-08-严格对齐BY220-玩家运营归属机器账号与超管后台收口计划.md} §3.3）。</p>
 *
 * <p>机器账号的数据范围收敛为 {@code MACHINE}：只能看和操作自己名下的玩家/托；
 * 范围外的玩家统一按"不存在"（404）处理，避免通过 403 暴露跨机器数据的存在性。</p>
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

    @Autowired
    private VirtualWalletService walletService;

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

        // 子账号：用真实的 SUB_ACCOUNT 角色（V76 后不再持有 AGENT_CONSOLE_READ），并绑定分组，
        // 用于验证"即使 GROUP 范围本可解析，玩家运营面也一律 403"。
        long subUserId = userRepository.insert(SUB_USER, "范围子账号", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(subUserId, "SUB_ACCOUNT");
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
        String tokenA = login(MACHINE_A_USER, PASSWORD);
        String tokenB = login(MACHINE_B_USER, PASSWORD);
        // 夹具玩家必须由**归属机器**自己创建：机器账号的 MACHINE 范围会强制归属，
        // 平台账号已经无法通过接口创建玩家（403）。
        long playerA1 = createNormalPlayer(tokenA, "范围玩家A1", null);
        long playerA2 = createNormalPlayer(tokenA, "范围玩家A2", null);
        long playerB1 = createNormalPlayer(tokenB, "范围玩家B1", null);
        // 平台直属玩家（不属于任何机器）在接口层已经无法创建，用 SQL 造夹具，
        // 用于验证机器账号看不到"平台直属"这类范围外玩家。
        long platformDirectPlayer = createPlatformDirectPlayer("范围平台直属玩家");

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

    /**
     * 子账号（真实 {@code SUB_ACCOUNT} 角色 + 已绑定分组）在玩家运营面上一律 403。
     *
     * <p>BY220 依据：上下分/审批/建玩家都在 {@code rbt/controller/Index.php}（机器账号），
     * {@code admin.php} 的控制器从不查 {@code db('user')}。原用例断言"子账号只看本组机器玩家"
     * 是按"平台侧也有玩家运营"的错误前提写的，已被业务决定推翻；
     * 这里保留同等强度：**读与写都 403，且不落任何玩家数据**。</p>
     */
    @Test
    void groupBoundSubAccountIsRejectedFromPlayerDesk() throws Exception {
        // 前置：分组范围本可解析（agent_group.sys_user_id 已绑定），403 只可能来自 URL 层权限
        assertThat(jdbc.queryForObject("SELECT sys_user_id FROM agent_group WHERE id=?", Long.class, groupId))
                .isEqualTo(userIdOf(SUB_USER));

        String subToken = login(SUB_USER, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(subToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(subToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/summary").header("Authorization", bearer(subToken)))
                .andExpect(status().isForbidden());

        // 写：指定本组机器也进不去，且不落库
        int playersBefore = playerCountOfMachine(machineBId);
        mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("{\"displayName\":\"范围子账号玩家\",\"machineId\":" + machineBId + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/player-desk/players/bot")
                        .header("Authorization", bearer(subToken)).contentType("application/json")
                        .content("{\"userCode\":\"scopesubbot01\",\"displayName\":\"范围子账号托\",\"machineId\":"
                                + machineBId + "}"))
                .andExpect(status().isForbidden());
        assertThat(playerCountOfMachine(machineBId)).isEqualTo(playersBefore);
    }

    /**
     * 超管（持 {@code USER_MANAGE}）不再拥有玩家运营面的"全局视图"：读与写一律 403。
     *
     * <p>机器账号自己的范围仍然是 {@code MACHINE}（不是 GLOBAL），即 player-desk 上已无全局范围入口。</p>
     */
    @Test
    void platformAdminHasNoPlayerDeskEntryAndNoGlobalScope() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/player-desk/players").header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());

        int playersBefore = playerCountOfMachine(machineAId);
        mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"displayName\":\"范围超管玩家\",\"machineId\":" + machineAId + "}"))
                .andExpect(status().isForbidden());
        assertThat(playerCountOfMachine(machineAId)).isEqualTo(playersBefore);

        // 机器账号自己的范围是 MACHINE：player-desk 上不存在 GLOBAL 视图
        String tokenA = login(MACHINE_A_USER, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/scope").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MACHINE"))
                .andExpect(jsonPath("$.machineId").value((int) machineAId));
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

        // 全局赔率/限额：**读**走 player-desk 的通用规则（仅机器账号），**写**仍由既有的
        // PUT → USER_MANAGE 规则把守（GameBettingConfigService 没有权限校验，删掉那条会让机器能改全局配置）。
        // 机器级的赔率/返水走 /machine-config（见 MachineConfigConsoleTest）。
        mockMvc.perform(get("/api/admin/player-desk/betting-config").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/admin/player-desk/betting-config")
                        .header("Authorization", bearer(tokenA)).contentType("application/json")
                        .content("{\"oddsRate\":99}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 用 SQL 造一个"平台直属玩家"夹具（不属于任何机器）。
     *
     * <p>阶段 3 之后平台账号已无法通过接口创建玩家（403），而"机器账号看不到平台直属玩家"
     * 这条范围断言必须保留，因此这里直接落库：{@code walletService.ensureWalletForUser}
     * 建钱包账户时默认归属平台直属代理（{@code PLATFORM_DIRECT}）。</p>
     */
    private long createPlatformDirectPlayer(String displayName) {
        long userId = userRepository.insert("playerscope-direct-" + System.nanoTime(), displayName,
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "USER");
        walletService.ensureWalletForUser(userId, displayName);
        createdPlayerUserIds.add(userId);
        return userId;
    }

    private long userIdOf(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private int playerCountOfMachine(long agentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM demo_user_account WHERE agent_id = ?",
                Integer.class, agentId);
    }

    private long createNormalPlayer(String token, String displayName, Long machineId) throws Exception {
        String body = machineId == null
                ? "{\"displayName\":\"" + displayName + "\"}"
                : "{\"displayName\":\"" + displayName + "\",\"machineId\":" + machineId + "}";
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(token)).contentType("application/json").content(body))
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
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
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
