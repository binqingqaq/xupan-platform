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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营后台（{@code /console}，BY220 {@code robot.php}）上下分联动代理积分。
 *
 * <p>BY220 语义：机器账号给自己名下**普通玩家**上分时先扣机器（代理）积分、下分时回补；
 * **托**的上下分只改玩家钱包，不动机器积分；机器积分不足时直接失败且不产生任何写入。
 * 平台管理员在运营后台直接调整玩家钱包，不联动机器积分（对应 BY220 超管在 {@code admin.php} 的操作）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class AgentScoreLinkageConsoleTest {

    private static final String ADMIN = "scorelink-admin";
    private static final String MACHINE_USER = "scorelink-agent";
    private static final String MACHINE_CODE = "SCORELINK_A";
    private static final String PASSWORD = "ScoreLinkPassword123";

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
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdUserIds.clear();
        clean();
        adminId = userRepository.insert(ADMIN, "积分联动管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "积分联动机器", PASSWORD, null, adminId).id();
        jdbc.update("UPDATE agent SET score=1000.00, bot_count=1 WHERE id=?", machineId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void machineTopUpDebitsAgentScoreAndDownAdjustmentCreditsItBack() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);
        long player = createNormalPlayer(token, "联动普通玩家");

        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100,\"reason\":\"代理上分\",\"idempotencyKey\":\"link-topup-1\"}"))
                .andExpect(status().isOk());
        assertThat(agentScore()).isEqualByComparingTo("900.00");
        assertThat(balanceOf(player)).isEqualByComparingTo("100.00");
        // 账本原则 2：代理积分变化必须同时写流水，且分方向、带变动前后余额
        assertThat(lastLedgerDirection()).isEqualTo("OUT");
        assertThat(lastLedgerAmount()).isEqualByComparingTo("100.00");
        assertThat(lastLedgerDelta()).isEqualByComparingTo("-100.00");
        assertThat(ledgerEntryCount()).isEqualTo(1L);

        // 幂等：同一幂等键重复提交不重复扣分
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100,\"reason\":\"代理上分\",\"idempotencyKey\":\"link-topup-1\"}"))
                .andExpect(status().isOk());
        assertThat(agentScore()).isEqualByComparingTo("900.00");
        assertThat(balanceOf(player)).isEqualByComparingTo("100.00");
        assertThat(ledgerEntryCount()).as("重放不得写第二条流水").isEqualTo(1L);

        // 下分：接口按协议接收负数，机器积分回补
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/adjustments")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":-40,\"reason\":\"代理下分\",\"idempotencyKey\":\"link-down-1\"}"))
                .andExpect(status().isOk());
        assertThat(agentScore()).isEqualByComparingTo("940.00");
        assertThat(balanceOf(player)).isEqualByComparingTo("60.00");
        assertThat(lastLedgerDirection()).isEqualTo("IN");
        assertThat(lastLedgerAmount()).isEqualByComparingTo("40.00");
        assertThat(lastLedgerDelta()).isEqualByComparingTo("40.00");
        assertThat(ledgerEntryCount()).isEqualTo(2L);
    }

    /**
     * 账本原则 3、对账：机器流水必须与 {@code agent.score} 一致。
     *
     * <p>这个用例直接校验"账能自己验算"——最新一条流水的变动后余额等于当前积分，
     * 且每一条都满足 {@code balance_after = balance_before ± amount}。</p>
     */
    @Test
    void agentLedgerEntriesFormAConsistentChain() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);
        long player = createNormalPlayer(token, "账本自洽玩家");
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100,\"reason\":\"代理上分\",\"idempotencyKey\":\"chain-topup-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/adjustments")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":-40,\"reason\":\"代理下分\",\"idempotencyKey\":\"chain-down-1\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_ledger
                 WHERE agent_id = ?
                   AND balance_after <> balance_before
                       + CASE WHEN direction = 'IN' THEN amount ELSE -amount END
                """, Long.class, machineId)).as("每条流水都必须自洽").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_ledger l
                 WHERE l.agent_id = ?
                   AND l.balance_after <> (SELECT score FROM agent WHERE id = ?)
                   AND l.id = (SELECT MAX(id) FROM agent_ledger WHERE agent_id = ?)
                """, Long.class, machineId, machineId, machineId))
                .as("最新流水的变动后余额必须等于当前机器积分").isZero();
    }

    /**
     * 积分流水读接口：机器只能读自己的流水，且返回对账标志。
     *
     * <p>账本原则 2、3 的可观测面——写进去的流水必须能被读出来核对。</p>
     */
    @Test
    void machineReadsItsOwnScoreLedger() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);
        long player = createNormalPlayer(token, "流水读取玩家");
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100,\"reason\":\"代理上分\",\"idempotencyKey\":\"read-topup-1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/player-desk/score-ledger").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineId))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.items[0].entryType").value("PLAYER_TOP_UP"))
                .andExpect(jsonPath("$.items[0].direction").value("OUT"))
                .andExpect(jsonPath("$.items[0].amount").value(100.0))
                .andExpect(jsonPath("$.items[0].balanceBefore").value(1000.0))
                .andExpect(jsonPath("$.items[0].balanceAfter").value(900.0));

        // 机器传别的机器 id → 按"不存在"处理，不能读到别人的流水
        mockMvc.perform(get("/api/admin/player-desk/score-ledger").param("machineId", "999999")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());

        // 分页参数越界 → 400
        mockMvc.perform(get("/api/admin/player-desk/score-ledger").param("pageSize", "1000")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEDGER_QUERY_INVALID"));
    }

    @Test
    void insufficientAgentScoreFailsWithoutAnyWrite() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);
        long player = createNormalPlayer(token, "积分不足玩家");
        jdbc.update("UPDATE agent SET score=50.00 WHERE id=?", machineId);

        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100,\"reason\":\"代理上分\",\"idempotencyKey\":\"link-insufficient-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_SCORE_INSUFFICIENT"));

        assertThat(agentScore()).isEqualByComparingTo("50.00");
        assertThat(balanceOf(player)).isEqualByComparingTo("0.00");
    }

    @Test
    void botTopUpDoesNotTouchAgentScore() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);
        long bot = createBot(token, "linkbot001", "联动托");

        mockMvc.perform(post("/api/admin/player-desk/players/" + bot + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":70,\"reason\":\"托上分\",\"idempotencyKey\":\"link-bot-topup-1\"}"))
                .andExpect(status().isOk());

        assertThat(agentScore()).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(bot)).isEqualByComparingTo("70.00");
    }

    @Test
    void botCreationIsLimitedByMachineQuota() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);
        createBot(token, "linkbot002", "联动托一");

        mockMvc.perform(post("/api/admin/player-desk/players/bot")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"userCode\":\"linkbot003\",\"displayName\":\"联动托二\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_BOT_LIMIT_REACHED"));
    }

    @Test
    void platformAdminAdjustmentDoesNotDebitMachineScore() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        String machineToken = login(MACHINE_USER, PASSWORD);
        long player = createNormalPlayer(machineToken, "管理员调整玩家");

        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"amount\":25,\"reason\":\"管理员上分\",\"idempotencyKey\":\"link-admin-1\"}"))
                .andExpect(status().isOk());

        assertThat(agentScore()).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(player)).isEqualByComparingTo("25.00");
    }

    private long createNormalPlayer(String token, String displayName) throws Exception {
        return postPlayer(token, "/api/admin/player-desk/players/normal", "{\"displayName\":\"" + displayName + "\"}");
    }

    private long createBot(String token, String userCode, String displayName) throws Exception {
        return postPlayer(token, "/api/admin/player-desk/players/bot",
                "{\"userCode\":\"" + userCode + "\",\"displayName\":\"" + displayName + "\"}");
    }

    private long postPlayer(String token, String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("Authorization", bearer(token)).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn();
        long userId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
        createdUserIds.add(userId);
        return userId;
    }

    private BigDecimal agentScore() {
        return jdbc.queryForObject("SELECT score FROM agent WHERE id=?", BigDecimal.class, machineId);
    }

    private long ledgerEntryCount() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_ledger WHERE agent_id=?", Long.class, machineId);
        return total == null ? 0L : total;
    }

    /** 最新一条机器流水的方向。 */
    private String lastLedgerDirection() {
        return jdbc.queryForObject("""
                SELECT direction FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1
                """, String.class, machineId);
    }

    /** 最新一条机器流水的变动额（正数）。 */
    private BigDecimal lastLedgerAmount() {
        return jdbc.queryForObject("""
                SELECT amount FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1
                """, BigDecimal.class, machineId);
    }

    /** 最新一条机器流水的净变动（按方向带符号）。 */
    private BigDecimal lastLedgerDelta() {
        return jdbc.queryForObject("""
                SELECT CASE WHEN direction = 'IN' THEN amount ELSE -amount END
                  FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1
                """, BigDecimal.class, machineId);
    }

    private BigDecimal balanceOf(long userId) {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE sys_user_id=?", BigDecimal.class, userId);
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
        List<Long> prefixed = jdbc.queryForList("SELECT id FROM sys_user WHERE username LIKE 'scorelink-%'", Long.class);
        List<Long> owners = new ArrayList<>(prefixed);
        owners.addAll(createdUserIds);

        List<Long> accounts = new ArrayList<>();
        if (!machineIds.isEmpty()) {
            accounts.addAll(jdbc.queryForList("SELECT id FROM demo_user_account WHERE agent_id IN ("
                    + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray()));
        }
        if (!owners.isEmpty()) {
            accounts.addAll(jdbc.queryForList("SELECT id FROM demo_user_account WHERE sys_user_id IN ("
                    + placeholders(owners.size()) + ")", Long.class, owners.toArray()));
        }
        if (!accounts.isEmpty()) {
            accounts = accounts.stream().distinct().toList();
        }
        List<Long> users = new ArrayList<>(owners);
        if (!accounts.isEmpty()) {
            users.addAll(jdbc.queryForList("SELECT sys_user_id FROM demo_user_account WHERE id IN ("
                    + placeholders(accounts.size()) + ")", Long.class, accounts.toArray()));
        }
        List<Long> allUsers = users.stream().filter(java.util.Objects::nonNull).distinct().toList();

        if (!allUsers.isEmpty()) {
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", allUsers);
            delete("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", allUsers, allUsers);
            delete("DELETE FROM player_name_change_record WHERE user_id IN (%s)", allUsers);
            delete("DELETE FROM player_link_expiration_config WHERE user_id IN (%s)", allUsers);
            delete("DELETE FROM player_admin_notice WHERE recipient_user_id IN (%s) OR sender_user_id IN (%s)", allUsers, allUsers);
            delete("DELETE FROM chat_user_mute WHERE user_id IN (%s) OR operator_user_id IN (%s) OR revoked_by IN (%s)", allUsers, allUsers, allUsers);
            delete("DELETE FROM chat_read_cursor WHERE user_id IN (%s)", allUsers);
        }
        if (!accounts.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
            delete("DELETE FROM game_bet WHERE user_id IN (%s)", accounts);
            delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM demo_user_account WHERE id IN (%s)", accounts);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
        delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!allUsers.isEmpty()) {
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", allUsers);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", allUsers);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", allUsers);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", allUsers);
            delete("DELETE FROM sys_user WHERE id IN (%s)", allUsers);
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
