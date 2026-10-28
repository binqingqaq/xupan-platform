package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.web.AuthController;
import com.xupan.server.game.repository.GameDataRepository;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 玩家删除口径**严格对齐 BY220**：物理删除玩家行 + 余额退回机器，注单与流水历史保留。
 *
 * <p>BY220 依据 {@code application/rbt/controller/Index.php:235-243}：
 * {@code setInc('score')} 退余额 → {@code delete()} 物理删玩家行 → {@code setCount()} 重算统计；
 * 注单 {@code x_record} 与资金流水 {@code x_money_log} 因为冗余存了玩家/机器且无外键而完整保留。</p>
 *
 * <p>本类覆盖实施计划 §5 的 DEL 行：有注单/流水删除、无注单删除、历史仍可读（快照）、
 * 机器池增加额 = 删除时余额、重复删除幂等不重复退款、机器范围报表仍包含历史（切片 5）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerPhysicalDeleteTest {

    private static final String ADMIN = "delphys-admin";
    private static final String MACHINE_USER = "delphys-agent";
    private static final String MACHINE_CODE = "DELPHYS_A";
    private static final String PASSWORD = "DelPhysPassword123";
    private static final String BET_PREFIX = "delphys-bet";
    private static final String LEDGER_PREFIX = "delphys-ledger";
    private static final String REQUEST_PREFIX = "delphys-req";
    /** 本类专用期号：避免与其它测试类共享 H2 库时的历史注单互相干扰。 */
    private static final String BET_ISSUE = "20991231";

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
    private GameDataRepository gameDataRepository;

    private long adminId;
    private long machineId;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "物理删除管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "物理删除机器", PASSWORD, null, adminId).id();
        jdbc.update("UPDATE agent SET score=1000.00, bot_count=1 WHERE id=?", machineId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /**
     * 主用例：有注单与流水的玩家被**物理删除**，历史仍然可读，余额退回机器池。
     *
     * <p>断言四点：①玩家行（账号 + 登录账号）已不存在；②注单/流水/换名/上下分申请行仍在，
     * 且 `user_id`/`account_id` 被外键置空、玩家与机器快照可读；③机器池增加额**等于删除时余额**，
     * 且写了一条 `PLAYER_DELETE_REFUND` 机器流水（BY220 不记，我们是超集）；
     * ④随身份消失的子行与机器人数统计正确。</p>
     */
    @Test
    void deletingPlayerKeepsHistoryWithSnapshotAndRefundsBalanceToMachine() throws Exception {
        String token = login();
        long player = createNormalPlayer(token, "删除历史玩家");
        long accountId = accountIdOf(player);
        String memberCode = memberCodeOf(accountId);

        // 上分 100（机器池 1000 → 900），玩家余额 100
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":100,\"reason\":\"代理上分\",\"idempotencyKey\":\"delphys-topup\"}"))
                .andExpect(status().isOk());
        assertThat(agentScore()).isEqualByComparingTo("900.00");
        assertThat(balanceOf(player)).isEqualByComparingTo("100.00");

        // 造齐「应当保留的历史」与「应当随身份消失的运行态」
        insertBet(accountId, BET_PREFIX + "-1", "10.00");
        insertPointRequest(player);
        insertNameChange(player);
        insertLinkExpirationConfig(player);
        insertQuickBetPreference(player);
        insertChatReadCursor(player);
        insertLoginLog(player);
        insertBehavior(accountId);
        assertThat(accessLinkCount(player)).as("玩家链接登录必须已有可删除的链接行").isEqualTo(1L);

        // 删除前的报网视图基线：订单列表 1 条、未报 1 条（该注单还没有上报订单行）
        assertThat(((Number) reportOrders(token).get("total")).longValue()).isEqualTo(1L);
        assertThat(((Number) reportConfig(token).get("unreportedCount")).longValue()).isEqualTo(1L);

        // 删除（BY220 DelMember）
        MvcResult deleted = mockMvc.perform(delete("/api/admin/player-desk/players/" + player)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDeleted").value(false))
                .andExpect(jsonPath("$.userId").value((int) player))
                .andExpect(jsonPath("$.memberCode").value(memberCode))
                .andExpect(jsonPath("$.playerName").value("删除历史玩家"))
                .andExpect(jsonPath("$.refundedAmount").value(100.0))
                .andExpect(jsonPath("$.machineId").value((int) machineId))
                .andReturn();
        assertThat((Object) JsonPath.read(deleted.getResponse().getContentAsString(), "$.refundedAmount"))
                .isEqualTo(100.0);

        // ① 玩家行已物理删除
        assertThat(count("SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_user WHERE id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM demo_user_account WHERE agent_id=?", machineId)).isZero();

        // ② 注单/流水仍在且可读：外键置空 + 快照可读
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE bet_code=?", BET_PREFIX + "-1")).isEqualTo(1L);
        assertThat(jdbc.queryForMap("""
                SELECT user_id, player_code_snapshot, player_name_snapshot, agent_id_snapshot
                  FROM game_bet WHERE bet_code=?
                """, BET_PREFIX + "-1"))
                .containsEntry("user_id", null)
                .containsEntry("player_code_snapshot", memberCode)
                .containsEntry("player_name_snapshot", "删除历史玩家")
                .containsEntry("agent_id_snapshot", machineId);
        assertThat(count("SELECT COUNT(*) FROM demo_balance_ledger WHERE user_id=?", accountId)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE user_id IS NULL AND player_code_snapshot=? AND player_name_snapshot=?
                """, memberCode, "删除历史玩家")).isEqualTo(1L);
        // 身份域的业务历史同口径保留（计划 §3.1 第 2 条）
        assertThat(jdbc.queryForMap("""
                SELECT user_id, player_code_snapshot, player_name_snapshot
                  FROM player_point_request WHERE client_message_id=?
                """, REQUEST_PREFIX))
                .containsEntry("user_id", null)
                .containsEntry("player_code_snapshot", memberCode)
                .containsEntry("player_name_snapshot", "删除历史玩家");
        assertThat(jdbc.queryForMap("""
                SELECT user_id, player_code_snapshot FROM player_name_change_record WHERE new_display_name=?
                """, "删除历史玩家改名"))
                .containsEntry("user_id", null)
                .containsEntry("player_code_snapshot", memberCode);

        // ②b 随身份消失的子行确实消失了（否则 sys_user 根本删不掉）
        assertThat(count("SELECT COUNT(*) FROM player_access_link WHERE user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM player_link_expiration_config WHERE user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM player_quick_bet_preference WHERE user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_read_cursor WHERE user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM test_player_behavior WHERE account_id=?", accountId)).isZero();
        // 审计与登录日志本身保留，只把主体置空（项目审计基线）
        assertThat(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_login_log WHERE username_snapshot=?", "删除历史玩家")).isEqualTo(1L);

        // ③ 机器池增加额 = 删除时余额（900 + 100 = 1000），并留下可审计的退回流水
        assertThat(agentScore()).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForMap("""
                SELECT entry_type, direction, amount, balance_before, balance_after, reason
                  FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1
                """, machineId))
                .containsEntry("entry_type", "PLAYER_DELETE_REFUND")
                .containsEntry("direction", "IN")
                .containsEntry("amount", new BigDecimal("100.00"))
                .containsEntry("balance_before", new BigDecimal("900.00"))
                .containsEntry("balance_after", new BigDecimal("1000.00"));
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1
                """, String.class, machineId)).contains("玩家删除退回余额");
        assertThat(count("SELECT COUNT(*) FROM agent_ledger WHERE agent_id=? AND entry_type='PLAYER_DELETE_REFUND'",
                machineId)).isEqualTo(1L);

        // ⑤ 开奖公告的两条读路径（中奖名单 / 注单核对列表）不得因为玩家行被删而变空：
        //    它们必须回退到删除时写入的玩家快照（计划 §6「SET NULL 之后界面不能变成空白」）。
        List<GameDataRepository.WinnerRecord> winners = gameDataRepository.findWinningBets(BET_ISSUE);
        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).displayName()).isEqualTo("删除历史玩家");
        List<GameDataRepository.BetAuditRecord> audit = gameDataRepository.findBetAuditByIssue(BET_ISSUE);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).displayName()).isEqualTo("删除历史玩家");

        // ⑥ 报网视图（ReportUpstreamOrderRepository）不得因为玩家行被删而丢单：
        //    订单列表条数不变、玩家编号/昵称回退到快照；「未报」统计也不得变小
        //    （否则已删玩家的未报注单会永远报不出去）。
        Map<String, Object> orders = reportOrders(token);
        assertThat(((Number) orders.get("total")).longValue()).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) orders.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("memberCode", memberCode)
                .containsEntry("displayName", "删除历史玩家");
        assertThat(((Number) reportConfig(token).get("unreportedCount")).longValue()).isEqualTo(1L);
    }

    /** 机器报网订单列表（{@code /api/admin/player-desk/report/orders}）原始 JSON。 */
    private Map<String, Object> reportOrders(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/player-desk/report/orders")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$");
    }

    /** 机器报网配置与上报统计（{@code /api/admin/player-desk/report}）原始 JSON。 */
    private Map<String, Object> reportConfig(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/player-desk/report")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$");
    }

    /** 无注单/无流水的玩家同样能删掉（不撞外键），且余额为 0 时不写退回流水。 */
    @Test
    void deletingPlayerWithoutHistorySucceedsAndWritesNoRefundEntry() throws Exception {
        String token = login();
        long player = createNormalPlayer(token, "无历史玩家");

        mockMvc.perform(delete("/api/admin/player-desk/players/" + player)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDeleted").value(false))
                .andExpect(jsonPath("$.refundedAmount").value(0.0));

        assertThat(count("SELECT COUNT(*) FROM sys_user WHERE id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id=?", player)).isZero();
        assertThat(agentScore()).isEqualByComparingTo("1000.00");
        assertThat(count("SELECT COUNT(*) FROM agent_ledger WHERE agent_id=? AND entry_type='PLAYER_DELETE_REFUND'",
                machineId)).isZero();
    }

    /** 重复删除：幂等成功，**不重复退款**。 */
    @Test
    void repeatedDeleteIsIdempotentAndNeverRefundsTwice() throws Exception {
        String token = login();
        long player = createNormalPlayer(token, "重复删除玩家");
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":60,\"reason\":\"代理上分\",\"idempotencyKey\":\"delphys-topup-repeat\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/admin/player-desk/players/" + player)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDeleted").value(false))
                .andExpect(jsonPath("$.refundedAmount").value(60.0));
        assertThat(agentScore()).isEqualByComparingTo("1000.00");

        // 第二次：目标已不存在 → 幂等成功（不是 404），且不再退款
        mockMvc.perform(delete("/api/admin/player-desk/players/" + player)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDeleted").value(true))
                .andExpect(jsonPath("$.refundedAmount").value(0.0));
        assertThat(agentScore()).isEqualByComparingTo("1000.00");
        assertThat(count("SELECT COUNT(*) FROM agent_ledger WHERE agent_id=? AND entry_type='PLAYER_DELETE_REFUND'",
                machineId)).isEqualTo(1L);
    }

    /**
     * 并发：删除与删除同时发生（同一玩家）只允许退一次。
     *
     * <p>两个线程同时打同一个 {@code DELETE} 接口；删除入口在事务里对
     * {@code demo_user_account} 行加 {@code SELECT ... FOR UPDATE}，因此两次调用被串行化，
     * 后到的那次查不到账号行 → 幂等成功、不退第二次。</p>
     */
    @Test
    void concurrentDeletesRefundExactlyOnce() throws Exception {
        String token = login();
        long player = createNormalPlayer(token, "并发删除玩家");
        mockMvc.perform(post("/api/admin/player-desk/players/" + player + "/balance/grants")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"amount\":25,\"reason\":\"代理上分\",\"idempotencyKey\":\"delphys-topup-concurrent\"}"))
                .andExpect(status().isOk());
        assertThat(agentScore()).isEqualByComparingTo("975.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> first = () -> concurrentDelete(token, player, start);
            Callable<Integer> second = () -> concurrentDelete(token, player, start);
            Future<Integer> a = pool.submit(first);
            Future<Integer> b = pool.submit(second);
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .as("两次并发删除都必须是幂等成功")
                    .containsExactly(200, 200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id=?", player)).isZero();
        assertThat(count("SELECT COUNT(*) FROM agent_ledger WHERE agent_id=? AND entry_type='PLAYER_DELETE_REFUND'",
                machineId)).isEqualTo(1L);
        assertThat(agentScore()).isEqualByComparingTo("1000.00");
    }

    private int concurrentDelete(String token, long player, CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(delete("/api/admin/player-desk/players/" + player)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getStatus();
    }

    /**
     * 切片 5（机器归属快照）：删掉一个有注单的玩家之后，**该机器的报表合计仍包含这些历史**。
     *
     * <p>BY220 的 {@code x_record.rid/uid} 冗余存了机器与玩家，所以删掉 {@code x_rbuser} 行之后
     * 机器报表照样能统计到这些历史。xupan 若只把 {@code game_bet.user_id} 置空、报表仍靠
     * {@code JOIN demo_user_account} 取 {@code agent_id}，机器的营业额/盈亏就会**静默变小**。
     * 本用例锁定「合计不变」：删除前后 {@code /api/agent/operations} 的营业额、注单数、
     * 普/托拆分与盈亏必须逐项相同（不做切片 5 时该用例必红）。</p>
     */
    @Test
    void machineReportStillCountsDeletedPlayerHistory() throws Exception {
        String token = login();
        long player = createNormalPlayer(token, "报表历史玩家");
        long accountId = accountIdOf(player);
        insertBet(accountId, BET_PREFIX + "-report", "30.00");

        Map<String, Object> before = machineOperations(token);
        assertThat(((Number) before.get("betCount")).longValue()).isEqualTo(1L);
        assertThat(new BigDecimal(String.valueOf(before.get("turnover")))).isEqualByComparingTo("30.00");
        assertThat(new BigDecimal(String.valueOf(before.get("normalTurnover")))).isEqualByComparingTo("30.00");
        assertThat(new BigDecimal(String.valueOf(before.get("netProfit")))).isEqualByComparingTo("30.00");

        mockMvc.perform(delete("/api/admin/player-desk/players/" + player)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // 玩家行已不存在，但机器报表的**金额与单量合计**必须与删除前逐项一致
        assertThat(count("SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id=?", player)).isZero();
        Map<String, Object> after = machineOperations(token);
        for (String field : List.of("day", "betCount", "turnover", "netProfit",
                "pendingBetCount", "normalTurnover", "botTurnover")) {
            assertThat(after.get(field)).as("删除玩家后机器报表的 %s 不得静默变小", field)
                    .isEqualTo(before.get(field));
        }
        // 「活跃玩家数」按身份统计，玩家被物理删除后自然归零（其余合计不变）
        assertThat(((Number) before.get("activePlayerCount")).longValue()).isEqualTo(1L);
        assertThat(((Number) after.get("activePlayerCount")).longValue()).isZero();
        assertThat(new BigDecimal(String.valueOf(after.get("turnover")))).isEqualByComparingTo("30.00");
    }

    private Map<String, Object> machineOperations(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/agent/operations")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$");
    }

    // ------------------------------------------------------------------ helpers
    private long accountIdOf(long userId) {
        Long value = jdbc.queryForObject("SELECT id FROM demo_user_account WHERE sys_user_id=?", Long.class, userId);
        return value == null ? 0L : value;
    }

    private String memberCodeOf(long accountId) {
        return jdbc.queryForObject("SELECT member_code FROM demo_user_account WHERE id=?", String.class, accountId);
    }

    private BigDecimal balanceOf(long userId) {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE sys_user_id=?", BigDecimal.class, userId);
    }

    private BigDecimal agentScore() {
        return jdbc.queryForObject("SELECT score FROM agent WHERE id=?", BigDecimal.class, machineId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private long accessLinkCount(long userId) {
        return count("SELECT COUNT(*) FROM player_access_link WHERE user_id=?", userId);
    }

    private void insertBet(long accountId, String betCode, String stake) {
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type,
                     parameters_text, stake, odds_snapshot, settlement_status, net_profit, explanation,
                     created_at, settled_at)
                VALUES (?, ?, ?, ?, 1, 'FAN', '1', ?, 1.950, 'WIN', ?, '物理删除测试', ?, ?)
                """, accountId, betCode, betCode, BET_ISSUE, new BigDecimal(stake), new BigDecimal(stake),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private void insertPointRequest(long userId) {
        jdbc.update("""
                INSERT INTO player_point_request
                    (user_id, request_type, amount, status, client_message_id, source_message_id, requested_at)
                VALUES (?, 'TOP_UP', 10.00, 'PENDING', ?, 1, CURRENT_TIMESTAMP)
                """, userId, REQUEST_PREFIX);
    }

    private void insertNameChange(long userId) {
        jdbc.update("""
                INSERT INTO player_name_change_record (user_id, old_display_name, new_display_name)
                VALUES (?, '删除历史玩家', '删除历史玩家改名')
                """, userId);
    }

    private void insertLinkExpirationConfig(long userId) {
        jdbc.update("INSERT INTO player_link_expiration_config (user_id, days) VALUES (?, 7)", userId);
    }

    private void insertQuickBetPreference(long userId) {
        jdbc.update("""
                INSERT INTO player_quick_bet_preference
                    (user_id, amount_1, amount_2, amount_3, amount_4, amount_5)
                VALUES (?, 10.00, 20.00, 30.00, 40.00, 50.00)
                """, userId);
    }

    private void insertChatReadCursor(long userId) {
        Long roomId = jdbc.queryForObject("SELECT id FROM chat_room ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO chat_read_cursor (room_id, user_id, last_read_sequence) VALUES (?, ?, 0)",
                roomId, userId);
    }

    private void insertLoginLog(long userId) {
        jdbc.update("""
                INSERT INTO sys_login_log (username_snapshot, user_id, result)
                SELECT u.username, u.id, 'SUCCESS' FROM sys_user u WHERE u.id=?
                """, userId);
    }

    private void insertBehavior(long accountId) {
        jdbc.update("""
                INSERT INTO test_player_behavior(account_id, run_mode, enabled, stake_min, stake_max)
                VALUES (?, 'AUTOMATIC', TRUE, 2, 100.00)
                """, accountId);
    }

    private long createNormalPlayer(String token, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header(AuthController.REFRESH_AUDIENCE_HEADER, "CONSOLE")
                        .contentType("application/json")
                        .content("{\"username\":\"" + MACHINE_USER + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        List<Long> machineIds = jdbc.queryForList("SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);
        List<Long> owners = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username IN (?, ?)", Long.class, ADMIN, MACHINE_USER));
        List<Long> accounts = new ArrayList<>();
        if (!machineIds.isEmpty()) {
            accounts.addAll(jdbc.queryForList("SELECT id FROM demo_user_account WHERE agent_id IN ("
                    + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray()));
        }
        if (!accounts.isEmpty()) {
            owners.addAll(jdbc.queryForList("SELECT sys_user_id FROM demo_user_account WHERE id IN ("
                    + placeholders(accounts.size()) + ")", Long.class, accounts.toArray()));
        }
        List<Long> userIds = owners.stream().filter(Objects::nonNull).distinct().toList();

        // 物理删除后 user_id/account_id 已置空，按唯一业务键清理测试行
        jdbc.update("DELETE FROM game_bet WHERE bet_code LIKE ?", BET_PREFIX + "%");
        jdbc.update("DELETE FROM player_point_request WHERE client_message_id LIKE ?", REQUEST_PREFIX + "%");
        jdbc.update("DELETE FROM player_name_change_record WHERE new_display_name LIKE ?", "%改名");
        if (!accounts.isEmpty()) {
            deleteRows("DELETE FROM game_bet WHERE user_id IN (%s)", accounts);
            deleteRows("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
            deleteRows("DELETE FROM game_bet_edit_record WHERE account_id IN (%s)", accounts);
            deleteRows("DELETE FROM test_player_action WHERE account_id IN (%s)", accounts);
            deleteRows("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accounts);
        }
        if (!userIds.isEmpty()) {
            deleteRows("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", userIds);
            deleteRows("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", userIds, userIds);
            deleteRows("DELETE FROM player_name_change_record WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM player_link_expiration_config WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM player_quick_bet_preference WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM player_point_request WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM player_admin_notice WHERE recipient_user_id IN (%s) OR sender_user_id IN (%s)", userIds, userIds);
            deleteRows("DELETE FROM chat_user_mute WHERE user_id IN (%s) OR operator_user_id IN (%s) OR revoked_by IN (%s)", userIds, userIds, userIds);
            deleteRows("DELETE FROM chat_read_cursor WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM sys_login_log WHERE user_id IN (%s) OR username_snapshot IN ('物理删除管理员','物理删除机器')", userIds);
        }
        jdbc.update("DELETE FROM sys_login_log WHERE username_snapshot IN ('删除历史玩家','无历史玩家','重复删除玩家','并发删除玩家')");
        if (!accounts.isEmpty()) {
            deleteRows("DELETE FROM demo_user_account WHERE id IN (%s)", accounts);
        }
        // 物理删除后 user_id 已置空，按快照列清理这些历史行
        jdbc.update("DELETE FROM demo_balance_ledger WHERE player_name_snapshot IN ('删除历史玩家','无历史玩家','重复删除玩家','并发删除玩家')");
        jdbc.update("DELETE FROM game_bet WHERE player_name_snapshot IN ('删除历史玩家','无历史玩家','重复删除玩家','并发删除玩家')");
        if (!machineIds.isEmpty()) {
            deleteRows("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            deleteRows("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
        }
        if (!machineIds.isEmpty()) {
            deleteRows("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!userIds.isEmpty()) {
            deleteRows("DELETE FROM auth_session WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM sys_user_role WHERE user_id IN (%s)", userIds);
            deleteRows("DELETE FROM sys_user WHERE id IN (%s)", userIds);
        }
    }

    private void deleteRows(String sqlTemplate, List<Long>... groups) {
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
