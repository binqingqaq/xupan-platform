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
import java.time.Instant;

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
    void createsGroupBoundCenterAccountWithInitialAvailableBalance() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long id = createCenterAccount(token, "A", "center_up_a", 10000);

        mockMvc.perform(get("/api/admin/center/agents").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].agentCode")
                        .value(org.hamcrest.Matchers.contains("center_up_a")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].availableBalance")
                        .value(org.hamcrest.Matchers.contains(10000.0)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].frozenBalance")
                        .value(org.hamcrest.Matchers.contains(0.0)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].upstreamUnsettled")
                        .value(org.hamcrest.Matchers.contains(0.0)));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM center_agent WHERE id = ?",
                String.class, id)).isNotEqualTo(PASSWORD);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND bucket = 'AVAILABLE' AND entry_type = 'OPENING_BALANCE'
                """, Long.class, id)).isEqualTo(1L);

        // 充值走可用额度，其它两层不受影响
        mockMvc.perform(post("/api/admin/center/agents/" + id + "/grants")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500,\"reason\":\"初始额度\",\"idempotencyKey\":\"center-grant-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(10500.0))
                .andExpect(jsonPath("$.frozenBalance").value(0.0))
                .andExpect(jsonPath("$.upstreamUnsettled").value(0.0));

        // 幂等：同键重放不写第二条流水
        mockMvc.perform(post("/api/admin/center/agents/" + id + "/grants")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500,\"reason\":\"初始额度\",\"idempotencyKey\":\"center-grant-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(10500.0));
        // 初始额度与充值分别留有流水；相同幂等键的充值只追加一次。
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND entry_type = 'CREDIT_GRANT'
                """, Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?
                """, Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT reason FROM center_agent_ledger WHERE idempotency_key = ?",
                String.class, "center-grant-1")).isEqualTo("初始额度");
        assertThat(jdbc.queryForObject("SELECT balance_before FROM center_agent_ledger WHERE idempotency_key = ?",
                java.math.BigDecimal.class, "center-grant-1")).isEqualByComparingTo("10000.00");
        assertThat(jdbc.queryForObject("SELECT balance_after FROM center_agent_ledger WHERE idempotency_key = ?",
                java.math.BigDecimal.class, "center-grant-1")).isEqualByComparingTo("10500.00");

        mockMvc.perform(post("/api/admin/center/agents")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
    }

    @Test
    void rejectsSharingUpstreamAccountAcrossCenterAgents() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long first = createCenterAccount(token, "B", "center_up_b", 5000);

        mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId("B") + ",\"username\":\"center_up_b2\","
                                + "\"password\":\"" + PASSWORD + "\",\"initialQuota\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CENTER_ACCOUNT_GROUP_TAKEN"));

        long otherGroupId = createGroup("C");
        mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + otherGroupId + ",\"username\":\"center_up_b\","
                                + "\"password\":\"" + PASSWORD + "\",\"initialQuota\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CENTER_ACCOUNT_USERNAME_TAKEN"));

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
        long id = createCenterAccount(token, "D", "center_up_d", 0);
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
                .andExpect(jsonPath("$.total").value(7));

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
        long id = createCenterAccount(token, "E", "center_up_e", 0);

        mockMvc.perform(get("/api/admin/center/agents/" + id + "/ledger").param("pageSize", "1000")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_LEDGER_QUERY_INVALID"));

        mockMvc.perform(get("/api/admin/center/agents/999999/ledger")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CENTER_AGENT_NOT_FOUND"));
    }

    @Test
    void filtersCenterOrdersAcrossAccountGroupMachineOrderGameIssueStatusAndTimeWithPaging() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long accountId = createCenterAccount(token, "FILTER", "center_up_filter", 1000);
        long groupId = jdbc.queryForObject("SELECT agent_group_id FROM center_agent WHERE id = ?", Long.class, accountId);
        insertOrder(accountId, "FILTER-202-1", 202L, 77L, "AU8", "39999801", "REJECTED");
        insertOrder(accountId, "FILTER-202-2", 202L, 77L, "AU8", "39999801", "REJECTED");
        insertOrder(accountId, "FILTER-203", 203L, 77L, "AU8", "39999802", "ACCEPTED");

        Instant from = Instant.now().minusSeconds(60);
        Instant to = Instant.now().plusSeconds(60);
        mockMvc.perform(get("/api/admin/center/agents/orders")
                        .param("centerAgentId", Long.toString(accountId))
                        .param("groupId", Long.toString(groupId))
                        .param("machineId", "77")
                        .param("localBetId", "202")
                        .param("centerOrderNo", "FILTER-202")
                        .param("gameCode", "AU8")
                        .param("issueNumber", "39999801")
                        .param("status", "REJECTED")
                        .param("createdFrom", from.toString())
                        .param("createdTo", to.toString())
                        .param("page", "2").param("pageSize", "1")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].localBetId").value(202))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"));

        mockMvc.perform(get("/api/admin/center/agents/orders")
                        .param("createdFrom", to.toString()).param("createdTo", from.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_ORDER_QUERY_INVALID"));
    }

    private void insertOrder(long accountId, String orderNo, long localBetId, long machineId,
                             String gameCode, String issueNumber, String status) {
        jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, local_bet_id, local_agent_id, game_code, issue_number,
                     play_type, play_text, odds, amount, status)
                VALUES (?, ?, ?, ?, ?, ?, 'FAN', 'filter test', 3.8500, 10.00, ?)
                """, accountId, orderNo, localBetId, machineId, gameCode, issueNumber, status);
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

    private long createCenterAccount(String token, String groupSuffix, String username, int initialQuota)
            throws Exception {
        long groupId = createGroup(groupSuffix);
        MvcResult result = mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"username\":\"" + username + "\","
                                + "\"password\":\"" + PASSWORD + "\","
                                + "\"initialQuota\":" + initialQuota + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.account.accountId"))
                .longValue();
        createdCenterAgentIds.add(id);
        return id;
    }

    private long createGroup(String suffix) {
        String code = "CENTER_TEST_GROUP_" + suffix;
        jdbc.update("INSERT INTO agent_group (group_code, display_name, status, created_by) "
                        + "VALUES (?, ?, 'ACTIVE', ?)", code, code, adminId);
        Long id = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, code);
        if (id == null) throw new IllegalStateException("创建测试渠道组后未取得 ID");
        return id;
    }

    private long groupId(String suffix) {
        return jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class,
                "CENTER_TEST_GROUP_" + suffix);
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
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'center_up_%'))");
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'center_up_%')");
        jdbc.update("DELETE FROM center_machine_session WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'center_up_%')");
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE 'center_up_%')");
        jdbc.update("DELETE FROM center_agent WHERE agent_code LIKE 'center_up_%'");
        jdbc.update("DELETE FROM agent_group WHERE group_code LIKE 'CENTER_TEST_GROUP_%'");
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
