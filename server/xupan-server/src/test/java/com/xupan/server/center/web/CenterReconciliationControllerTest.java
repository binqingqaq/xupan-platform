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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两侧对账（阶段 B 第五步）。
 *
 * <p>对账的价值在于**能发现差异**，所以这里既验证"对平"也验证"确实报得出差异"，
 * 否则一个永远返回 balanced=true 的对账等于没有。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CenterReconciliationControllerTest {

    private static final String ADMIN = "center-recon-admin";
    private static final String PASSWORD = "CenterReconPassword123";
    private static final String CENTER_CODE = "RECON_CENTER_A";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private String adminToken;
    private long centerAgentId;
    private long agentId;
    private long accountId;
    private long betId;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        long adminId = userRepository.insert(ADMIN, "对账管理员",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        adminToken = login(ADMIN, PASSWORD);

        // 中心代理
        jdbc.update("""
                INSERT INTO center_agent (agent_code, agent_name, upstream_account, token_hash, token_prefix,
                                          status, credit_limit, available_balance, created_by)
                VALUES (?, '对账中心代理', 'recon_up_a', REPEAT('b', 64), 'ctr_recon', 'ACTIVE',
                        10000.00, 10000.00, ?)
                """, CENTER_CODE, adminId);
        centerAgentId = jdbc.queryForObject(
                "SELECT id FROM center_agent WHERE agent_code = ?", Long.class, CENTER_CODE);

        // 本地机器 + 账号 + 注单
        long accountUserId = userRepository.insert("recon-machine-account", "对账机器账号",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, status, system_owned, board_open, report_type,
                                   report_auto, report_online, center_agent_id, account_user_id, created_by)
                VALUES ('RECON_MACHINE_A', '对账机器', 'ACTIVE', FALSE, TRUE, 'CENTER', TRUE, TRUE, ?, ?, ?)
                """, centerAgentId, accountUserId, adminId);
        agentId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = 'RECON_MACHINE_A'", Long.class);

        long playerUserId = userRepository.insert("recon-player", "对账玩家",
                new BCryptPasswordEncoder().encode(PASSWORD), "ACTIVE");
        jdbc.update("""
                INSERT INTO demo_user_account (sys_user_id, user_code, member_code, display_name, player_kind,
                                               status, agent_id, balance)
                VALUES (?, 'RECON-PLAYER-1', 'RECON001', '对账玩家', 'NORMAL', 'ACTIVE', ?, 1000.00)
                """, playerUserId, agentId);
        accountId = jdbc.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, playerUserId);

        jdbc.update("""
                INSERT INTO game_bet (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                                      play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES (?, 'RECON-BET-1', 'RECON-BET-1', '39999801', 1, 'FAN', '1', 100.00, 3.850, 'PENDING')
                """, accountId);
        betId = jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = 'RECON-BET-1'", Long.class);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /** 只有本地上报记录、中心没有对应订单 → 报 MISSING_IN_CENTER。 */
    @Test
    void reportsMissingInCenter() throws Exception {
        insertLocalAttempt("ACCEPTED");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.missingInCenterCount").value(1))
                .andExpect(jsonPath("$.differences[0].type").value("MISSING_IN_CENTER"))
                .andExpect(jsonPath("$.differences[0].betId").value((int) betId));
    }

    /** 两侧都有且一致 → 对平。 */
    @Test
    void balancedWhenBothSidesAgree() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertCenterOrder("ACCEPTED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.differenceCount").value(0));
    }

    /** 中心受理但本地记失败 → 状态语义不一致。 */
    @Test
    void reportsStatusMismatch() throws Exception {
        insertLocalAttempt("REJECTED");
        insertCenterOrder("ACCEPTED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.statusMismatchCount").value(1))
                .andExpect(jsonPath("$.differences[0].type").value("STATUS_MISMATCH"))
                .andExpect(jsonPath("$.differences[0].localStatus").value("REJECTED"))
                .andExpect(jsonPath("$.differences[0].centerStatus").value("ACCEPTED"));
    }

    /** 金额不一致是必须逐笔查的一类差异。 */
    @Test
    void reportsAmountMismatch() throws Exception {
        insertLocalAttempt("ACCEPTED");
        insertCenterOrder("ACCEPTED", "90.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.amountMismatchCount").value(1))
                .andExpect(jsonPath("$.differences[0].localAmount").value(100.0))
                .andExpect(jsonPath("$.differences[0].centerAmount").value(90.0));
    }

    /** 中心有订单但本地无上报记录 → 脏数据。 */
    @Test
    void reportsMissingInLocal() throws Exception {
        insertCenterOrder("ACCEPTED", "100.00");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.missingInLocalCount").value(1));
    }

    /** 未解决项（本地 PENDING/TIMEOUT）会被计数，便于盯遗留。 */
    @Test
    void countsUnresolvedItems() throws Exception {
        insertLocalAttempt("TIMEOUT");

        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("machineId", String.valueOf(agentId))
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.localUnresolved").value(1))
                .andExpect(jsonPath("$.counts.localTotal").value(1));
    }

    /** 账本自检：中心三层与本地机器都一致时，两个不一致计数都为 0。 */
    @Test
    void reportsBothLedgersSelfConsistent() throws Exception {
        jdbc.update("UPDATE agent SET score = 500.00 WHERE id = ?", agentId);
        jdbc.update("""
                INSERT INTO agent_ledger (agent_id, entry_type, direction, amount, balance_before, balance_after,
                                          idempotency_key)
                VALUES (?, 'OPENING_BALANCE', 'IN', 500.00, 0.00, 500.00, 'recon-opening-1')
                """, agentId);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inconsistentCenterBuckets").value(0))
                .andExpect(jsonPath("$.inconsistentAgentBuckets").value(0));
    }

    /** 账本被破坏时对账必须报出来（否则自检形同虚设）。 */
    @Test
    void detectsBrokenAgentLedger() throws Exception {
        jdbc.update("UPDATE agent SET score = 500.00 WHERE id = ?", agentId);
        jdbc.update("""
                INSERT INTO agent_ledger (agent_id, entry_type, direction, amount, balance_before, balance_after,
                                          idempotency_key)
                VALUES (?, 'OPENING_BALANCE', 'IN', 500.00, 0.00, 500.00, 'recon-opening-2')
                """, agentId);
        // 人为把积分改掉，制造"流水与余额不一致"
        jdbc.update("UPDATE agent SET score = 999.00 WHERE id = ?", agentId);

        mockMvc.perform(get("/api/admin/center/agents/reconciliation")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inconsistentAgentBuckets").value(1));
    }

    @Test
    void validatesLimitAndPermission() throws Exception {
        mockMvc.perform(get("/api/admin/center/agents/reconciliation").param("limit", "1000")
                        .header("Authorization", bearerOf(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CENTER_RECONCILIATION_QUERY_INVALID"));

        mockMvc.perform(get("/api/admin/center/agents/reconciliation"))
                .andExpect(status().isUnauthorized());
    }

    private void insertLocalAttempt(String status) {
        jdbc.update("""
                INSERT INTO report_upstream_order (bet_id, agent_id, attempt_no, status, report_type,
                                                   idempotency_key, sent_at, resolved_at)
                VALUES (?, ?, 1, ?, 'CENTER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, betId, agentId, status, "RECON-ATTEMPT-" + status);
    }

    private void insertCenterOrder(String status, String amount) {
        BigDecimal stake = new BigDecimal(amount);
        jdbc.update("""
                INSERT INTO center_order (center_agent_id, agent_order_no, local_bet_id, local_agent_id,
                                          game_code, issue_number, play_type, play_text, odds, amount,
                                          status, frozen_amount, accepted_at)
                VALUES (?, ?, ?, ?, 'K28', '39999801', 'FAN', 'params=1', 3.8500, ?, ?, ?, CURRENT_TIMESTAMP)
                """, centerAgentId, "BET-" + betId, betId, agentId, stake, status,
                "ACCEPTED".equals(status) ? stake : BigDecimal.ZERO.setScale(2));
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
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_CODE);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_CODE);
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = 'RECON_MACHINE_A')");
        jdbc.update("DELETE FROM game_bet WHERE bet_code = 'RECON-BET-1'");
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = 'RECON_MACHINE_A')");
        jdbc.update("UPDATE agent SET report_type = NULL, center_agent_id = NULL "
                + "WHERE agent_code = 'RECON_MACHINE_A'");
        jdbc.update("DELETE FROM demo_user_account WHERE member_code = 'RECON001'");
        jdbc.update("DELETE FROM agent WHERE agent_code = 'RECON_MACHINE_A'");
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_CODE);
        for (String username : new String[] {ADMIN, "recon-machine-account", "recon-player"}) {
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", username);
            jdbc.update("DELETE FROM sys_user WHERE username = ?", username);
        }
    }
}
