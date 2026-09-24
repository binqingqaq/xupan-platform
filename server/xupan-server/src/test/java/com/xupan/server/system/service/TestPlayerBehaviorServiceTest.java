package com.xupan.server.system.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.BetTextParser;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.playerauth.service.PlayerLinkAuthenticationService;
import com.xupan.server.system.repository.PlayerDeskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "xupan.test-player.behavior-enabled=false"})
class TestPlayerBehaviorServiceTest {

    private static final String ADMIN = "behavior-test-admin";
    private static final String BOT = "behavior-test-bot";
    private static final String ISSUE = "3000000";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordPolicyService passwordPolicy;
    @Autowired
    private VirtualWalletService walletService;
    @Autowired
    private PlayerLinkAuthenticationService playerLinkAuthenticationService;
    @Autowired
    private PlayerDeskRepository playerDeskRepository;
    @Autowired
    private TestPlayerBehaviorService behaviorService;
    @Autowired
    private GameDataRepository gameRepository;

    private long adminId;
    private long botId;
    private long accountId;
    private long linkId;

    @BeforeEach
    void setUp() {
        clean();
        resetBettingConfig();
        adminId = userRepository.insert(ADMIN, "行为管理员", passwordPolicy.encode("BehaviorAdmin123"), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        walletService.ensureWalletForUser(adminId, "行为管理员");
        botId = userRepository.insertTestPlayer(BOT, "行为托", null, passwordPolicy.encode("BehaviorBot123"));
        userRepository.assignRole(botId, "USER");
        accountId = walletService.ensureTestWalletForUser(botId, BOT, "行为托");
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE id=?", accountId);
        jdbc.update("UPDATE sys_user SET auth_mode='BOT_SERVICE' WHERE id=?", botId);
        linkId = playerLinkAuthenticationService.issue(botId, adminId).linkId();
        playerDeskRepository.ensureBehavior(accountId);
        gameRepository.saveBettingIssue(ISSUE, Instant.now());
    }

    @AfterEach
    void tearDown() {
        clean();
        resetBettingConfig();
    }

    @Test
    void plansBetsAcrossBettingWindowWithoutAutoChat() {
        grant("BEHAVIOR-GRANT-PLAN");
        configure("AUTOMATIC", 3, "30-300", "OFF", 100, true, 0);

        List<PlayerDeskRepository.ActionRow> executed = behaviorService.dispatch(botId);

        assertThat(executed).as("首轮不应有到期动作").isEmpty();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND action_type='BET_TEXT'", botId))
                .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND action_type='CHAT_TEXT'", botId))
                .as("自动模式不再生成闲聊动作").isZero();

        List<Timestamp> schedule = jdbc.queryForList(
                "SELECT scheduled_at FROM test_player_action WHERE user_id=? AND action_type='BET_TEXT' ORDER BY action_no",
                Timestamp.class, botId);
        Instant bettingEndsAt = jdbc.queryForObject(
                "SELECT betting_ends_at FROM game_issue WHERE issue_number=?", Timestamp.class, ISSUE).toInstant();
        Instant lowerBound = Instant.now().plusSeconds(1);
        for (Timestamp scheduled : schedule) {
            assertThat(scheduled).isNotNull();
            assertThat(scheduled.toInstant()).isAfter(lowerBound);
            assertThat(scheduled.toInstant()).isBeforeOrEqualTo(bettingEndsAt.minusSeconds(1));
        }
        assertThat(schedule.get(0).toInstant()).isBeforeOrEqualTo(schedule.get(2).toInstant());
    }

    @Test
    void doesNothingWhenLinkInvalid() {
        grant("BEHAVIOR-GRANT-LINK");
        configure("AUTOMATIC", 2, "30-300", "OFF", 100, true, 0);
        playerLinkAuthenticationService.revoke(botId, linkId, adminId);

        assertThat(behaviorService.dispatch(botId)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=?", botId))
                .as("链接失效期间不创建任何动作").isZero();
    }

    @Test
    void skipsPlannedBetsAfterLinkRevoked() {
        grant("BEHAVIOR-GRANT-SKIP");
        configure("AUTOMATIC", 2, "30-300", "OFF", 100, true, 0);
        behaviorService.dispatch(botId);

        playerLinkAuthenticationService.revoke(botId, linkId, adminId);
        behaviorService.dispatch(botId);

        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND status='SKIPPED' "
                + "AND error_code='LINK_INVALID'", botId)).isEqualTo(2);
    }

