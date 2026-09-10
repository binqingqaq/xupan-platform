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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
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
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", AUTO_USER);
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", AUTO_USER);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", AUTO_USER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", AUTO_USER);
    }

    @Test
    void advancesFiveMinuteIssueAndCreatesNextIssue() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        automationService.advance(start);
        assertThat(gameRepository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("3000000");
            assertThat(issue.phase()).isEqualTo(自动轮期服务.BETTING);
            assertThat(issue.bettingEndsAt()).isEqualTo(start.plusSeconds(180));
            assertThat(issue.drawEndsAt()).isEqualTo(start.plusSeconds(300));
        });

        automationService.advance(start.plusSeconds(180));
        assertThat(gameRepository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("3000000");
            assertThat(issue.phase()).isEqualTo(自动轮期服务.DRAWING);
            assertThat(automationService.previewNumbers(issue, start.plusSeconds(181))).hasSize(8);
        });

        automationService.advance(start.plusSeconds(300));
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

        automationService.advance(start.plusSeconds(300));

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
