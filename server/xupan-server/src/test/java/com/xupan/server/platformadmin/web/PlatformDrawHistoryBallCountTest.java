package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.platformadmin.service.PlatformDrawHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PlatformDrawHistoryBallCountTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PlatformDrawHistoryService service;

    @Test
    @Transactional
    void normalHistoryReturnsOnlySevenBallIssuesOnceSevenBallIssuesExist() {
        long operator = users.insert("seven-history-operator", "七球历史测试管理员", "test-hash", "ACTIVE");
        users.assignRole(operator, "ADMIN");
        String legacy = "399988801";
        String seven = "399988802";
        Instant openedAt = Instant.parse("2026-10-31T00:00:00Z");

        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, ball_count,
                     number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                     opened_at, issue_started_at, settled_at)
                VALUES ('AU8', ?, 'CLOSED', 'SETTLED', 8, 1, 2, 3, 4, 5, 6, 7, 8, ?, ?, ?)
                """, legacy, Timestamp.from(openedAt), Timestamp.from(openedAt), Timestamp.from(openedAt));
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, ball_count,
                     number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                     opened_at, issue_started_at, settled_at)
                VALUES ('AU8', ?, 'CLOSED', 'DRAWING', 7, 8, 7, 6, 5, 4, 3, 2, NULL, ?, ?, ?)
                """, seven, Timestamp.from(openedAt.plusSeconds(300)),
                Timestamp.from(openedAt.plusSeconds(300)), Timestamp.from(openedAt.plusSeconds(300)));

        PlatformDrawHistoryService.Page page = service.list("AU8", null, 1, 100, operator);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(row -> {
            assertThat(row.issueNumber()).isEqualTo(seven);
            assertThat(row.ballCount()).isEqualTo(7);
            assertThat(row.balls()).containsExactly(8, 7, 6, 5, 4, 3, 2);
        });
    }
}
