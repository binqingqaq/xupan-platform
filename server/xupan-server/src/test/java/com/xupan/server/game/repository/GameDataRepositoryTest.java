package com.xupan.server.game.repository;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.service.SettlementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class GameDataRepositoryTest {

    @Autowired
    private GameDataRepository repository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
        ensureLegacyDemoAccount();
    }

    @Test
    void persistsIssueOddsBetAndSettlementOnce() {
        repository.saveIssue("TEST-0001", "OPEN", null);
        repository.saveOdds(PlayType.FAN, new BigDecimal("3.850"));
        long accountId = legacyDemoAccountId();

        long betId = repository.saveBetWithOddsSnapshot(accountId, "BET-TEST-0001", "REPO-REQUEST-001", "TEST-0001", 8,
                PlayType.FAN, List.of(2), new BigDecimal("15.00"), new BigDecimal("3.850"));

        assertThat(repository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("TEST-0001");
            assertThat(issue.numbers()).hasSize(8).containsOnlyNulls();
        });
        assertThat(repository.findOdds(PlayType.FAN).orElseThrow()).isEqualByComparingTo("3.850");
        assertThat(repository.findBetByIdempotencyKey(accountId, "REPO-REQUEST-001"))
                .get().extracting(GameDataRepository.BetRecord::accountId).isEqualTo(accountId);
        assertThatThrownBy(() -> repository.requireBetRequestMatch(accountId, "REPO-REQUEST-001", "TEST-0001", 8,
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
        repository.saveIssue("TEST-0002", "OPEN", null);
        repository.saveIssue("TEST-0002", "CLOSED", List.of(1, 2, 3, 4, 5, 6, 7, 8));

        assertThat(repository.findCurrentIssue()).isEmpty();
        Integer firstNumber = jdbcTemplate.queryForObject(
                "SELECT number_1 FROM game_issue WHERE issue_number = 'TEST-0002'", Integer.class);
        assertThat(firstNumber).isEqualTo(1);
    }

    @Test
    void savingTheSameBettingIssueIsIdempotent() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");

        repository.saveBettingIssue("TEST-REPEAT", startedAt);
        repository.saveBettingIssue("TEST-REPEAT", startedAt.plusSeconds(30));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_issue WHERE issue_number = 'TEST-REPEAT'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT issue_started_at FROM game_issue WHERE issue_number = 'TEST-REPEAT'", java.sql.Timestamp.class))
                .isEqualTo(java.sql.Timestamp.from(startedAt));
    }

    private void ensureLegacyDemoAccount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE user_code = 'DEMO-USER'", Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO demo_user_account (user_code, display_name, balance) VALUES (?, ?, ?)",
                    "DEMO-USER", "演示用户", new BigDecimal("1000.00"));
        }
    }

    private long legacyDemoAccountId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE user_code = 'DEMO-USER'", Long.class);
    }
}
