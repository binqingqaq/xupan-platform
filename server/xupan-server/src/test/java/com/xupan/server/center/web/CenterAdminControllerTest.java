package com.xupan.server.center.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 中心上游账户与额度三层（账本原则 1、2、3、8、9）。
 *
 * <p>这些用例固定住阶段 B 的地基：三层额度是三个不同事实、账本追加且可验算、
 * 上游账号不可共用、Token 只保存哈希且只返回一次。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CenterAdminControllerTest {

    private static final String ADMIN = "center-admin-test";
    private static final String PASSWORD = "CenterAdminPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private final List<Long> createdCenterAgentIds = new ArrayList<>();
    private long adminId;

    @BeforeEach
    void setUp() {
        createdCenterAgentIds.clear();
        clean();
        adminId = userRepository.insert(ADMIN, "中心上游管理员",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void createsCenterAgentWithThreeSeparateBalanceBuckets() throws Exception {
        String token = login(ADMIN, PASSWORD);

        MvcResult created = mockMvc.perform(post("/api/admin/center/agents")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentCode\":\"CENTER_TEST_A\",\"agentName\":\"中心测试代理A\","
                                + "\"upstreamAccount\":\"center_up_a\",\"creditLimit\":10000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.agentCode").value("CENTER_TEST_A"))
                .andExpect(jsonPath("$.agent.status").value("ACTIVE"))
                .andExpect(jsonPath("$.agent.creditLimit").value(10000.0))
                // 原则 1：三层是三个不同字段，创建时三者互相独立
                .andExpect(jsonPath("$.agent.availableBalance").value(0.0))
                .andExpect(jsonPath("$.agent.frozenBalance").value(0.0))
                .andExpect(jsonPath("$.agent.upstreamUnsettled").value(0.0))
                // 原则 9：只在创建时返回一次 Token 原文，并给出可展示前缀
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.agent.tokenPrefix").isNotEmpty())
                .andReturn();
        String createdBody = created.getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(createdBody, "$.agent.id")).longValue();
        createdCenterAgentIds.add(id);
        String rawToken = JsonPath.read(createdBody, "$.token");
        assertThat(rawToken).startsWith("ctr_");

        // 原则 9：库里只有哈希，绝无原文
        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM center_agent WHERE id = ?", String.class, id);
        assertThat(storedHash).isNotEqualTo(rawToken);
        assertThat(storedHash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_agent WHERE token_hash = ?", Long.class, rawToken))
                .as("Token 原文不得入库").isZero();

        // 列表不再返回 Token 原文
        mockMvc.perform(get("/api/admin/center/agents").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].token").doesNotExist());

        // 充值走可用额度，其它两层不受影响
        mockMvc.perform(post("/api/admin/center/agents/" + id + "/grants")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500,\"reason\":\"初始额度\",\"idempotencyKey\":\"center-grant-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(500.0))
                .andExpect(jsonPath("$.frozenBalance").value(0.0))
                .andExpect(jsonPath("$.upstreamUnsettled").value(0.0));

        // 幂等：同键重放不写第二条流水
        mockMvc.perform(post("/api/admin/center/agents/" + id + "/grants")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500,\"reason\":\"初始额度\",\"idempotencyKey\":\"center-grant-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(500.0));
        // 创建时写了一条 CREDIT_LIMIT 流水，充值只应写一条 CREDIT_GRANT；重放不再写
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'CREDIT_GRANT'
                """, Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?
                """, Long.class, id)).isEqualTo(2L);
    }

    @Test
    void rejectsSharingUpstreamAccountAcrossCenterAgents() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long first = createCenterAgent(token, "CENTER_TEST_B", "center_up_b", 5000);

        // 原则 8：同一上游账号不得被两个中心代理共用
        mockMvc.perform(post("/api/admin/center/agents")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentCode\":\"CENTER_TEST_C\",\"agentName\":\"中心测试代理C\","
                                + "\"upstreamAccount\":\"center_up_b\",\"creditLimit\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CENTER_UPSTREAM_ACCOUNT_TAKEN"));

        assertThat(first).isPositive();
    }

    /**
     * 账本原则 1、2、3：三层各自记账，且每一层都能靠自己的流水验算。
     *
     * <p>这里直接跑一次"可用 → 冻结 → 上游未结算 → 可用（结算入账）"的完整流转，
     * 这是第二层结算要复用的路径。</p>
     */
    @Test
    void ledgerRecordsEveryBucketChangeAndStaysVerifiable() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long id = createCenterAgent(token, "CENTER_TEST_D", "center_up_d", 10000);
        mockMvc.perform(post("/api/admin/center/agents/" + id + "/grants")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000,\"reason\":\"初始额度\",\"idempotencyKey\":\"ledger-grant-1\"}"))
                .andExpect(status().isOk());

        // 用仓储直接驱动三层流转，模拟"接单 → 开奖 → 结算"对额度的占用与释放
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, idempotency_key)
                VALUES (?, 'AVAILABLE', 'BET_FREEZE', 'OUT', 300.00, 1000.00, 700.00, '接单冻结', 'ledger-freeze-1')
                """, id);
        jdbc.update("UPDATE center_agent SET available_balance = 700.00 WHERE id = ?", id);
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, idempotency_key)
                VALUES (?, 'FROZEN', 'BET_FREEZE', 'IN', 300.00, 0.00, 300.00, '接单冻结', 'ledger-freeze-2')
                """, id);
        jdbc.update("UPDATE center_agent SET frozen_balance = 300.00 WHERE id = ?", id);

        // 开奖后：冻结 → 上游未结算
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, idempotency_key)
                VALUES (?, 'FROZEN', 'UPSTREAM_MOVE', 'OUT', 300.00, 300.00, 0.00, '开奖转未结算', 'ledger-move-1')
                """, id);
        jdbc.update("UPDATE center_agent SET frozen_balance = 0.00 WHERE id = ?", id);
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, idempotency_key)
                VALUES (?, 'UPSTREAM_UNSETTLED', 'UPSTREAM_MOVE', 'IN', 300.00, 0.00, 300.00,
                        '开奖转未结算', 'ledger-move-2')
                """, id);
        jdbc.update("UPDATE center_agent SET upstream_unsettled = 300.00 WHERE id = ?", id);

        // 第二层结算：代理赢，中心赔 300 给代理的可用额度，未结算清零
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, idempotency_key)
                VALUES (?, 'UPSTREAM_UNSETTLED', 'SETTLE_WIN', 'OUT', 300.00, 300.00, 0.00, '结算派出', 'ledger-settle-1')
                """, id);
        jdbc.update("UPDATE center_agent SET upstream_unsettled = 0.00 WHERE id = ?", id);
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, idempotency_key)
                VALUES (?, 'AVAILABLE', 'SETTLE_WIN', 'IN', 300.00, 700.00, 1000.00, '结算收入', 'ledger-settle-2')
                """, id);
        jdbc.update("UPDATE center_agent SET available_balance = 1000.00 WHERE id = ?", id);

        // 读接口给出对账标志
        mockMvc.perform(get("/api/admin/center/agents/" + id + "/ledger")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.total").value(8));

        // 每一层的最新流水都应等于该层当前值（账本可独立验算）
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
                """, Long.class, id, id)).as("账本与三层额度必须一致").isZero();

        // 三层互不串味：可用 1000、冻结 0、未结算 0
        mockMvc.perform(get("/api/admin/center/agents").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].availableBalance").value(
                        org.hamcrest.Matchers.contains(1000.0)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].frozenBalance").value(
                        org.hamcrest.Matchers.contains(0.0)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].upstreamUnsettled").value(
                        org.hamcrest.Matchers.contains(0.0)));
    }

    @Test
    void ledgerQueryValidatesPagingAndAgentExistence() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long id = createCenterAgent(token, "CENTER_TEST_E", "center_up_e", 100);

        mockMvc.perform(get("/api/admin/center/agents/" + id + "/ledger").param("pageSize", "1000")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_LEDGER_QUERY_INVALID"));

        mockMvc.perform(get("/api/admin/center/agents/999999/ledger")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CENTER_AGENT_NOT_FOUND"));
    }

    /** 没有 CENTER_AGENT_MANAGE 的普通用户不得管理中心代理。 */
    @Test
    void requiresCenterAgentManagePermission() throws Exception {
        String token = login(ADMIN, PASSWORD);
        String plainUser = "center-plain-user";
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", plainUser);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", plainUser);
        jdbc.update("INSERT INTO sys_user (username, display_name, password_hash, status, auth_mode, internal_code) "
                        + "VALUES (?, '中心普通用户', ?, 'ACTIVE', 'PASSWORD', ?)",
                plainUser, new BCryptPasswordEncoder().encode(PASSWORD), "CENTER-PLAIN-" + System.nanoTime());
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, plainUser);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) "
                + "SELECT ?, id FROM sys_role WHERE role_code = 'USER'", userId);

        String userToken = login(plainUser, PASSWORD);
        // URL 层的权限规则（SecurityConfig）会先拒绝，因此这里拿到的是框架级错误码。
        // 保留这条断言是为了固定"普通用户进不来"这个事实；服务层的 CENTER_AGENT_FORBIDDEN
        // 仍然覆盖"权限规则被绕过"的兜底场景。
        mockMvc.perform(get("/api/admin/center/agents").header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));

        // 有权限的管理员仍可用
        mockMvc.perform(get("/api/admin/center/agents").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private long createCenterAgent(String token, String code, String upstreamAccount, int creditLimit)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/center/agents")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentCode\":\"" + code + "\",\"agentName\":\"" + code + "名称\","
                                + "\"upstreamAccount\":\"" + upstreamAccount + "\","
                                + "\"creditLimit\":" + creditLimit + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.agent.id")).longValue();
        createdCenterAgentIds.add(id);
        return id;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'CENTER_TEST_%')");
        jdbc.update("UPDATE agent SET center_agent_id = NULL WHERE center_agent_id IS NOT NULL");
        jdbc.update("DELETE FROM center_agent WHERE agent_code LIKE 'CENTER_TEST_%'");
        jdbc.update("DELETE FROM sys_login_log WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, 'center-plain-user'))", ADMIN);
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, 'center-plain-user'))", ADMIN);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, 'center-plain-user'))", ADMIN);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, 'center-plain-user'))", ADMIN);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, 'center-plain-user')", ADMIN);
    }
}
