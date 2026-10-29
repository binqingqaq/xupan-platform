package com.xupan.server.system.service;

import com.xupan.server.agent.repository.AgentRepository;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "xupan.test-player.behavior-enabled=false"})
class TestPlayerBehaviorServiceTest {
    private static final String ADMIN = "behavior-test-admin";
    private static final String MACHINE = "behavior-test-machine";
    private static final String BOT = "behavior-test-bot";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private PasswordPolicyService passwordPolicy;
    @Autowired private VirtualWalletService walletService;
    @Autowired private PlayerLinkAuthenticationService playerLinkAuthenticationService;
    @Autowired private PlayerDeskRepository playerDeskRepository;
    @Autowired private TestPlayerBehaviorService behaviorService;
    @Autowired private GameDataRepository gameRepository;

    private long adminId;
    private long machineUserId;
    private long agentId;
    private long botId;
    private long accountId;
    private long linkId;
    private String issue;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "行为管理员", passwordPolicy.encode("BehaviorAdmin123"), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        machineUserId = userRepository.insert(MACHINE, "行为机器", passwordPolicy.encode("BehaviorMachine123"), "ACTIVE");
        userRepository.assignRole(machineUserId, "AGENT");
        agentId = agentRepository.createAgent("behavior-machine-code", "行为机器", null, machineUserId, adminId);
        jdbc.update("UPDATE agent SET manual_betting_open=TRUE,chase_enabled=TRUE,score=5000 WHERE id=?", agentId);

        botId = userRepository.insertTestPlayer(BOT, "行为托", null, passwordPolicy.encode("BehaviorBot123"));
        userRepository.assignRole(botId, "USER");
        accountId = walletService.ensureTestWalletForUser(botId, BOT, "行为托");
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT',agent_id=? WHERE id=?", agentId, accountId);
        jdbc.update("UPDATE sys_user SET auth_mode='BOT_SERVICE' WHERE id=?", botId);
        linkId = playerLinkAuthenticationService.issue(botId, adminId).linkId();
        playerDeskRepository.ensureBehavior(accountId);

