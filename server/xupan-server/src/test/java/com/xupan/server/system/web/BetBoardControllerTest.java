package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.VirtualWalletService;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class BetBoardControllerTest {

    private static final String ADMIN = "bet-board-admin";
    private static final String MACHINE_USER = "bet-board-machine";
    private static final String MACHINE_CODE = "BETBOARD_MACHINE";
    private static final String NORMAL = "bet-board-normal";
    private static final String BOT = "bet-board-bot";
    private static final String PASSWORD = "BetBoardPassword123";
    private static final String CURRENT_ISSUE = "9000000";

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

    @Autowired
    private GameDataRepository gameDataRepository;

    @Autowired
    private AgentAdminService agentAdminService;

    private long normalAccountId;
    private long botAccountId;
    private long machineId;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "下注榜管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        // 下注榜属于机器账号的代理后台（BY220 robot.php），因此夹具玩家必须归属一台测试机器
        machineId = agentAdminService.createAgent(
                MACHINE_CODE, MACHINE_USER, "下注榜机器", PASSWORD, null, adminId).id();

        long normalId = userRepository.insert(NORMAL, "普通下注玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(normalId, "USER");
        normalAccountId = walletService.ensureWalletForUser(normalId, "普通下注玩家");

        long botId = userRepository.insert(BOT, "托下注玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(botId, "USER");
        botAccountId = walletService.ensureWalletForUser(botId, "托下注玩家");
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE id=?", botAccountId);
        jdbc.update("UPDATE demo_user_account SET agent_id=? WHERE id IN (?, ?)",
                machineId, normalAccountId, botAccountId);

        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO game_issue
                    (issue_number, status, phase, opened_at, issue_started_at, betting_ends_at, draw_ends_at)
                VALUES (?, 'OPEN', 'BETTING', ?, ?, ?, ?)
                """, CURRENT_ISSUE, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(180)), Timestamp.from(now.plusSeconds(300)));

        insertBet(normalAccountId, "BB-NORMAL-1", "NONE", "2,3,1", "116.00", "PENDING");
        insertBet(normalAccountId, "BB-NORMAL-CANCELED", "FAN", "4", "30.00", "CANCELED");
        insertBet(botAccountId, "BB-BOT-1", "FAN", "1", "100.00", "PENDING");
        insertBet(botAccountId, "BB-BOT-2", "SPECIAL", "19", "127.00", "PENDING");
        insertBet(botAccountId, "BB-BOT-3", "ANGLE", "1,2", "80.00", "PENDING");

        for (int index = 0; index < 20; index++) {
            String issue = String.format("85%05d", index + 1);
            gameDataRepository.saveIssue(issue, "CLOSED", List.of(
                    1 + (index % 4), 2 + (index % 4), 3 + (index % 4), 4 + (index % 4),
                    5 + (index % 4), 6 + (index % 4), 7 + (index % 4), 8 + (index % 4)));
        }
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void returnsCurrentBetBoardAndLatestEighteenDraws() throws Exception {
        String token = login(MACHINE_USER, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/bet-board")
                        .param("kind", "BOT")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueNumber").value(CURRENT_ISSUE))
                .andExpect(jsonPath("$.normalCount").value(1))
                .andExpect(jsonPath("$.botCount").value(3))
                .andExpect(jsonPath("$.normalStake").value(116.00))
                .andExpect(jsonPath("$.botStake").value(307.00))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].displayName").value("托下注玩家"))
                .andExpect(jsonPath("$.items[0].betText").value("12角/80"))
                .andExpect(jsonPath("$.history", hasSize(18)))
                .andExpect(jsonPath("$.history[0].balls", hasSize(8)))
                .andExpect(jsonPath("$.history[0].balls[0].fan").isNumber());

        mockMvc.perform(get("/api/admin/player-desk/bet-board")
                        .param("kind", "NORMAL")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].betText").value("23无1/116"));
    }

    /**
     * 下注榜每条注单都必须带上报状态（BY220 下注榜的 {@code flyers_status}）。
     *
     * 代理是在下注榜里点这个状态标记来重新飞单的
     * （{@code application/rbt/view/index/admin_list.html:1975-1992}），所以它必须随下注榜下发；
     * 否则代理后台就没有 BY220 的重报入口。
     */
    @Test
    void returnsReportStatusOnEveryBetSoConsoleCanResubmit() throws Exception {
        // 注单必须挂在本机器的夹具玩家上：机器账号的数据范围是 MACHINE，
        // 否则下注榜（按机器范围过滤）看不到这些注单。
        long accountId = normalAccountId;
        insertBetWithReportStatus(accountId, "BB-REPORT-REJECTED", "FAN", "1", "10.00", "PENDING", "REJECTED");
        insertBetWithReportStatus(accountId, "BB-REPORT-ACCEPTED", "FAN", "2", "10.00", "PENDING", "ACCEPTED");

        String token = login(MACHINE_USER, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/bet-board")
                        .param("kind", "NORMAL")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.reportStatus == 'REJECTED')]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.reportStatus == 'ACCEPTED')]", hasSize(1)));
    }

    @Test
    void rejectsOrdinaryPlayers() throws Exception {
        String token = login(NORMAL, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/bet-board")
                        .param("kind", "NORMAL")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    private void insertBet(long accountId, String code, String playType, String parameters,
                           String stake, String status) {
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, 1.950, ?)
                """, accountId, code, code, CURRENT_ISSUE, playType, parameters,
                new BigDecimal(stake), status);
    }

    /**
     * 造一笔带指定上报状态的注单。
     *
     * <p>V58 起上报状态不是 {@code game_bet} 上的列，而是"该注单最新一行上报订单"的派生值，
     * 因此这里追加一行上报订单。</p>
     */
    private void insertBetWithReportStatus(long accountId, String code, String playType, String parameters,
                                           String stake, String status, String reportStatus) {
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, 1.950, ?)
                """, accountId, code, code, CURRENT_ISSUE, playType, parameters,
                new BigDecimal(stake), status);
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, attempt_no, status, idempotency_key, sent_at, resolved_at)
                SELECT b.id, a.agent_id, 1, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.bet_code = ?
                """, reportStatus, "SEED-" + code, code);
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
        jdbc.update("DELETE FROM game_bet WHERE issue_number = ? OR bet_code LIKE 'BB-%'", CURRENT_ISSUE);
        jdbc.update("DELETE FROM game_issue WHERE issue_number = ? OR issue_number LIKE '85%'", CURRENT_ISSUE);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?))", NORMAL, BOT, ADMIN, MACHINE_USER);
        // demo_user_account.agent_id → agent 外键：账号删完才能删机器
        jdbc.update("DELETE FROM agent_game WHERE agent_id IN (SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN (SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?))", NORMAL, BOT, ADMIN, MACHINE_USER);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?, ?, ?)", NORMAL, BOT, ADMIN, MACHINE_USER);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
