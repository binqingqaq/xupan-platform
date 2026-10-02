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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 中心上游接单（阶段 B 第二步）。
 *
 * <p>覆盖《代理报网盘口与结算业务规则说明》§12、§14、§16.4：
 * Token 认证、接单校验、额度冻结、幂等、拒绝原因可回传。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CenterOrderControllerTest {

    private static final String ADMIN = "center-order-admin";
    private static final String PASSWORD = "CenterOrderPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private final List<Long> createdCenterAgentIds = new ArrayList<>();
    private long adminId;
    private long centerAgentId;
    private String agentToken;

    @BeforeEach
    void setUp() throws Exception {
        createdCenterAgentIds.clear();
        clean();
        adminId = userRepository.insert(ADMIN, "中心订单管理员",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        String adminToken = login(ADMIN, PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/admin/center/agents")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentCode\":\"CENTER_ORDER_A\",\"agentName\":\"接单测试代理\","
                                + "\"upstreamAccount\":\"center_order_up_a\",\"creditLimit\":50000}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        centerAgentId = ((Number) JsonPath.read(body, "$.agent.id")).longValue();
        agentToken = JsonPath.read(body, "$.token");
        createdCenterAgentIds.add(centerAgentId);

        // 给足可用额度，让"余额不足"成为可单独触发的分支
        mockMvc.perform(post("/api/admin/center/agents/" + centerAgentId + "/grants")
                        .header("Authorization", bearerOf(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000,\"reason\":\"接单测试额度\",\"idempotencyKey\":\"order-grant-1\"}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void acceptsOrderAndFreezesAgentCredit() throws Exception {
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-1001", "FAN", "3.850", "100", "39999001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.frozenAmount").value(100.0));

        // 原则 1：接单是"可用 → 冻结"，不是把余额直接扣掉
        mockMvc.perform(get("/api/admin/center/agents").header("Authorization", bearerOf(loginAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + centerAgentId + ")].availableBalance").value(
                        org.hamcrest.Matchers.contains(9900.0)))
                .andExpect(jsonPath("$[?(@.id == " + centerAgentId + ")].frozenBalance").value(
                        org.hamcrest.Matchers.contains(100.0)))
                .andExpect(jsonPath("$[?(@.id == " + centerAgentId + ")].upstreamUnsettled").value(
                        org.hamcrest.Matchers.contains(0.0)));

        // 两层各写一条流水，且账本仍然自洽
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'
                """, Long.class, centerAgentId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_order WHERE center_agent_id = ? AND status = 'ACCEPTED'
                """, Long.class, centerAgentId)).isEqualTo(1L);
    }

    /** 原则 6：同一代理同一订单号只受理一次，重复上报不重复冻结额度。 */
    @Test
    void duplicateReportIsIdempotentAndDoesNotFreezeTwice() throws Exception {
        String payload = order("ORD-1002", "FAN", "3.850", "100", "39999002");
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE agent_order_no = ?",
                Long.class, "ORD-1002")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'
                """, Long.class, centerAgentId)).as("重复上报不得重复冻结").isEqualTo(2L);
    }

    /** 校验失败要落"已拒绝"订单行并带回拒绝原因，代理据此走失败退单（§12.4）。 */
    @Test
    void rejectsInvalidOrdersWithReason() throws Exception {
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-2001", "FAN", "3.850", "100", "ABC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectCode").value("CENTER_ISSUE_INVALID"));

        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-2002", "NOT_A_PLAY", "3.850", "100", "39999003")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectCode").value("CENTER_PLAY_TYPE_INVALID"));

        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-2003", "FAN", "3.850", "0", "39999004")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectCode").value("CENTER_AMOUNT_INVALID"));

        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-2004", "FAN", "0", "100", "39999005")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectCode").value("CENTER_ODDS_INVALID"));

        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-2005", "FAN", "3.850", "200000", "39999006")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectCode").value("CENTER_AMOUNT_LIMIT_EXCEEDED"));

        // 拒绝的订单不占额度
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_order WHERE center_agent_id = ? AND status = 'REJECTED'
                """, Long.class, centerAgentId)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'
                """, Long.class, centerAgentId)).isZero();
    }

    /** 可用额度不足时拒绝，不做透支（否则余额与敞口脱钩）。 */
    @Test
    void rejectsWhenAvailableCreditIsInsufficient() throws Exception {
        // 接单校验的是**可用额度**（available），不是信用额度（credit_limit），
        // 所以这里直接把可用额度压到 50，留出"100 的单子放不下"的场景。
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, idempotency_key)
                VALUES (?, 'AVAILABLE', 'BET_FREEZE', 'OUT', 9950.00, 10000.00, 50.00, '压额度', 'order-squeeze-1')
                """, centerAgentId);
        jdbc.update("UPDATE center_agent SET available_balance = 50.00, frozen_balance = 9950.00 WHERE id = ?",
                centerAgentId);

        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-3001", "FAN", "3.850", "100", "39999007")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectCode").value("CENTER_AVAILABLE_INSUFFICIENT"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'BET_FREEZE'
                """, Long.class, centerAgentId)).as("只有预置那条，接单不得再冻结").isEqualTo(1L);
    }

    /** Token 认证：无效 Token 一律 401，且不区分失败原因。 */
    @Test
    void rejectsInvalidTokenWithoutRevealingReason() throws Exception {
        mockMvc.perform(post("/api/center/orders").header("Authorization", "Bearer ctr_not_a_real_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-4001", "FAN", "3.850", "100", "39999008")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CENTER_TOKEN_INVALID"));

        mockMvc.perform(post("/api/center/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-4002", "FAN", "3.850", "100", "39999009")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CENTER_TOKEN_INVALID"));

        // 代理停用后同一个 Token 也拿不到信息（还是同一个错误码）
        jdbc.update("UPDATE center_agent SET status = 'DISABLED' WHERE id = ?", centerAgentId);
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-4003", "FAN", "3.850", "100", "39999010")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CENTER_TOKEN_INVALID"));
    }

    /** 代理只能看到自己的订单。 */
    @Test
    void agentOnlySeesOwnOrders() throws Exception {
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-5001", "FAN", "3.850", "100", "39999011")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/center/orders").header("Authorization", bearerOf(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].agentOrderNo").value("ORD-5001"))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));

        // Token 自检返回三层额度
        mockMvc.perform(get("/api/center/me").header("Authorization", bearerOf(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentCode").value("CENTER_ORDER_A"))
                .andExpect(jsonPath("$.availableBalance").value(9900.0))
                .andExpect(jsonPath("$.frozenBalance").value(100.0))
                .andExpect(jsonPath("$.upstreamUnsettled").value(0.0));
    }

    /** 超管侧订单可见性与权限分离。 */
    @Test
    void adminCanReadOrdersAndSummary() throws Exception {
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("ORD-6001", "FAN", "3.850", "100", "39999012")))
                .andExpect(status().isOk());
        String adminToken = loginAdmin();

        mockMvc.perform(get("/api/admin/center/agents/orders").header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/admin/center/agents/orders/summary")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.status == 'ACCEPTED')].total").value(
                        org.hamcrest.Matchers.contains(1)));

        mockMvc.perform(get("/api/admin/center/agents/orders").param("pageSize", "1000")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_ORDER_QUERY_INVALID"));
    }

    /**
     * 代理只传玩法代码、不传展示文案时也必须能受理。
     *
     * <p>这条是**真实 MySQL 上暴露出来的缺陷**：H2 对缺省列更宽松，
     * 而生产 MySQL 在 {@code play_text NOT NULL} 时直接 500。
     * 中心不应因为"少一个展示字段"拒收订单，所以该列改为可空并在此固定。</p>
     */
    @Test
    void acceptsOrderWithoutPlayText() throws Exception {
        mockMvc.perform(post("/api/center/orders").header("Authorization", bearerOf(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentOrderNo\":\"ORD-7001\",\"gameCode\":\"K28\","
                                + "\"issueNumber\":\"39999020\",\"playType\":\"FAN\","
                                + "\"odds\":3.85,\"amount\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_order WHERE agent_order_no = 'ORD-7001' AND play_text IS NULL",
                Long.class)).isEqualTo(1L);
    }

    private String order(String orderNo, String playType, String odds, String amount, String issueNumber) {
        return "{\"agentOrderNo\":\"" + orderNo + "\",\"localBetId\":123,\"localAgentId\":18,"
                + "\"gameCode\":\"K28\",\"issueNumber\":\"" + issueNumber + "\","
                + "\"playType\":\"" + playType + "\",\"playText\":\"番摊 1\","
                + "\"odds\":" + odds + ",\"amount\":" + amount + "}";
    }

    private String loginAdmin() throws Exception {
        return login(ADMIN, PASSWORD);
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
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'CENTER_ORDER_%')");
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'CENTER_ORDER_%')");
        jdbc.update("DELETE FROM center_agent WHERE agent_code LIKE 'CENTER_ORDER_%'");
        jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", ADMIN);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", ADMIN);
    }
}