    @Test
    void reducesStakeWhenBalanceIsLowerThanConfiguredRange() {
        jdbc.update("UPDATE demo_user_account SET balance=7.00 WHERE id=?", accountId);
        configure("AUTOMATIC", 1, "30-300", "OFF", 100, true, 0);

        behaviorService.dispatch(botId, true);

        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND status='SUCCEEDED' "
                + "AND bet_id IS NOT NULL", botId)).isEqualTo(1);
        BigDecimal stake = jdbc.queryForObject("SELECT stake FROM game_bet WHERE user_id=?",
                BigDecimal.class, accountId);
        assertThat(stake).as("余额不足时按可用余额降额下注").isEqualByComparingTo("7.00");
    }

    @Test
    void manualModeDoesNotCreateOrExecuteBets() {
        grant("BEHAVIOR-GRANT-MANUAL");
        configure("MANUAL", 2, "30-300", "OFF", 100, true, 0);

        assertThat(behaviorService.dispatch(botId)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=?", botId)).isZero();
    }

    @Test
    void activityPercentShrinksPlannedSlotCount() {
        assertThat(TestPlayerBehaviorService.slotCount(4, 100, true)).isEqualTo(4);
        assertThat(TestPlayerBehaviorService.slotCount(4, 50, true)).isEqualTo(2);
        assertThat(TestPlayerBehaviorService.slotCount(4, 30, true)).isEqualTo(1);
        assertThat(TestPlayerBehaviorService.slotCount(4, 100, false)).isZero();
        assertThat(TestPlayerBehaviorService.slotCount(0, 100, true)).isZero();
    }

    @Test
    void activityZeroPlansNothing() {
        grant("BEHAVIOR-GRANT-ZERO");
        configure("AUTOMATIC", 3, "30-300", "OFF", 0, true, 0);

        behaviorService.dispatch(botId, true);

        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND action_type='BET_TEXT'", botId))
                .isZero();
    }

    @Test
    void roundTenProducesWholeTensWithoutDecimals() {
        grant("BEHAVIOR-GRANT-ROUND");
        configure("AUTOMATIC", 4, "30-300", "ON", 100, true, 0);

        behaviorService.dispatch(botId);

        List<String> texts = jdbc.queryForList("SELECT source_text FROM test_player_action "
                + "WHERE user_id=? AND action_type='BET_TEXT'", String.class, botId);
        assertThat(texts).hasSize(4);
        for (String text : texts) {
            BetTextParser.ParseResult parsed = BetTextParser.parse(text);
            assertThat(parsed.accepted()).as(text).isTrue();
            BigDecimal stake = parsed.bets().get(0).stake();
            assertThat(stake.remainder(BigDecimal.TEN)).as("整十金额：%s", text).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(text).doesNotContain(".");
        }
    }

    @Test
    void playTypeSelectionRestrictsGeneratedBets() {
        grant("BEHAVIOR-GRANT-PLAY");
        configure("AUTOMATIC", 3, "30-300", "OFF", 100, false, 0, List.of("SPECIAL"));

        behaviorService.dispatch(botId);

        List<String> texts = jdbc.queryForList("SELECT source_text FROM test_player_action "
                + "WHERE user_id=? AND action_type='BET_TEXT'", String.class, botId);
        assertThat(texts).hasSize(3);
        for (String text : texts) {
            BetTextParser.ParseResult parsed = BetTextParser.parse(text);
            assertThat(parsed.accepted()).as(text).isTrue();
            assertThat(parsed.bets().get(0).playType()).as(text).isEqualTo(PlayType.SPECIAL);
        }
    }

    @Test
    void globalBotIssueCapSkipsExtraBets() {
        grant("BEHAVIOR-GRANT-CAP");
        setBotCaps(1, 5000);
        configure("AUTOMATIC", 3, "30-300", "OFF", 100, true, 0);

        behaviorService.dispatch(botId, true);

        List<String> rows = jdbc.queryForList("SELECT status || ':' || COALESCE(error_code, '-') || ':' || source_text "
                + "FROM test_player_action WHERE user_id=? ORDER BY action_no", String.class, botId);
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND status='SUCCEEDED' "
                + "AND bet_id IS NOT NULL", botId)).as("达到全局上限后只保留 1 单：%s", rows).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND status='SKIPPED' "
                + "AND error_code='BOT_ISSUE_CAP'", botId)).isEqualTo(2);
    }

    @Test
    void randomTopUpRequestLandsInApprovalQueue() {
        jdbc.update("UPDATE demo_user_account SET balance=10.00 WHERE id=?", accountId);
        configure("AUTOMATIC", 0, "30-300", "OFF", 100, true, 100);

        behaviorService.dispatch(botId, true);

        assertThat(count("SELECT COUNT(*) FROM player_point_request WHERE user_id=? AND status='PENDING'", botId))
                .as("随机上分申请进入后台审批队列").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND action_type='TOP_UP_REQUEST' "
                + "AND status='SUCCEEDED'", botId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND action_type='CHAT_TEXT'", botId))
                .as("上分申请不在聊天室发消息").isZero();
    }

    private void grant(String key) {
        walletService.grant(adminId, botId, new BigDecimal("1000.00"), "托行为测试充值", key);
    }

    private void configure(String mode, int bets, String rangeCode, String roundTen, int activity,
                           boolean playRandom, int topupProbability) {
        configure(mode, bets, rangeCode, roundTen, activity, playRandom, topupProbability, List.of());
    }

    private void configure(String mode, int bets, String rangeCode, String roundTen, int activity,
                           boolean playRandom, int topupProbability, List<String> playTypes) {
        BigDecimal[] bounds = switch (rangeCode) {
            case "RANDOM" -> new BigDecimal[]{new BigDecimal("30"), new BigDecimal("30000")};
            case "1000-3000" -> new BigDecimal[]{new BigDecimal("1000"), new BigDecimal("3000")};
            default -> new BigDecimal[]{new BigDecimal("30"), new BigDecimal("300")};
        };
        PlayerDeskRepository.Behavior behavior = playerDeskRepository.updateBehavior(botId, mode, bets,
                bounds[0], bounds[1], false, 0, rangeCode, roundTen, activity, playRandom,
                topupProbability, new BigDecimal("100.00"), new BigDecimal("1000.00"));
        playerDeskRepository.replacePlayTypes(behavior.id(), playTypes);
    }

    private void setBotCaps(int bets, int stake) {
        jdbc.update("UPDATE game_betting_config SET bot_issue_total_bets=?, bot_issue_total_stake=? WHERE id=1",
                bets, stake);
    }

    private void resetBettingConfig() {
        jdbc.update("UPDATE game_betting_config SET bot_issue_total_bets=20, bot_issue_total_stake=5000, "
                + "bot_night_activity_override_percent=NULL WHERE id=1");
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private void clean() {
        jdbc.update("DELETE FROM test_player_action WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?))",
                ADMIN, BOT);
        jdbc.update("DELETE FROM player_point_request WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?))",
                ADMIN, BOT);
        jdbc.update("DELETE FROM test_player_behavior_play_type WHERE behavior_id IN "
                + "(SELECT b.id FROM test_player_behavior b JOIN demo_user_account a ON a.id=b.account_id "
                + "JOIN sys_user u ON u.id=a.sys_user_id WHERE u.username IN (?, ?))", ADMIN, BOT);
        jdbc.update("DELETE FROM test_player_behavior WHERE account_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?)))", ADMIN, BOT);
        jdbc.update("DELETE FROM player_access_link WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?))",
                ADMIN, BOT);
        jdbc.update("DELETE FROM chat_outbox WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE sender_id IN (SELECT id FROM sys_user WHERE username IN (?, ?)))",
                ADMIN, BOT);
        jdbc.update("DELETE FROM chat_message WHERE sender_id IN (SELECT id FROM sys_user WHERE username IN (?, ?))",
                ADMIN, BOT);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?)))", ADMIN, BOT);
        jdbc.update("DELETE FROM game_bet WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?)))", ADMIN, BOT);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, BOT);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?))",
                ADMIN, BOT);
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", ADMIN, BOT);
        jdbc.update("DELETE FROM sys_login_log WHERE username_snapshot IN (?, ?)", ADMIN, BOT);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?))",
                ADMIN, BOT);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?)", ADMIN, BOT);
        jdbc.update("DELETE FROM game_issue_event");
        jdbc.update("DELETE FROM game_issue");
    }
}
