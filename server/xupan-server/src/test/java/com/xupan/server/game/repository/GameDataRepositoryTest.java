package com.xupan.server.game.repository;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.service.SettlementResult;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GameDataRepositoryTest {

    private static final String BALL_RULE_OPERATOR = "game-data-ball-rule-operator";

    @Autowired
    private GameDataRepository repository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM game_ball_rule_change WHERE idempotency_key LIKE 'GAME-DATA-SEVEN-%'");
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
        jdbcTemplate.update("UPDATE game_definition SET next_issue_ball_count=8, ball_indexes='1,2,3,4,5,6,7,8' "
                + "WHERE game_code='AU8'");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", BALL_RULE_OPERATOR);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", BALL_RULE_OPERATOR);
        ensureLegacyDemoAccount();
    }

    @Test
    void issueBallCountSnapshotKeepsLegacyEightAndNewSevenResultsDistinct() {
        long operatorId = userRepository.insert(BALL_RULE_OPERATOR, "球数切换测试操作员",
                passwordPolicy.encode("GameDataBallRule123"), "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");
        repository.recordBallRuleChange("AU8", "399997000", 8, 7,
                "GAME-DATA-SEVEN-001", "测试球数切换", operatorId, Instant.parse("2026-10-31T00:00:00Z"));

        repository.saveIssue("399996999", "CLOSED", List.of(1, 2, 3, 4, 5, 6, 7, 18));
        repository.saveIssue("399997000", "CLOSED", List.of(1, 2, 3, 4, 5, 6, 7));

        assertThat(repository.findIssueByGameCodeAndIssueNumber("AU8", "399996999")).get().satisfies(issue -> {
            assertThat(issue.ballCount()).isEqualTo(8);
            assertThat(issue.numbers()).containsExactly(1, 2, 3, 4, 5, 6, 7, 18);
        });
        assertThat(repository.findIssueByGameCodeAndIssueNumber("AU8", "399997000")).get().satisfies(issue -> {
            assertThat(issue.ballCount()).isEqualTo(7);
            assertThat(issue.numbers()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        });
        assertThat(jdbcTemplate.queryForObject("SELECT number_8 FROM game_issue WHERE game_code='AU8' "
                + "AND issue_number='399997000'", Integer.class)).isNull();
        long accountId = legacyDemoAccountId();
        assertThatThrownBy(() -> repository.saveBetWithOddsSnapshot("AU8", accountId,
                "BET-SEVEN-ISSUE-BALL-8", "SEVEN-ISSUE-BALL-8", "399997000", 8,
                PlayType.FAN, List.of(1), new BigDecimal("10.00"), new BigDecimal("3.850"),
                BigDecimal.ZERO, BigDecimal.ZERO))
                .hasMessageContaining("GAME_BET_BALL_OUT_OF_RANGE");
        assertThatThrownBy(() -> repository.saveBetWithOddsSnapshot("AU8", accountId,
                "BET-MISSING-ISSUE", "MISSING-ISSUE", "399997099", 1,
                PlayType.FAN, List.of(1), new BigDecimal("10.00"), new BigDecimal("3.850"),
                BigDecimal.ZERO, BigDecimal.ZERO))
                .hasMessage("GAME_BET_BALL_OUT_OF_RANGE");
        assertThat(repository.findSettledIssues("AU8", 10)).extracting(GameDataRepository.IssueRecord::ballCount)
                .containsExactly(7, 8);
        assertThat(repository.ballCountForIssue("AU8", "399996998")).isEqualTo(8);
        assertThat(repository.ballCountForIssue("AU8", "399997001")).isEqualTo(7);
    }

    @Test
    void persistsIssueOddsBetAndSettlementOnce() {
        repository.saveIssue("399996001", "OPEN", null);
        repository.saveOdds(PlayType.FAN, new BigDecimal("3.850"));
        long accountId = legacyDemoAccountId();

        long betId = repository.saveBetWithOddsSnapshot(accountId, "BET-TEST-0001", "REPO-REQUEST-001", "399996001", 1,
                PlayType.FAN, List.of(2), new BigDecimal("15.00"), new BigDecimal("3.850"), new BigDecimal("0.1000"), new BigDecimal("0.2000"));

        assertThat(repository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("399996001");
            assertThat(issue.numbers()).hasSize(8).containsOnlyNulls();
        });
        assertThat(repository.findOdds(PlayType.FAN).orElseThrow()).isEqualByComparingTo("3.850");
        assertThat(repository.findBetByIdempotencyKey(accountId, "REPO-REQUEST-001"))
                .get().extracting(GameDataRepository.BetRecord::accountId).isEqualTo(accountId);
        assertThatThrownBy(() -> repository.requireBetRequestMatch(accountId, "REPO-REQUEST-001", "399996001", 1,
                PlayType.FAN, List.of(3), new BigDecimal("15.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WALLET_IDEMPOTENCY_CONFLICT");

        SettlementResult settlement = new SettlementResult(SettlementStatus.WIN,
                new BigDecimal("15.00"), new BigDecimal("3.850"), new BigDecimal("42.75"), "test");
        assertThat(repository.settleBetOnce(betId, settlement)).isTrue();
        assertThat(repository.settleBetOnce(betId, settlement)).isFalse();
    }

    @Test
    void persistsDrawnIssueNumbers() {
        repository.saveIssue("399996002", "OPEN", null);
        repository.saveIssue("399996002", "CLOSED", List.of(1, 2, 3, 4, 5, 6, 7, 8));

        assertThat(repository.findCurrentIssue()).isEmpty();
        Integer firstNumber = jdbcTemplate.queryForObject(
                "SELECT number_1 FROM game_issue WHERE issue_number = '399996002'", Integer.class);
        assertThat(firstNumber).isEqualTo(1);
    }

    @Test
    void doesNotOverwriteConfirmedIssueResult() {
        repository.saveIssue("399996003", "CLOSED", List.of(1, 2, 3, 4, 5, 6, 7, 8));
        jdbcTemplate.update("""
                UPDATE game_issue
                   SET draw_result_version = 1, draw_confirmed_at = CURRENT_TIMESTAMP
                 WHERE game_code = 'AU8' AND issue_number = '399996003'
                """);

        assertThatThrownBy(() -> repository.saveIssue("399996003", "CLOSED",
                List.of(8, 7, 6, 5, 4, 3, 2, 1)))
                .hasMessageContaining("DRAW_RESULT_LOCKED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT number_1 FROM game_issue
                 WHERE game_code = 'AU8' AND issue_number = '399996003'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void readsOnlyCompleteSettledHistoryInSettlementOrder() {
        repository.saveIssue("399996004", "OPEN", null);
        repository.saveIssue("399996004", "CLOSED", List.of(1, 2, 3, 4, 5, 6, 7, 8));
        repository.saveIssue("399996005", "OPEN", null);
        repository.saveIssue("399996005", "CLOSED", List.of(11, 12, 13, 14, 15, 16, 17, 18));

        jdbcTemplate.update("UPDATE game_issue SET settled_at = ? WHERE issue_number = ?",
                java.sql.Timestamp.from(Instant.parse("2026-09-17T11:00:40Z")), "399996004");
        jdbcTemplate.update("UPDATE game_issue SET settled_at = ? WHERE issue_number = ?",
                java.sql.Timestamp.from(Instant.parse("2026-09-17T11:05:40Z")), "399996005");

        assertThat(repository.findSettledIssues(10))
                .extracting(GameDataRepository.IssueRecord::issueNumber)
                .containsExactly("399996005", "399996004");
        assertThat(repository.findSettledIssues(10).get(0).numbers())
                .containsExactly(11, 12, 13, 14, 15, 16, 17, 18);
    }

    @Test
    void startsANewSettledBlockAfterSixtyCompleteIssues() {
        for (int index = 1; index <= 61; index++) {
            String issueNumber = String.valueOf(399_996_100L + index);
            repository.saveIssue(issueNumber, "CLOSED", List.of(1, 2, 3, 4, 5, 6, 7, index % 20 + 1));
            jdbcTemplate.update("UPDATE game_issue SET settled_at = ? WHERE issue_number = ?",
                    java.sql.Timestamp.from(Instant.parse("2026-09-17T11:00:00Z").plusSeconds(index)), issueNumber);
        }

        assertThat(repository.findLatestSettledBlock(60))
                .extracting(GameDataRepository.IssueRecord::issueNumber)
                .containsExactly("399996161");
    }

    @Test
    void savingTheSameBettingIssueIsIdempotent() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");

        repository.saveBettingIssue("399996200", startedAt);
        repository.saveBettingIssue("399996200", startedAt.plusSeconds(30));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_issue WHERE issue_number = '399996200'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT issue_started_at FROM game_issue WHERE issue_number = '399996200'", java.sql.Timestamp.class))
                .isEqualTo(java.sql.Timestamp.from(startedAt));
    }

    private void ensureLegacyDemoAccount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE user_code = 'DEMO-USER'", Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO demo_user_account (user_code, member_code, display_name, balance) VALUES (?, ?, ?, ?)",
                    "DEMO-USER", "v1", "演示用户", new BigDecimal("1000.00"));
        }
    }

    private long legacyDemoAccountId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE user_code = 'DEMO-USER'", Long.class);
    }
}
