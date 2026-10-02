package com.xupan.server.center.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;

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

    private static final String ADMIN = "center-settle-admin";
    private static final String PASSWORD = "CenterSettlePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private final List<Long> createdCenterAgentIds = new ArrayList<>();
    private long centerAgentId;
    private String agentToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        createdCenterAgentIds.clear();
        clean();
        long adminId = userRepository.insert(ADMIN, "结算管理员",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        adminToken = login(ADMIN, PASSWORD);

        MvcResult created = mockMvc.perform(post("/api/admin/center/agents")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentCode\":\"CENTER_SETTLE_A\",\"agentName\":\"结算测试代理\","
                                + "\"upstreamAccount\":\"center_settle_up_a\",\"creditLimit\":50000}"))
                .andExpect(status().isOk()).andReturn();
        String body = created.getResponse().getContentAsString();
        centerAgentId = ((Number) JsonPath.read(body, "$.agent.id")).longValue();
        agentToken = JsonPath.read(body, "$.token");
        createdCenterAgentIds.add(centerAgentId);

        mockMvc.perform(post("/api/admin/center/agents/" + centerAgentId + "/grants")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000,\"reason\":\"结算测试额度\",\"idempotencyKey\":\"settle-grant-1\"}"))
                .andExpect(status().isOk());
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
        acceptOrder("SETTLE-WIN-1", "FAN", "3.850", "100", "39999101", "1");

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999101", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.agentWinTotal").value(285.0))
                .andExpect(jsonPath("$.orders[0].settleResult").value("AGENT_WIN"))
                .andExpect(jsonPath("$.orders[0].settleAmount").value(285.0));

        // 三层：接单时 10000→9900 可用、100 冻结；结算后冻结 0、未结算 0、可用 9900+285=10185
        assertBalance("10185.00", "0.00", "0.00");
        assertThat(orderStatus("SETTLE-WIN-1")).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("""
                SELECT settle_result FROM center_order WHERE agent_order_no = 'SETTLE-WIN-1'
                """, String.class)).isEqualTo("AGENT_WIN");
        assertThat(orderDrawNumbers("SETTLE-WIN-1")).isEqualTo("1,2,3");
    }

    /**
     * 代理输：中心收走本金 100（本金已在冻结里，直接清零），可用额度不变。
     */
    @Test
    void agentLosesAndCenterCollectsStake() throws Exception {
        acceptOrder("SETTLE-LOSE-1", "FAN", "3.850", "100", "39999102", "2");

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999102", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(1))
                .andExpect(jsonPath("$.agentLoseTotal").value(100.0))
                .andExpect(jsonPath("$.orders[0].settleResult").value("AGENT_LOSE"));

        // 代理投的是番 2、开的番 1 → 输。本金在接单时已从可用划走，输掉只是未结算里的本金归中心，
        // 可用**不再变动**（再扣一次就是把同一笔本金扣两遍）。
        assertBalance("9900.00", "0.00", "0.00");
    }

    /** 平局/流局：本金原额退回可用额度（代理不赚不亏）。 */
    @Test
    void voidSettlementReturnsStakeToAvailable() throws Exception {
        // STRICT 玩法：参数 1,2；开奖番 2 → DRAW
        acceptOrder("SETTLE-VOID-1", "STRICT", "3.850", "100", "39999103", "1,2");

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999103", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(1))
                .andExpect(jsonPath("$.orders[0].settleResult").value("VOID"))
                .andExpect(jsonPath("$.orders[0].settleAmount").value(100.0));

        assertBalance("10000.00", "0.00", "0.00");
    }

    /** 幂等：同一期重复结算不重复赔钱，第二次结算数为 0。 */
    @Test
    void settlementIsIdempotent() throws Exception {
        acceptOrder("SETTLE-IDEM-1", "FAN", "3.850", "100", "39999104", "1");

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999104", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(1));
        assertBalance("10185.00", "0.00", "0.00");

        // 再结算一次：已 SETTLED 不再是 ACCEPTED，因此一处也不处理
        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999104", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(0))
                .andExpect(jsonPath("$.agentWinTotal").value(0.0));
        assertBalance("10185.00", "0.00", "0.00");
    }

    /** 无参数（params= 缺失）的订单无法结算，跳过而不是静默赔钱。 */
    @Test
    void unparsableOrderIsSkippedNotSettled() throws Exception {
        acceptOrder("SETTLE-SKIP-1", "FAN", "3.850", "100", "39999105", null);

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999105", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1));

        // 额度不动，订单仍为 ACCEPTED，等待人工处理
        assertBalance("9900.00", "100.00", "0.00");
        assertThat(orderStatus("SETTLE-SKIP-1")).isEqualTo("ACCEPTED");
    }

    /** 结算后账本与三层一致，且每一层都能靠自己的流水验算。 */
    @Test
    void ledgerStaysVerifiableAfterSettlement() throws Exception {
        acceptOrder("SETTLE-LEDGER-1", "FAN", "3.850", "100", "39999106", "1");
        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999106", 1)))
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
        acceptOrder("SETTLE-MULTI-1", "FAN", "3.850", "100", "39999107", "1");
        acceptOrder("SETTLE-MULTI-2", "FAN", "3.850", "50", "39999107", "2");
        assertBalance("9850.00", "150.00", "0.00");

        mockMvc.perform(post("/api/admin/center/agents/settlements")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(settlement("39999107", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(2))
                // 第一笔赢 285，第二笔输 50
                .andExpect(jsonPath("$.agentWinTotal").value(285.0))
                .andExpect(jsonPath("$.agentLoseTotal").value(50.0));

        // 可用 = 9850 + 285 = 10135：接单时两笔本金共 150 已离开可用；
        // 赢的那笔中心赔出净额 285 回到可用；输的那笔本金归中心，不再回到可用。
        assertBalance("10135.00", "0.00", "0.00");
    }

    private void acceptOrder(String orderNo, String playType, String odds, String amount, String issueNumber,
                             String params) throws Exception {
        String playText = params == null ? "" : "params=" + params;
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentOrderNo\":\"" + orderNo + "\",\"gameCode\":\"K28\","
                                + "\"issueNumber\":\"" + issueNumber + "\",\"playType\":\"" + playType + "\","
                                + "\"playText\":\"" + playText + "\",\"odds\":" + odds
                                + ",\"amount\":" + amount + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    private String settlement(String issueNumber, int fan) {
        return "{\"gameCode\":\"K28\",\"issueNumber\":\"" + issueNumber + "\","
                + "\"number\":" + fan + ",\"fan\":" + fan + ",\"parity\":\"ODD\",\"size\":\"SMALL\","
                + "\"drawNumbers\":\"1,2,3\"}";
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
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'CENTER_SETTLE_%')");
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'CENTER_SETTLE_%')");
        jdbc.update("DELETE FROM center_agent WHERE agent_code LIKE 'CENTER_SETTLE_%'");
        jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", ADMIN);
    }
}
