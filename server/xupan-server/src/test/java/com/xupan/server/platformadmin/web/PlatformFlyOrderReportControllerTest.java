package com.xupan.server.platformadmin.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformFlyOrderReportControllerTest {

    private static final String ADMIN = "platform-fly-report-test-admin";
    private static final String PASSWORD = "AdminPassword123";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordPolicyService passwords;

    @Test
    @Transactional
    void groupsNormalFlyOrdersByBy220StatusAndAmountAndExcludesBotsAndWithdrawnOrders() throws Exception {
        long adminId = users.insert(ADMIN, "飞单报表测试管理员", passwords.encode(PASSWORD), "ACTIVE");
        users.assignRole(adminId, "ADMIN");
        long groupId = insert("""
                INSERT INTO agent_group (group_code, display_name, username, status)
                VALUES ('PLATFORM_FLY_REPORT_GROUP', '飞单报表测试子账号', 'platform_fly_report_sub', 'ACTIVE')
                """);
        long machineId = insert("""
                INSERT INTO agent (agent_code, display_name, group_id, system_owned, status)
                VALUES ('PLATFORM_FLY_REPORT_MACHINE', '飞单报表测试机器', ?, FALSE, 'ACTIVE')
                """, groupId);
        long normalId = player("normal", machineId, "NORMAL", "V-FLY-NORMAL");
        long botId = player("bot", machineId, "BOT", "V-FLY-BOT");
        long unknownId = player("unknown", machineId, "NORMAL", "V-FLY-UNKNOWN");

        long accepted = bet(normalId, machineId, "accepted", "NORMAL", "10.00", "PENDING");
        attempt(accepted, machineId, "ACCEPTED", "ACCEPTED");
        long centerCanceled = bet(normalId, machineId, "center-cancelled", "NORMAL", "12.00", "PENDING");
        attempt(centerCanceled, machineId, "MANUAL", "CENTER_CANCELLED");
        long centerRefunded = bet(normalId, machineId, "center-refunded", "NORMAL", "13.00", "PENDING");
        attempt(centerRefunded, machineId, "REFUNDED", "CENTER_REFUNDED");
        long rejected = bet(normalId, machineId, "rejected", "NORMAL", "20.00", "PENDING");
        attempt(rejected, machineId, "REJECTED", "REJECTED");
        long exhausted = bet(normalId, machineId, "retry-exhausted", "NORMAL", "21.00", "PENDING");
        attempt(exhausted, machineId, "MANUAL", "RETRY_EXHAUSTED");
        bet(normalId, machineId, "unreported", "NORMAL", "30.00", "PENDING");
        long withdrawn = bet(normalId, machineId, "withdrawn", "NORMAL", "50.00", "CANCELED");
        attempt(withdrawn, machineId, "REFUNDED", "REJECTED");
        long bot = bet(botId, machineId, "bot-accepted", "BOT", "60.00", "PENDING");
        attempt(bot, machineId, "ACCEPTED", "ACCEPTED");
        long unknown = bet(unknownId, machineId, "unknown-accepted", null, "70.00", "PENDING");
        jdbc.update("UPDATE game_bet SET user_id = NULL WHERE id = ?", unknown);

        String token = login();
        mockMvc.perform(get("/api/admin/reports/fly-orders")
                        .header("Authorization", bearer(token))
                        .param("day", "2026-10-02").param("subAccountId", Long.toString(groupId))
                        .param("status", "REPORTED").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.cutoffHour").value(6))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.summary.reportedCount").value(3))
                .andExpect(jsonPath("$.summary.reportedAmount").value(35.00))
                .andExpect(jsonPath("$.summary.failedCount").value(2))
                .andExpect(jsonPath("$.summary.failedAmount").value(41.00))
                .andExpect(jsonPath("$.summary.unreportedCount").value(1))
                .andExpect(jsonPath("$.summary.unreportedAmount").value(30.00));

        mockMvc.perform(get("/api/admin/reports/fly-orders")
                        .header("Authorization", bearer(token)).param("day", "2026-10-02")
                        .param("subAccountId", Long.toString(groupId)).param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.summary.reportedAmount").value(35.00));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?", Long.class, withdrawn))
                .isEqualTo(1L);
    }

    @Test
    void rejectsInvalidStatusAndCutoff() throws Exception {
        long adminId = users.insert(ADMIN + "-validation", "飞单报表测试管理员", passwords.encode(PASSWORD), "ACTIVE");
        users.assignRole(adminId, "ADMIN");
        String token = login(ADMIN + "-validation");
        mockMvc.perform(get("/api/admin/reports/fly-orders").header("Authorization", bearer(token))
                        .param("status", "CENTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_STATUS_INVALID"));
        mockMvc.perform(get("/api/admin/reports/fly-orders").header("Authorization", bearer(token))
                        .param("cutoffHour", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_CUTOFF_INVALID"));
    }

    private long player(String suffix, long machineId, String kind, String memberCode) {
        long userId = users.insert("platform-fly-report-player-" + suffix, "飞单测试" + suffix,
                passwords.encode(PASSWORD), "ACTIVE");
        jdbc.update("""
                INSERT INTO demo_user_account
                    (user_code, member_code, display_name, balance, status, sys_user_id, agent_id,
                     identity_type, player_kind)
                VALUES (?, ?, ?, 100.00, 'ACTIVE', ?, ?, 'REAL', ?)
                """, "PLATFORM_FLY_REPORT_PLAYER_" + suffix, memberCode, "飞单测试" + suffix,
                userId, machineId, kind);
        return jdbc.queryForObject("SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, userId);
    }

    private long bet(long accountId, long machineId, String suffix, String kind, String stake, String settlementStatus) {
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, agent_id_snapshot, player_kind_snapshot,
                     player_code_snapshot, player_name_snapshot, issue_number, ball_number, play_type,
                     parameters_text, stake, odds_snapshot, settlement_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'FLY-REPORT-ISSUE', 1, 'FAN', '1', ?, 3.850, ?,
                        '2026-10-02 12:00:00')
                """, accountId, "PLATFORM_FLY_REPORT_BET_" + suffix, "PLATFORM_FLY_REPORT_REQUEST_" + suffix,
                machineId, kind, "V-FLY-" + suffix, "飞单测试" + suffix, new java.math.BigDecimal(stake), settlementStatus);
        return jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class,
                "PLATFORM_FLY_REPORT_BET_" + suffix);
    }

    private void attempt(long betId, long machineId, String status, String responseCode) {
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, attempt_no, status, report_type, idempotency_key, response_code,
                     created_at, updated_at)
                VALUES (?, ?, 1, ?, 'CENTER', ?, ?, '2026-10-02 12:01:00', '2026-10-02 12:01:00')
                """, betId, machineId, status, "PLATFORM_FLY_REPORT_ATTEMPT_" + betId, responseCode);
    }

    private long insert(String sql, Object... args) {
        jdbc.update(sql, args);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String login() throws Exception { return login(ADMIN); }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
