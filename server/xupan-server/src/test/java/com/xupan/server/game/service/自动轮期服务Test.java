package com.xupan.server.game.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.game.repository.GameDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "xupan.automation.enabled=false",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class 自动轮期服务Test {

    private static final String AUTO_USER = "auto-wallet-test-user";

    @Autowired
    private 自动轮期服务 automationService;

    @Autowired
    private GameDataRepository gameRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VirtualWalletService walletService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
        jdbcTemplate.update("DELETE FROM game_route_odds WHERE game_id IN (SELECT id FROM game_definition WHERE game_code LIKE 'MULTI_%')");
        jdbcTemplate.update("DELETE FROM game_definition WHERE game_code LIKE 'MULTI_%'");
        jdbcTemplate.update("UPDATE game_definition SET next_issue_ball_count=7, ball_indexes='1,2,3,4,5,6,7' "
                + "WHERE game_code='AU8'");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", AUTO_USER);
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", AUTO_USER);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", AUTO_USER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", AUTO_USER);
    }

    @Test
    void scheduledAdvanceCreatesIssueForEveryActiveGame() {
        jdbcTemplate.update("""
                INSERT INTO game_definition
                    (id, game_code, display_name, ball_indexes, draw_source_url, sort_order,
                     algorithm, play_prefix, switch_enabled, special_enabled, special_model,
                     keyboard_enabled, status, version, created_at, updated_at)
                VALUES (9001, 'MULTI_AUTO', '自动多彩种', '1,2,3,4,5,6,7,8', NULL, 99,
                        'SUM', NULL, FALSE, TRUE, 'MODEL_ONE', TRUE, 'ACTIVE', 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        automationService.advanceAllActive(Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(gameRepository.findCurrentIssue(GameDataRepository.DEFAULT_GAME_CODE)).isPresent();
        assertThat(gameRepository.findCurrentIssue("MULTI_AUTO")).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("MULTI_AUTO-3000000");
            assertThat(issue.phase()).isEqualTo(自动轮期服务.BETTING);
        });
    }

    @Test
    void advancesFourMinuteIssueAndCreatesNextIssue() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        automationService.advance(start);
        assertThat(gameRepository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("3000000");
            assertThat(issue.phase()).isEqualTo(自动轮期服务.BETTING);
            assertThat(issue.bettingEndsAt()).isEqualTo(start.plusSeconds(180));
            assertThat(issue.drawEndsAt()).isEqualTo(start.plusSeconds(240));
        });

        automationService.advance(start.plusSeconds(180));
        assertThat(gameRepository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("3000000");
            assertThat(issue.phase()).isEqualTo(自动轮期服务.DRAWING);
            assertThat(automationService.previewNumbers(issue, start.plusSeconds(181)))
                    .hasSize(7).doesNotHaveDuplicates().allMatch(number -> number >= 1 && number <= 40);
        });

        automationService.advance(start.plusSeconds(239));
        assertThat(gameRepository.findCurrentIssue()).get().satisfies(issue ->
                assertThat(issue.phase()).isEqualTo(自动轮期服务.DRAWING));

        automationService.advance(start.plusSeconds(240));
        assertThat(gameRepository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("3000001");
            assertThat(issue.phase()).isEqualTo(自动轮期服务.BETTING);
            assertThat(issue.issueNumber()).isNotEqualTo("3000000");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT phase FROM game_issue WHERE issue_number = '3000000'", String.class))
                .isEqualTo(自动轮期服务.SETTLED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_issue_event WHERE issue_number = '3000000' AND event_type = 'DRAW_RESULT'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void newIssueUsesSevenBallRuleForPreviewFinalResultAndNextIssue() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE game_definition SET next_issue_ball_count=7, ball_indexes='1,2,3,4,5,6,7' "
                + "WHERE game_code='AU8'");

        automationService.advance(start);
        GameDataRepository.IssueRecord first = gameRepository.findCurrentIssue().orElseThrow();
        assertThat(first.issueNumber()).isEqualTo("3000000");
        assertThat(first.ballCount()).isEqualTo(7);
        assertThat(first.numbers()).hasSize(7).containsOnlyNulls();

        automationService.advance(start.plusSeconds(180));
        GameDataRepository.IssueRecord drawing = gameRepository.findCurrentIssue().orElseThrow();
        assertThat(drawing.ballCount()).isEqualTo(7);
        assertThat(automationService.previewNumbers(drawing, start.plusSeconds(181))).hasSize(7);

        automationService.advance(start.plusSeconds(240));
        GameDataRepository.IssueRecord settled = gameRepository.findLatestSettledIssue().orElseThrow();
        assertThat(settled.issueNumber()).isEqualTo("3000000");
        assertThat(settled.ballCount()).isEqualTo(7);
        assertThat(settled.numbers()).hasSize(7).doesNotHaveDuplicates()
                .allSatisfy(number -> assertThat(number).isBetween(1, 40));
        assertThat(jdbcTemplate.queryForObject("SELECT number_8 FROM game_issue WHERE game_code='AU8' "
                + "AND issue_number='3000000'", Integer.class)).isNull();
        assertThat(gameRepository.findCurrentIssue()).get().satisfies(next -> {
            assertThat(next.issueNumber()).isEqualTo("3000001");
            assertThat(next.ballCount()).isEqualTo(7);
        });
    }

    @Test
    void finalNumberGeneratorReturnsSevenUniqueNumbersFromForty() {
        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(automationService.generateFinalNumbers())
                    .hasSize(7).doesNotHaveDuplicates().allMatch(number -> number >= 1 && number <= 40);
        }
    }

    @Test
    void finalizesPresetBallsAndRandomizesOnlyEmptyBalls() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE game_definition SET next_issue_ball_count=7, ball_indexes='1,2,3,4,5,6,7' "
                + "WHERE game_code='AU8'");

        automationService.advance(start);
        automationService.advance(start.plusSeconds(180));
        jdbcTemplate.update("UPDATE game_issue SET number_2=8, number_7=19 "
                + "WHERE game_code='AU8' AND issue_number='3000000' AND phase='DRAWING' "
                + "AND draw_confirmed_at IS NULL");

        automationService.advance(start.plusSeconds(240));

        GameDataRepository.IssueRecord settled = gameRepository.findLatestSettledIssue().orElseThrow();
        assertThat(settled.numbers()).hasSize(7).doesNotHaveDuplicates()
                .allSatisfy(number -> assertThat(number).isBetween(1, 40));
        assertThat(settled.numbers().get(1)).isEqualTo(8);
        assertThat(settled.numbers().get(6)).isEqualTo(19);
        assertThat(jdbcTemplate.queryForObject("SELECT phase FROM game_issue WHERE game_code='AU8' "
                + "AND issue_number='3000000'", String.class)).isEqualTo(自动轮期服务.SETTLED);
    }

    @Test
    void finalizesBetIntoItsOwnerWalletWithoutAnOnlineSession() {
        long userId = userRepository.insert(AUTO_USER, "自动结算用户", "test-password-hash", "ACTIVE");
        long accountId = walletService.ensureWalletForUser(userId, "自动结算用户");
        walletService.grant(userId, userId, new java.math.BigDecimal("100.00"),
                "自动结算测试初始化", "AUTO-WALLET-GRANT");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        automationService.advance(start);
        jdbcTemplate.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot)
                VALUES (?, 'AUTO-WALLET-BET', 'AUTO-WALLET-REQUEST', '3000000', 1,
                        'FAN', '2', 10.00, 3.850)
                """, accountId);
        long betId = jdbcTemplate.queryForObject(
                "SELECT id FROM game_bet WHERE bet_code = 'AUTO-WALLET-BET'", Long.class);
        walletService.debitForBet(userId, betId, "AUTO-WALLET-BET", "3000000",
                new java.math.BigDecimal("10.00"));

        automationService.advance(start.plusSeconds(240));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT phase FROM game_issue WHERE issue_number = '3000000'", String.class))
                .isEqualTo(自动轮期服务.SETTLED);
        String settlementStatus = jdbcTemplate.queryForObject(
                "SELECT settlement_status FROM game_bet WHERE id = ?", String.class, betId);
        assertThat(settlementStatus).isNotEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE user_id = ? AND operation_type = 'SETTLEMENT_CREDIT'
                   AND related_bet_id = ?
                """, Integer.class, accountId, betId))
                .isIn(0, 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }
}
