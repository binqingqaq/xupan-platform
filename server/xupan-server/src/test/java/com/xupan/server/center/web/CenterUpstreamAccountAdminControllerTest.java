package com.xupan.server.center.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.agent.service.AgentAdminService;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CenterUpstreamAccountAdminControllerTest {

    private static final String ADMIN = "center-account-admin-test";
    private static final String PLAIN_USER = "center-account-plain-test";
    private static final String PASSWORD = "CenterAccountAdmin123";
    private static final String ACCOUNT_PREFIX = "CENTER_ACCOUNT_TEST_";
    private static final String GROUP_CODE = "CENTER_ACCOUNT_TEST_GROUP";
    private static final String MACHINE_CODE = "CENTER_ACCOUNT_MACHINE_TEST";
    private static final String MACHINE_USER = "center-account-machine-test";
    private static final String PLAYER_CODE = "CENTER_ACCOUNT_PLAYER_TEST";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AgentAdminService agentAdminService;

    private long adminId;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "中心账号管理员",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void createsAndManagesAccountWithoutReturningPassword() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long groupId = createGroup();

        MvcResult created = mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"username\":\"" + ACCOUNT_PREFIX
                                + "A\",\"password\":\"InitialPass123\",\"initialQuota\":125.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.groupId").value(groupId))
                .andExpect(jsonPath("$.account.groupName").value("中心账号测试组"))
                .andExpect(jsonPath("$.account.username").value(ACCOUNT_PREFIX + "A"))
                .andExpect(jsonPath("$.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.account.availableBalance").value(125.50))
                .andExpect(jsonPath("$.account.frozenBalance").value(0.0))
                .andExpect(jsonPath("$.account.openStake").value(0.0))
                .andExpect(jsonPath("$.account.online").value(false))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        String createdJson = created.getResponse().getContentAsString();
        assertThat(createdJson).doesNotContain("InitialPass123");
        long accountId = ((Number) JsonPath.read(createdJson, "$.account.accountId")).longValue();
        Instant expiresAt = Instant.parse(JsonPath.read(createdJson, "$.account.expiresAt"));
        assertThat(expiresAt).isAfter(Instant.now().plusSeconds(6L * 24 * 60 * 60));
        assertThat(expiresAt).isBefore(Instant.now().plusSeconds(8L * 24 * 60 * 60));
        assertThat(jdbc.queryForObject("SELECT credit_limit FROM center_agent WHERE id = ?",
                java.math.BigDecimal.class, accountId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_agent_ledger
                 WHERE center_agent_id = ? AND bucket = 'AVAILABLE' AND entry_type = 'OPENING_BALANCE'
                """, Long.class, accountId)).isEqualTo(1L);

        mockMvc.perform(get("/api/admin/center/accounts").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.accountId == " + accountId + ")].username")
                        .value(org.hamcrest.Matchers.contains(ACCOUNT_PREFIX + "A")));

        mockMvc.perform(patch("/api/admin/center/accounts/" + accountId + "/status")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"reason\":\"安全策略停用\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(patch("/api/admin/center/accounts/" + accountId + "/status")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"reason\":\"复核后恢复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/admin/center/accounts/" + accountId + "/expiry")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2037-01-01T00:00:00Z\",\"reason\":\"续期申请\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value("2037-01-01T00:00:00Z"));

        mockMvc.perform(post("/api/admin/center/accounts/" + accountId + "/password")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"ChangedPass456\",\"reason\":\"凭据轮换\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId));

        assertThat(latestAudit(accountId, "/status")).contains("reason=复核后恢复", "beforeStatus=DISABLED", "afterStatus=ACTIVE");
        assertThat(latestAudit(accountId, "/expiry")).contains("reason=续期申请", "beforeExpiresAt=", "afterExpiresAt=");
        assertThat(latestAudit(accountId, "/password")).contains("reason=凭据轮换", "passwordChanged=true")
                .doesNotContain("ChangedPass456");
    }

    @Test
    void physicallyDeletesCenterScopeKeepsMachineLedgerAndPlayerSettlementData() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long groupId = createGroup();
        long accountId = createCenterAccount(adminToken, "DELETE", groupId);
        long machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "中心删除测试机器",
                PASSWORD, groupId, adminId).id();
        jdbc.update("UPDATE agent SET report_type = 'CENTER', center_agent_id = ?, report_auto = TRUE, score = 825.00 "
                + "WHERE id = ?", accountId, machineId);
        long playerId = insertPlayer(machineId);
        long betId = insertBet(playerId, "DELETE");
        long acceptedOrderId = insertCenterOrder(accountId, machineId, betId, "DELETE-ACCEPTED", "ACCEPTED");
        long settledOrderId = insertCenterOrder(accountId, machineId, betId, "DELETE-SETTLED", "SETTLED");
        jdbc.update("""
                INSERT INTO center_machine_session (center_agent_id, machine_agent_id, expires_at)
                VALUES (?, ?, ?)
                """, accountId, machineId, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, center_agent_id, attempt_no, status, report_type,
                     idempotency_key, upstream_order_no)
                VALUES (?, ?, ?, 1, 'ACCEPTED', 'CENTER', ?, ?)
                """, betId, machineId, accountId, "CENTER-DELETE-REPORT-" + betId, "BET-" + betId);
        jdbc.update("""
                INSERT INTO center_order_disposition
                    (center_order_id, action, from_status, to_status, amount, idempotency_key)
                VALUES (?, 'CONFIRM_ACCEPTED', 'PENDING', 'ACCEPTED', 0.00, ?)
                """, acceptedOrderId, "CENTER-DELETE-DISPOSITION-" + acceptedOrderId);
        jdbc.update("""
                INSERT INTO center_reconciliation_disposition
                    (difference_type, bet_id, resolution, center_agent_id, idempotency_key)
                VALUES ('STATUS_MISMATCH', ?, 'ACKNOWLEDGED', ?, ?)
                """, betId, accountId, "CENTER-DELETE-RECON-" + betId);
        jdbc.update("""
                INSERT INTO center_risk_hit
                    (center_agent_id, rule_code, rule_category, observed_value, threshold_value)
                VALUES (?, 'CENTER_AMOUNT_LIMIT_EXCEEDED', 'LIMIT', 120.00, 100.00)
                """, accountId);
        jdbc.update("""
                INSERT INTO agent_ledger
                    (agent_id, entry_type, direction, amount, balance_before, balance_after,
                     reason, reference_type, reference_id, idempotency_key)
                VALUES (?, 'CENTER_RISK_RESERVE', 'OUT', 200.00, 1000.00, 800.00,
                        'center risk reserve', 'CENTER_ORDER', ?, ?),
                       (?, 'CENTER_RISK_RELEASE', 'IN', 25.00, 800.00, 825.00,
                        'center risk release', 'CENTER_ORDER', ?, ?)
                """, machineId, Long.toString(acceptedOrderId), "CENTER-DELETE-RESERVE-" + acceptedOrderId,
                machineId, Long.toString(settledOrderId), "CENTER-DELETE-RELEASE-" + settledOrderId);
        jdbc.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after, reason, operator_name)
                VALUES (?, 'PLAYER_TOP_UP', 10.00, 10.00, 20.00, 'test wallet history', 'test')
                """, playerId);

        long plainUserId = userRepository.insert(PLAIN_USER, "中心删除普通用户",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(plainUserId, "USER");
        String plainToken = login(PLAIN_USER, PASSWORD);
        mockMvc.perform(delete("/api/admin/center/accounts/" + accountId)
                        .header("Authorization", bearer(plainToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"permission check\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/admin/center/accounts/" + accountId)
                        .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"中心账号停止使用\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent WHERE id = ?", Long.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE center_agent_id = ?",
                Long.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?",
                Long.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_order_disposition WHERE center_order_id IN (?, ?)",
                Long.class, acceptedOrderId, settledOrderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_reconciliation_disposition WHERE center_agent_id = ?",
                Long.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_risk_hit WHERE center_agent_id = ?",
                Long.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_machine_session WHERE center_agent_id = ?",
                Long.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?",
                Long.class, betId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_user_account WHERE id = ?", Long.class, playerId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE id = ?", Long.class, betId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_balance_ledger WHERE user_id = ?",
                Long.class, playerId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT center_report_deleted_at IS NOT NULL FROM game_bet WHERE id = ?",
                Boolean.class, betId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT report_type FROM agent WHERE id = ?", String.class, machineId))
                .isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT center_agent_id FROM agent WHERE id = ?", Long.class, machineId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT report_auto FROM agent WHERE id = ?", Boolean.class, machineId))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT score FROM agent WHERE id = ?", java.math.BigDecimal.class, machineId))
                .isEqualByComparingTo("825.00");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_ledger
                 WHERE agent_id = ? AND entry_type IN ('CENTER_RISK_RESERVE', 'CENTER_RISK_RELEASE')
                """, Long.class, machineId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT SUM(amount) FROM agent_ledger
                 WHERE agent_id = ? AND entry_type IN ('CENTER_RISK_RESERVE', 'CENTER_RISK_RELEASE')
                """, java.math.BigDecimal.class, machineId)).isEqualByComparingTo("225.00");
        assertThat(jdbc.queryForObject("""
                SELECT SUM(balance_before) FROM agent_ledger
                 WHERE agent_id = ? AND entry_type IN ('CENTER_RISK_RESERVE', 'CENTER_RISK_RELEASE')
                """, java.math.BigDecimal.class, machineId)).isEqualByComparingTo("1800.00");
        assertThat(jdbc.queryForObject("""
                SELECT SUM(balance_after) FROM agent_ledger
                 WHERE agent_id = ? AND entry_type IN ('CENTER_RISK_RESERVE', 'CENTER_RISK_RELEASE')
                """, java.math.BigDecimal.class, machineId)).isEqualByComparingTo("1625.00");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_ledger
                 WHERE agent_id = ? AND reference_type = 'CENTER_ACCOUNT_DELETED' AND reference_id = ?
                   AND entry_type IN ('CENTER_RISK_RESERVE', 'CENTER_RISK_RELEASE')
                """, Long.class, machineId, Long.toString(accountId))).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT request_summary FROM sys_operation_log
                 WHERE permission_code = 'CENTER_AGENT_MANAGE' AND http_method = 'DELETE' AND resource_id = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, Long.toString(accountId)))
                .contains("中心账号停止使用", "username=" + ACCOUNT_PREFIX + "DELETE",
                        "deletedCenterOrders=2", "detachedMachineLedgerRows=2", "machineBalancesChanged=false");
    }

    @Test
    void refusesDeletionWhenLegacyCenterAttemptHasNoAccountOwner() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long groupId = createGroup();
        long accountId = createCenterAccount(adminToken, "UNOWNED", groupId);
        long machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "中心归属待核对机器",
                PASSWORD, groupId, adminId).id();
        long playerId = insertPlayer(machineId);
        long betId = insertBet(playerId, "UNOWNED");
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, attempt_no, status, report_type, idempotency_key)
                VALUES (?, ?, 1, 'REJECTED', 'CENTER', ?)
                """, betId, machineId, "CENTER-UNOWNED-REPORT-" + betId);

        mockMvc.perform(delete("/api/admin/center/accounts/" + accountId)
                        .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"归属阻止测试\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CENTER_ACCOUNT_DELETE_ATTEMPTS_UNRESOLVED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent WHERE id = ?", Long.class, accountId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?",
                Long.class, betId)).isEqualTo(1L);
    }

    @Test
    void listsMachineSessionStateLoginExpiryAndRevocationReason() throws Exception {
        String token = login(ADMIN, PASSWORD);
        long groupId = createGroup();
        MvcResult created = mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"username\":\"" + ACCOUNT_PREFIX
                                + "SESSION\",\"password\":\"InitialPass123\",\"initialQuota\":0}"))
                .andExpect(status().isOk()).andReturn();
        long accountId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.account.accountId"))
                .longValue();
        long machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "中心会话机器",
                PASSWORD, groupId, adminId).id();
        Instant authenticatedAt = Instant.now().minusSeconds(300);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        Instant revokedAt = Instant.now();
        jdbc.update("""
                INSERT INTO center_machine_session
                    (center_agent_id, machine_agent_id, authenticated_at, expires_at, revoked_at, revoke_reason)
                VALUES (?, ?, ?, ?, ?, 'PASSWORD_RESET')
                """, accountId, machineId, java.sql.Timestamp.from(authenticatedAt),
                java.sql.Timestamp.from(expiresAt), java.sql.Timestamp.from(revokedAt));

        mockMvc.perform(get("/api/admin/center/accounts/" + accountId + "/sessions")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].machineId").value(machineId))
                .andExpect(jsonPath("$[0].machineCode").value(MACHINE_CODE))
                .andExpect(jsonPath("$[0].sessionStatus").value("REVOKED"))
                .andExpect(jsonPath("$[0].authenticatedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$[0].revokedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].revokeReason").value("PASSWORD_RESET"));
    }

    @Test
    void mapsInvalidPasswordAndRequiresCenterManagePermission() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long groupId = createGroup();

        mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"username\":\"" + ACCOUNT_PREFIX
                                + "BAD\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_ACCOUNT_PASSWORD_INVALID"));

        long plainUserId = userRepository.insert(PLAIN_USER, "中心普通账号",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(plainUserId, "USER");
        String plainToken = login(PLAIN_USER, PASSWORD);
        mockMvc.perform(get("/api/admin/center/accounts").header("Authorization", bearer(plainToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));

        mockMvc.perform(post("/api/center/orders").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));

        mockMvc.perform(post("/api/admin/center/agents").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
    }

    private long createGroup() {
        jdbc.update("INSERT INTO agent_group (group_code, display_name, status, created_by) "
                        + "VALUES (?, ?, 'ACTIVE', ?)", GROUP_CODE, "中心账号测试组", adminId);
        return jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, GROUP_CODE);
    }

    private long createCenterAccount(String token, String suffix, long groupId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"username\":\"" + ACCOUNT_PREFIX
                                + suffix + "\",\"password\":\"InitialPass123\",\"initialQuota\":250}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.account.accountId")).longValue();
    }

    private long insertPlayer(long machineId) {
        jdbc.update("""
                INSERT INTO demo_user_account (user_code, member_code, display_name, balance, agent_id)
                VALUES (?, ?, '中心删除测试玩家', 100.00, ?)
                """, PLAYER_CODE, "CD-" + PLAYER_CODE, machineId);
        return jdbc.queryForObject("SELECT id FROM demo_user_account WHERE user_code = ?", Long.class, PLAYER_CODE);
    }

    private long insertBet(long playerId, String suffix) {
        jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES ('AU8', ?, ?, ?, '3000001', 1, 'FAN', '1', 10.00, 3.850, 'PENDING')
                """, playerId, "CENTER-DELETE-BET-" + suffix, "CENTER-DELETE-BET-KEY-" + suffix);
        return jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class,
                "CENTER-DELETE-BET-" + suffix);
    }

    private long insertCenterOrder(long accountId, long machineId, long betId, String orderNo, String status) {
        jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, local_bet_id, local_agent_id, game_code,
                     issue_number, play_type, play_text, odds, amount, status, frozen_amount,
                     settle_result, settle_amount, draw_numbers, accepted_at, settled_at,
                     risk_amount, risk_policy_version, machine_funded_amount)
                VALUES (?, ?, ?, ?, 'AU8', '3000001', 'FAN', 'params=1', 3.8500, 10.00, ?,
                        10.00, CASE WHEN ? = 'SETTLED' THEN 'AGENT_LOSE' ELSE NULL END,
                        CASE WHEN ? = 'SETTLED' THEN 10.00 ELSE NULL END,
                        CASE WHEN ? = 'SETTLED' THEN '1,2,3,4,5,6,7,8' ELSE NULL END,
                        CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'SETTLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                        200.00, 1, 100.00)
                """, accountId, orderNo, betId, machineId, status, status, status, status, status);
        return jdbc.queryForObject("SELECT id FROM center_order WHERE center_agent_id = ? AND agent_order_no = ?",
                Long.class, accountId, orderNo);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String latestAudit(long accountId, String pathSuffix) {
        return jdbc.queryForObject("""
                SELECT request_summary FROM sys_operation_log
                 WHERE permission_code = 'CENTER_AGENT_MANAGE' AND resource_id = ?
                   AND request_path LIKE ? ORDER BY id DESC LIMIT 1
                """, String.class, Long.toString(accountId), "%" + pathSuffix);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE ?))", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE ?)", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM center_reconciliation_disposition WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE ?)", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM center_risk_hit WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE ?)", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM report_upstream_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE ?)", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM center_machine_session WHERE machine_agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE user_code = ?)", PLAYER_CODE);
        jdbc.update("DELETE FROM game_bet WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE user_code = ?)", PLAYER_CODE);
        jdbc.update("DELETE FROM demo_user_account WHERE user_code = ?", PLAYER_CODE);
        jdbc.update("DELETE FROM agent_game WHERE agent_id IN (SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN (SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", MACHINE_USER);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", MACHINE_USER);
        jdbc.update("DELETE FROM center_machine_session WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE ?)", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code LIKE ?)", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM center_agent WHERE agent_code LIKE ?", ACCOUNT_PREFIX + "%");
        jdbc.update("DELETE FROM agent_group WHERE group_code = ?", GROUP_CODE);
        jdbc.update("DELETE FROM sys_login_log WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, PLAIN_USER);
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, PLAIN_USER);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, PLAIN_USER);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, PLAIN_USER);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?)", ADMIN, PLAIN_USER);
    }
}
