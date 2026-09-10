package com.xupan.server.game.service;

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

    @Autowired
    private 自动轮期服务 automationService;

    @Autowired
    private GameDataRepository gameRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
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
}
