package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
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

    private long normalAccountId;
    private long botAccountId;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "下注榜管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        long normalId = userRepository.insert(NORMAL, "普通下注玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(normalId, "USER");
        normalAccountId = walletService.ensureWalletForUser(normalId, "普通下注玩家");

        long botId = userRepository.insert(BOT, "托下注玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(botId, "USER");
        botAccountId = walletService.ensureWalletForUser(botId, "托下注玩家");
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE id=?", botAccountId);

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
        String token = login(ADMIN, PASSWORD);

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
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?, ?)", NORMAL, BOT, ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
