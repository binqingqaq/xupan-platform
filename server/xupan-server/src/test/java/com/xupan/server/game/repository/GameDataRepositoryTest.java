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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Test
    void persistsIssueOddsBetAndSettlementOnce() {
        repository.saveIssue("TEST-0001", "OPEN", null);
        repository.saveOdds(PlayType.FAN, new BigDecimal("3.850"));

        long betId = repository.saveBetWithOddsSnapshot("BET-TEST-0001", "TEST-0001", 8,
                PlayType.FAN, List.of(2), new BigDecimal("15.00"), new BigDecimal("3.850"));

        assertThat(repository.findCurrentIssue()).get().satisfies(issue -> {
            assertThat(issue.issueNumber()).isEqualTo("TEST-0001");
            assertThat(issue.numbers()).hasSize(8).containsOnlyNulls();
        });
        assertThat(repository.findOdds(PlayType.FAN).orElseThrow()).isEqualByComparingTo("3.850");

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
}
