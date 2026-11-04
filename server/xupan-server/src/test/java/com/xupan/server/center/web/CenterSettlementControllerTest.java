package com.xupan.server.center.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.center.service.CenterOrderService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 中心上游第二层结算（阶段 B 第三步）。
 *
 * <p>对应《代理报网盘口与结算业务规则说明》§10：玩家赢 → 中心赔代理；玩家输 → 中心从代理收。
 * 覆盖额度三层流转（冻结 → 未结算 → 可用）与幂等。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CenterSettlementControllerTest {

    private String issuePrefix;

    private static final String ADMIN = "center-settle-admin";
    private static final String PASSWORD = "CenterSettlePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CenterUpstreamAccountService centerAccountService;

    @Autowired
    private CenterOrderService centerOrderService;

    private long centerAgentId;
    private long machineId;
    private long adminUserId;
    private int betSequence;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        issuePrefix = "8" + String.format("%07d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000_000));
        betSequence = 0;
        adminUserId = userRepository.findByUsername(ADMIN).map(user -> user.id()).orElseGet(() ->
                userRepository.insert(ADMIN, "结算管理员",
                        new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE"));
        userRepository.assignRole(adminUserId, "ADMIN");
        adminToken = login(ADMIN, PASSWORD);

        jdbc.update("INSERT INTO agent_group (group_code, display_name, status, created_by) "
                + "VALUES ('CENTER_SETTLE_GROUP', '结算测试子账号', 'ACTIVE', ?)", adminUserId);
        Long groupId = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = 'CENTER_SETTLE_GROUP'",
                Long.class);
        centerAgentId = centerAccountService.createAccount(groupId, "center_settle_up_a", PASSWORD,
                new BigDecimal("10000.00"), null, adminUserId).account().accountId();
        jdbc.update("INSERT INTO agent (agent_code, display_name, group_id, account_user_id, system_owned, status) "
                        + "VALUES ('CENTER_SETTLE_MACHINE', '结算测试机器', ?, ?, FALSE, 'ACTIVE')",
                groupId, adminUserId);
        machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = 'CENTER_SETTLE_MACHINE'", Long.class);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /**
     * 代理赢：本金 100、赔率 3.85 → 净赔付 285，从中心划给代理可用额度。
     *
     * <p>参数用 {@code params=1}，开奖番位 1 → FAN 玩法命中。</p>
     */
    @Test
    void agentWinsAndCenterPaysOut() throws Exception {
        acceptOrder("SETTLE-WIN-1", "FAN", "3.850", "100", issue("01"), "1");
        confirmIssue(issue("01"), 1);

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issue("01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories").value(1))
                .andExpect(jsonPath("$.orders").value(1));

        // 最大净赔付 285 在接单时已冻结；中奖后风险被消耗，可用额度不再重复扣款。
        assertBalance("9715.00", "0.00", "0.00");
        assertThat(orderStatus("SETTLE-WIN-1")).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("""
                SELECT settle_result FROM center_order WHERE agent_order_no = 'SETTLE-WIN-1'
                """, String.class)).isEqualTo("AGENT_WIN");
        assertThat(orderDrawNumbers("SETTLE-WIN-1")).isEqualTo("1,2,3,4,5,6,7,8");
    }

    @Test
    void settlesSevenBallCenterOrderAgainstTheSeventhNumberAndPersistsSevenNumberSnapshot() throws Exception {
        String issueNumber = issue("09");
        ensureOpenIssue(issueNumber, 7);
        long localBetId = insertLocalBet("SETTLE-SEVEN-BALL-1", "FAN", "3.850", "100",
                issueNumber, "1", 7);
        CenterOrderService.Accepted accepted = centerOrderService.accept(centerAgentId, "SETTLE-SEVEN-BALL-1",
                localBetId, machineId, "AU8", issueNumber, "FAN", "params=1",
                new BigDecimal("3.850"), new BigDecimal("100.00"));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        jdbc.update("""
                UPDATE game_issue
                   SET status='CLOSED', phase='SETTLED', number_1=2, number_2=2, number_3=2,
                       number_4=2, number_5=2, number_6=2, number_7=1, closed_at=CURRENT_TIMESTAMP,
                       settled_at=CURRENT_TIMESTAMP, draw_result_version=1,
                       draw_confirmed_at=CURRENT_TIMESTAMP, draw_confirmed_by=?
                 WHERE game_code='AU8' AND issue_number=?
                """, adminUserId, issueNumber);

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issueNumber)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1));

        assertThat(orderStatus("SETTLE-SEVEN-BALL-1")).isEqualTo("SETTLED");
        assertThat(orderDrawNumbers("SETTLE-SEVEN-BALL-1")).isEqualTo("2,2,2,2,2,2,1");
        assertBalance("9715.00", "0.00", "0.00");
    }

    /**
     * 代理输：中心收走本金 100（本金已在冻结里，直接清零），可用额度不变。
     */
    @Test
    void agentLosesAndCenterCollectsStake() throws Exception {
        acceptOrder("SETTLE-LOSE-1", "FAN", "3.850", "100", issue("02"), "2");
        confirmIssue(issue("02"), 1);

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issue("02"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1));

        // 代理输时释放未消耗的风险冻结，并把本金计入中心可用额度。
        assertBalance("10100.00", "0.00", "0.00");
    }

    /** 平局/流局：本金原额退回可用额度（代理不赚不亏）。 */
    @Test
    void voidSettlementReturnsStakeToAvailable() throws Exception {
        // STRICT 玩法：参数 1,2；开奖番 2 → DRAW
        acceptOrder("SETTLE-VOID-1", "STRICT", "3.850", "100", issue("03"), "1,2");
        confirmIssue(issue("03"), 2);

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issue("03"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1));
        assertThat(jdbc.queryForObject("SELECT settle_result FROM center_order WHERE agent_order_no = 'SETTLE-VOID-1'",
                String.class)).isEqualTo("VOID");

        assertBalance("10000.00", "0.00", "0.00");
    }

    /** 幂等：同一期重复结算不重复赔钱，第二次结算数为 0。 */
    @Test
    void settlementIsIdempotent() throws Exception {
        acceptOrder("SETTLE-IDEM-1", "FAN", "3.850", "100", issue("04"), "1");
        confirmIssue(issue("04"), 1);

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issue("04"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1));
        assertBalance("9715.00", "0.00", "0.00");

        // 再结算一次：已 SETTLED 不再是 ACCEPTED，因此一处也不处理
        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issue("04"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(0));
        assertBalance("9715.00", "0.00", "0.00");
    }

    @Test
    void settlementCannotUseAnUnconfirmedOrRequestSuppliedResult() throws Exception {
        acceptOrder("SETTLE-DRAFT-1", "FAN", "3.850", "100", issue("08"), "1");
        jdbc.update("UPDATE game_issue SET status='CLOSED', phase='DRAWING' "
                + "WHERE game_code='AU8' AND issue_number=?", issue("08"));

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameCode\":\"AU8\",\"issueNumber\":\"" + issue("08") + "\","
                                + "\"number\":2,\"fan\":2,\"drawNumbers\":\"2,2,2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAW_RESULT_NOT_CONFIRMED"));

        assertThat(orderStatus("SETTLE-DRAFT-1")).isEqualTo("ACCEPTED");
    }

    /** 无法计算最大风险敞口的本地注单在中心接单时即被拒绝。 */
    @Test
    void unparsableLocalBetIsRejectedBeforeItCanBeSettled() {
        String orderNo = "SETTLE-SKIP-1";
        String issueNumber = issue("05");
        ensureOpenIssue(issueNumber, 8);
        long localBetId = insertLocalBet(orderNo, "FAN", "3.850", "100", issueNumber, null);

        CenterOrderService.Accepted rejected = centerOrderService.accept(centerAgentId, orderNo, localBetId, machineId,
                "AU8", issueNumber, "FAN", "", new BigDecimal("3.850"), new BigDecimal("100"));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectCode()).isEqualTo("CENTER_RISK_SNAPSHOT_INVALID");
        assertBalance("10000.00", "0.00", "0.00");
        assertThat(orderStatus(orderNo)).isEqualTo("REJECTED");
    }

    /** 结算后账本与三层一致，且每一层都能靠自己的流水验算。 */
    @Test
    void ledgerStaysVerifiableAfterSettlement() throws Exception {
        acceptOrder("SETTLE-LEDGER-1", "FAN", "3.850", "100", issue("06"), "1");
        confirmIssue(issue("06"), 1);
        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issue("06"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/center/agents/" + centerAgentId + "/ledger")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT l.bucket, l.balance_after,
                           CASE l.bucket
                               WHEN 'AVAILABLE' THEN a.available_balance
                               WHEN 'FROZEN' THEN a.frozen_balance
                               WHEN 'UPSTREAM_UNSETTLED' THEN a.upstream_unsettled
                               WHEN 'CREDIT_LIMIT' THEN a.credit_limit
                           END AS current_value
                      FROM center_agent_ledger l
                      JOIN center_agent a ON a.id = l.center_agent_id
                     WHERE l.center_agent_id = ?
                       AND l.id IN (SELECT MAX(id) FROM center_agent_ledger
                                     WHERE center_agent_id = ? GROUP BY bucket)
                ) t WHERE t.balance_after <> t.current_value
                """, Long.class, centerAgentId, centerAgentId)).as("账本与三层必须一致").isZero();
    }

    /** 累计多笔订单一起结算。 */
    @Test
    void settlesMultipleOrdersOfSameIssueTogether() throws Exception {
        acceptOrder("SETTLE-MULTI-1", "FAN", "3.850", "100", issue("07"), "1");
        acceptOrder("SETTLE-MULTI-2", "FAN", "3.850", "50", issue("07"), "2");
        confirmIssue(issue("07"), 1);
        assertBalance("9572.50", "427.50", "0.00");

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement(issue("07"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(2));

        // 第一笔中奖消耗 285 冻结风险，第二笔输单释放 142.50 并把本金 50 计入中心余额。
        assertBalance("9765.00", "0.00", "0.00");
    }

    @Test
    void legacyHttpOrderEndpointIsDenied() throws Exception {
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
    }

    private void acceptOrder(String orderNo, String playType, String odds, String amount, String issueNumber,
                             String params) throws Exception {
        ensureOpenIssue(issueNumber, 8);
        String playText = params == null ? "" : "params=" + params;
        long localBetId = insertLocalBet(orderNo, playType, odds, amount, issueNumber, params);
        CenterOrderService.Accepted accepted = centerOrderService.accept(centerAgentId, orderNo, localBetId, machineId,
                "AU8", issueNumber, playType, playText, new BigDecimal(odds), new BigDecimal(amount));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        jdbc.update("UPDATE center_order SET ball_number = 1 WHERE agent_order_no = ?", orderNo);
    }

    private void confirmIssue(String issueNumber, int number) {
        jdbc.update("""
                UPDATE game_issue
                   SET status='CLOSED', phase='SETTLED', number_1=?, number_2=?, number_3=?, number_4=?,
                       number_5=?, number_6=?, number_7=?, number_8=?, closed_at=CURRENT_TIMESTAMP,
                       settled_at=CURRENT_TIMESTAMP, draw_result_version=1,
                       draw_confirmed_at=CURRENT_TIMESTAMP, draw_confirmed_by=?
                 WHERE game_code='AU8' AND issue_number=?
                """, number, number, number, number, number, number, number, number, adminUserId, issueNumber);
    }

    private void ensureOpenIssue(String issueNumber, int ballCount) {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM game_issue WHERE game_code='AU8' AND issue_number=?",
                Long.class, issueNumber);
        if (exists != null && exists > 0) return;
        jdbc.update("""
                INSERT INTO game_issue (game_code, issue_number, status, phase, ball_count,
                                        issue_started_at, opened_at, betting_ends_at, draw_ends_at)
                VALUES ('AU8', ?, 'OPEN', 'BETTING', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, issueNumber, ballCount);
    }

    private long insertLocalBet(String orderNo, String playType, String odds, String amount, String issueNumber,
                                String params) {
        return insertLocalBet(orderNo, playType, odds, amount, issueNumber, params, 1);
    }

    private long insertLocalBet(String orderNo, String playType, String odds, String amount, String issueNumber,
                                String params, int ballNumber) {
        String betCode = "CENTER_SETTLE_BET_" + (++betSequence);
        jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, machine_id_snapshot, agent_id_snapshot,
                     player_kind_snapshot)
                VALUES ('AU8', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL')
                """, betCode, betCode, issueNumber, ballNumber, playType, params == null ? "" : params,
                new BigDecimal(amount), new BigDecimal(odds), machineId, machineId);
        return jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
    }

    private void ensureSevenBallIssue(String issueNumber, List<Integer> numbers) {
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, ball_count,
                     number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                     issue_started_at, opened_at, closed_at, settled_at,
                     draw_result_version, draw_confirmed_at, draw_confirmed_by)
                VALUES ('AU8', ?, 'CLOSED', 'SETTLED', 7, ?, ?, ?, ?, ?, ?, ?, NULL,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        1, CURRENT_TIMESTAMP, ?)
                """, issueNumber, numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3),
                numbers.get(4), numbers.get(5), numbers.get(6), adminUserId);
    }

    private void ensureConfirmedIssue(String issueNumber, int firstBall) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM game_issue WHERE game_code='AU8' AND issue_number=?",
                Long.class, issueNumber);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, issue_started_at, opened_at, closed_at,
                     settled_at, draw_result_version, draw_confirmed_at, draw_confirmed_by)
                VALUES ('AU8', ?, 'CLOSED', 'SETTLED', ?, 2, 3, 4, 5, 6, 7, 8,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        1, CURRENT_TIMESTAMP, ?)
                """, issueNumber, firstBall, adminUserId);
    }

    private void insertDraftIssue(String issueNumber) {
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, issue_started_at, draw_result_version)
                VALUES ('AU8', ?, 'CLOSED', 'DRAWING', 1, 2, 3, 4, 5, 6, 7, 8,
                        CURRENT_TIMESTAMP, 1)
                """, issueNumber);
    }

    private String settlement(String issueNumber) {
        return "{\"gameCode\":\"AU8\",\"issueNumber\":\"" + issueNumber + "\"}";
    }

    private void assertBalance(String available, String frozen, String unsettled) {
        BigDecimal a = jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
        BigDecimal f = jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
        BigDecimal u = jdbc.queryForObject("SELECT upstream_unsettled FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
        assertThat(a).isEqualByComparingTo(available);
        assertThat(f).isEqualByComparingTo(frozen);
        assertThat(u).isEqualByComparingTo(unsettled);
    }

    private String orderStatus(String agentOrderNo) {
        return jdbc.queryForObject("SELECT status FROM center_order WHERE agent_order_no = ?",
                String.class, agentOrderNo);
    }

    private String orderDrawNumbers(String agentOrderNo) {
        return jdbc.queryForObject("SELECT draw_numbers FROM center_order WHERE agent_order_no = ?",
                String.class, agentOrderNo);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearerOf(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = 'center_settle_up_a'))");
        jdbc.update("DELETE FROM center_machine_session WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = 'center_settle_up_a')");
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = 'center_settle_up_a')");
        jdbc.update("DELETE FROM game_bet WHERE machine_id_snapshot IN "
                + "(SELECT id FROM agent WHERE agent_code = 'CENTER_SETTLE_MACHINE')");
        if (issuePrefix != null) {
            jdbc.update("DELETE FROM chat_robot_dispatch WHERE game_event_id IN "
                    + "(SELECT id FROM game_issue_event WHERE issue_number LIKE ?)", issuePrefix + "%");
            jdbc.update("DELETE FROM game_issue_event WHERE issue_number LIKE ?", issuePrefix + "%");
            jdbc.update("DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number LIKE ? "
                    + "AND draw_confirmed_at IS NULL", issuePrefix + "%");
        }
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = 'center_settle_up_a')");
        jdbc.update("DELETE FROM center_agent WHERE agent_code = 'center_settle_up_a'");
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = 'CENTER_SETTLE_MACHINE')");
        jdbc.update("DELETE FROM agent WHERE agent_code = 'CENTER_SETTLE_MACHINE'");
        jdbc.update("DELETE FROM agent_group WHERE group_code = 'CENTER_SETTLE_GROUP'");
        jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        // The test operator is retained because immutable confirmed issues reference draw_confirmed_by.
    }

    private String issue(String suffix) {
        return issuePrefix + suffix;
    }
}
