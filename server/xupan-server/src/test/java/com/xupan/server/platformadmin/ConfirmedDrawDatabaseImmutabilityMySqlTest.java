package com.xupan.server.platformadmin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "xupan.mysql.integration", matches = "true")
class ConfirmedDrawDatabaseImmutabilityMySqlTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void confirmedResultRejectsDirectUpdateAndDeleteButAllowsSettlementMetadata() {
        String issueNumber = "DRAW_LOCK_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (issue_number, status, game_code, phase, number_1, number_2, number_3, number_4,
                     number_5, number_6, number_7, number_8, draw_result_version, draw_confirmed_at)
                VALUES (?, 'CLOSED', 'AU8', 'SETTLED', 1, 2, 3, 4, 5, 6, 7, 18, 1, CURRENT_TIMESTAMP)
                """, issueNumber);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE game_issue SET number_1 = 8 WHERE game_code = 'AU8' AND issue_number = ?
                """, issueNumber))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Confirmed draw result is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM game_issue WHERE game_code = 'AU8' AND issue_number = ?
                """, issueNumber))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Confirmed draw result cannot be deleted");

        jdbcTemplate.update("""
                UPDATE game_issue SET settled_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                 WHERE game_code = 'AU8' AND issue_number = ?
                """, issueNumber);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT number_1 FROM game_issue
                 WHERE game_code = 'AU8' AND issue_number = ? AND draw_confirmed_at IS NOT NULL
                """, Integer.class, issueNumber)).isEqualTo(1);
    }
}
