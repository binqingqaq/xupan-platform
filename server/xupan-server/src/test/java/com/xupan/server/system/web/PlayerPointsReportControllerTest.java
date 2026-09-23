package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerPointsReportControllerTest {

    private static final String ADMIN = "points-report-admin";
    private static final String NORMAL = "points-report-normal";
    private static final String BOT = "points-report-bot";
    private static final String PASSWORD = "PointsReportPassword123";
    private static final Instant IN_DAY = Instant.parse("2026-09-21T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private VirtualWalletService walletService;

    private long normalId;
    private long botId;
    private long normalAccountId;
    private long botAccountId;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "报表管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        normalId = userRepository.insert(NORMAL, "普通报表玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(normalId, "USER");
        normalAccountId = walletService.ensureWalletForUser(normalId, "普通报表玩家");

        botId = userRepository.insert(BOT, "托报表玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(botId, "USER");
        botAccountId = walletService.ensureWalletForUser(botId, "托报表玩家");
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE id=?", botAccountId);

        insertLedger(normalAccountId, "ADMIN_GRANT", "100.00", "0.00", "100.00", "上分");
        insertLedger(normalAccountId, "ADMIN_ADJUST", "-25.00", "100.00", "75.00", "下分");
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, issue_number, ball_number, play_type, parameters_text,
                     stake, odds_snapshot, settlement_status, net_profit, explanation, created_at, settled_at)
                VALUES (?, 'REPORT-BET-1', '20260921', 1, 'FAN', '1', 30.00, 1.950,
                        'WIN', 12.00, '测试结算', ?, ?)
                """, normalAccountId, timestamp(IN_DAY), timestamp(IN_DAY));

        jdbc.update("""
                INSERT INTO test_player_behavior(account_id, run_mode, enabled, bets_per_issue, stake_min, stake_max)
                VALUES (?, 'MANUAL', FALSE, 0, 100.00, 100.00)
                """, botAccountId);
        long actualBehaviorId = jdbc.queryForObject("SELECT id FROM test_player_behavior WHERE account_id=?",
                Long.class, botAccountId);
        jdbc.update("""
                INSERT INTO test_player_action
                    (behavior_id, user_id, account_id, issue_number, action_no, action_type,
                     idempotency_key, source_text, status, created_at, updated_at)
                VALUES (?, ?, ?, '20260921', 1, 'BET_TEXT', 'REPORT-ACTION-1', '第1球/1番30',
                        'SUCCEEDED', ?, ?)
                """, actualBehaviorId, botId, botAccountId, timestamp(IN_DAY), timestamp(IN_DAY));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void reportQueriesNormalAndBotByBusinessDate() throws Exception {
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/points-records")
                        .param("kind", "NORMAL").param("date", "2026-09-21")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-09-21"))
                .andExpect(jsonPath("$.fromInclusive").value("2026-09-20T22:00:00Z"))
                .andExpect(jsonPath("$.summary.playerCount").value(1))
                .andExpect(jsonPath("$.summary.turnover").value(30.00))
                .andExpect(jsonPath("$.summary.netProfit").value(12.00))
                .andExpect(jsonPath("$.summary.topUp").value(100.00))
                .andExpect(jsonPath("$.summary.down").value(25.00))
                .andExpect(jsonPath("$.players[0].bets", hasSize(1)))
                .andExpect(jsonPath("$.players[0].pointOperations", hasSize(2)));

        mockMvc.perform(get("/api/admin/player-desk/points-records")
                        .param("kind", "BOT").param("date", "2026-09-21")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerKind").value("BOT"))
                .andExpect(jsonPath("$.summary.playerCount").value(1))
                .andExpect(jsonPath("$.players[0].botActions", hasSize(1)))
                .andExpect(jsonPath("$.players[0].botActions[0].status").value("SUCCEEDED"));
    }

    @Test
    void futureBusinessDateIsRejected() throws Exception {
        String token = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/points-records")
                        .param("kind", "NORMAL").param("date", "2999-01-01")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POINTS_REPORT_DATE_INVALID"));
    }

    private void insertLedger(long accountId, String operation, String amount, String before, String after, String reason) {
        jdbc.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after,
                     operator_user_id, operator_name, idempotency_key, reason, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, '测试管理员', ?, ?, ?)
                """, accountId, operation, new BigDecimal(amount), new BigDecimal(before), new BigDecimal(after),
                "REPORT-" + operation + "-" + accountId + "-" + amount, reason, timestamp(IN_DAY));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void clean() {
        jdbc.update("DELETE FROM auth_ws_ticket");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_login_log");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM test_player_action WHERE account_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM test_player_behavior WHERE account_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM game_bet WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?, ?)", NORMAL, BOT, ADMIN);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