        issue = "BOT-BY220-" + System.nanoTime();
        gameRepository.saveBettingIssue(issue, Instant.now());
    }

    @AfterEach
    void tearDown() { clean(); }

    @Test
    void newBotBehaviorDefaultsToAutomaticAndBy220FirstStakeTier() {
        PlayerDeskRepository.Behavior behavior = playerDeskRepository.findBehavior(botId).orElseThrow();
        assertThat(behavior.mode()).isEqualTo("AUTOMATIC");
        assertThat(behavior.enabled()).isTrue();
        assertThat(behavior.stakeRangeCode()).isEqualTo("30-300");
        assertThat(behavior.stakeMin()).isEqualByComparingTo("30");
        assertThat(behavior.stakeMax()).isEqualByComparingTo("300");
        assertThat(behavior.machineChaseEnabled()).isTrue();
    }

    @Test
    void automaticPoolProducesParserAcceptedBetsForAllThirteenLeafOutcomes() {
        Set<String> outcomes = new HashSet<>();
        for (int i = 0; i < 12000; i++) {
            String text = BotBetTextGenerator.by220AutomaticBet(new BigDecimal("100"));
            BetTextParser.ParseResult parsed = BetTextParser.parse(text);
            assertThat(parsed.accepted()).as(text).isTrue();
            assertThat(parsed.bets()).hasSize(1);
            BetTextParser.ParsedBet bet = parsed.bets().get(0);
            if (bet.playType() == PlayType.ODD_EVEN) outcomes.add(bet.parameters().get(0) == 1 ? "单" : "双");
            else if (bet.playType() == PlayType.BIG_SMALL) outcomes.add(bet.parameters().get(0) == 1 ? "大" : "小");
            else outcomes.add(bet.playType().name());
        }
        assertThat(outcomes).contains("FAN", "ANGLE", "ADD", "CAR", "STRICT", "POSITIVE", "TONG",
                "NONE", "单", "双", "大", "小", "SPECIAL");
        assertThat(BotBetTextGenerator.approvedWeights()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("SPECIAL", 216), Map.entry("NONE", 216), Map.entry("CAR", 72),
                Map.entry("STRICT", 72), Map.entry("ANGLE", 36), Map.entry("FAN", 36),
                Map.entry("POSITIVE", 36), Map.entry("ODD", 9), Map.entry("EVEN", 9),
                Map.entry("BIG", 9), Map.entry("SMALL", 9), Map.entry("ADD", 40), Map.entry("TONG", 40)));
        assertThat(BotBetTextGenerator.approvedWeights().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(800);
    }

    @Test
    void automaticBetPlanCreatesAtMostOnePersistedTicketForSingleEligibleBot() {
        walletService.grant(adminId, botId, new BigDecimal("1000"), "测试初始虚拟积分", "BOT-PLAN-GRANT");
        List<PlayerDeskRepository.ActionRow> executed = behaviorService.dispatch(botId);
        assertThat(executed).isEmpty(); // Ticket is scheduled inside the current issue window.
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type='BET_TEXT'", botId, issue))
                .isEqualTo(1);
        String text = jdbc.queryForObject("SELECT source_text FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type='BET_TEXT'", String.class, botId, issue);
        assertThat(BetTextParser.parse(text).accepted()).isTrue();
    }

    @Test
    void manualBotDoesNotReceiveAutomaticBetPlan() {
        playerDeskRepository.updateBehavior(botId, "MANUAL", new BigDecimal("30"), new BigDecimal("300"),
                "30-300", false, false);
        assertThat(behaviorService.dispatch(botId)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND action_type='BET_TEXT'", botId)).isZero();
    }

    @Test
    void automaticTopUpAndDownChangeOnlyBotWalletAndWriteNoApprovalRequest() {
        walletService.grant(adminId, botId, new BigDecimal("10"), "测试低余额", "BOT-LOW-GRANT");
        BigDecimal machineBefore = jdbc.queryForObject("SELECT score FROM agent WHERE id=?", BigDecimal.class, agentId);
        for (int i = 0; i < 300 && balance().compareTo(new BigDecimal("10")) == 0; i++) {
            behaviorService.dispatchPeriodicActivity(botId);
        }
        assertThat(balance()).isGreaterThan(new BigDecimal("10"));
        assertThat(count("SELECT COUNT(*) FROM player_point_request WHERE user_id=? AND status='PENDING'", botId)).isZero();
        assertThat(jdbc.queryForObject("SELECT score FROM agent WHERE id=?", BigDecimal.class, agentId))
                .isEqualByComparingTo(machineBefore);

        walletService.grant(adminId, botId, new BigDecimal("4000"), "测试下分条件", "BOT-DOWN-GRANT");
        for (int i = 0; i < 300 && count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type='AUTO_DOWN' AND status='SUCCEEDED'", botId, issue) == 0; i++) {
            behaviorService.dispatchPeriodicActivity(botId);
        }
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type='AUTO_DOWN' AND status='SUCCEEDED'", botId, issue)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score FROM agent WHERE id=?", BigDecimal.class, agentId))
                .isEqualByComparingTo(machineBefore);
    }

    @Test
    void revokedPlayerLinkStopsBotActions() {
        playerLinkAuthenticationService.revoke(botId, linkId, adminId);
        assertThat(behaviorService.dispatch(botId)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type='BET_TEXT'", botId, issue)).isZero();
    }

    @Test
    void manuallyClosedMachineDoesNotPlanBotBetsButStillAllowsNonBetBotActions() {
        jdbc.update("UPDATE agent SET manual_betting_open=FALSE WHERE id=?", agentId);

        assertThat(behaviorService.dispatch(botId)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type='BET_TEXT'",
                botId, issue)).isZero();

        long behaviorId = jdbc.queryForObject("SELECT id FROM test_player_behavior WHERE account_id=?", Long.class, accountId);
        jdbc.update("""
                INSERT INTO test_player_action(behavior_id,user_id,account_id,issue_number,action_no,action_type,
                                               idempotency_key,source_text,scheduled_at)
                VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                """, behaviorId, botId, accountId, issue, 901, "AUTO_QUERY",
                "BOT-CLOSED-QUERY-" + botId, "查");
        long actionId = jdbc.queryForObject("SELECT id FROM test_player_action WHERE user_id=? AND issue_number=? AND action_no=901",
                Long.class, botId, issue);

        assertThat(behaviorService.execute(actionId).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void pendingBotBetIsSkippedPermanentlyWhenMachineCloses() {
        walletService.grant(adminId, botId, new BigDecimal("1000"), "测试初始虚拟积分", "BOT-CLOSE-GRANT");
        behaviorService.dispatch(botId);
        long actionId = jdbc.queryForObject("SELECT id FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type='BET_TEXT'",
                Long.class, botId, issue);

        jdbc.update("UPDATE agent SET manual_betting_open=FALSE WHERE id=?", agentId);
        PlayerDeskRepository.ActionRow skipped = behaviorService.execute(actionId);
        assertThat(skipped.status()).isEqualTo("SKIPPED");
        assertThat(skipped.errorCode()).isEqualTo("MACHINE_MANUALLY_CLOSED");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id=? AND issue_number=?", accountId, issue)).isZero();

        jdbc.update("UPDATE agent SET manual_betting_open=TRUE WHERE id=?", agentId);
        behaviorService.dispatch(botId, true);
        PlayerDeskRepository.ActionRow afterReopen = jdbc.queryForObject("SELECT * FROM test_player_action WHERE id=?",
                (rs, rowNum) -> new PlayerDeskRepository.ActionRow(rs.getLong("id"), rs.getString("issue_number"),
                        rs.getInt("action_no"), rs.getString("action_type"), rs.getString("source_text"),
                        rs.getString("status"), rs.getInt("attempts"), rs.getString("error_code"),
                        rs.getString("error_message"), (Long) rs.getObject("message_id"), (Long) rs.getObject("bet_id"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), actionId);
        assertThat(afterReopen.status()).isEqualTo("SKIPPED");
        assertThat(afterReopen.errorCode()).isEqualTo("MACHINE_MANUALLY_CLOSED");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id=? AND issue_number=?", accountId, issue)).isZero();
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id=?", BigDecimal.class, accountId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void clean() {
        jdbc.update("DELETE FROM test_player_action WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM player_point_request WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM player_access_link WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM chat_outbox WHERE message_id IN (SELECT id FROM chat_message WHERE agent_id IN (SELECT id FROM agent WHERE agent_code='behavior-machine-code'))");
        jdbc.update("DELETE FROM chat_message WHERE agent_id IN (SELECT id FROM agent WHERE agent_code='behavior-machine-code')");
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN (SELECT id FROM demo_user_account WHERE sys_user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM game_bet WHERE user_id IN (SELECT id FROM demo_user_account WHERE sys_user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM test_player_behavior WHERE account_id IN (SELECT id FROM demo_user_account WHERE sys_user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM agent WHERE agent_code='behavior-machine-code'");
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN (SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM sys_login_log WHERE username_snapshot IN (?, ?, ?)", ADMIN, MACHINE, BOT);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?, ?)", ADMIN, MACHINE, BOT);
        if (issue != null) {
            jdbc.update("DELETE FROM game_issue_event WHERE issue_number=?", issue);
            jdbc.update("DELETE FROM game_issue WHERE issue_number=?", issue);
        }
    }
}
