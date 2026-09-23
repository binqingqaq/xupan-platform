package com.xupan.server.robot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RobotMigrationTest {

    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesRobotMigrationsAndSeedsDefaultConfiguration() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");
        assertThat(tableExists("CHAT_ROBOT")).isTrue();
        assertThat(tableExists("CHAT_ROBOT_TEMPLATE")).isTrue();
        assertThat(tableExists("CHAT_ROBOT_DISPATCH")).isTrue();
        assertThat(tableExists("CHAT_ROBOT_DRAW_COMPONENT")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_robot WHERE robot_code = 'issue-helper'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_robot_template t JOIN chat_robot r ON r.id = t.robot_id "
                        + "WHERE r.robot_code = 'issue-helper' AND t.enabled = TRUE", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_robot_draw_component c JOIN chat_robot r ON r.id = c.robot_id "
                        + "WHERE r.robot_code = 'issue-helper'", Integer.class)).isEqualTo(3);
        assertThat(constraintExists("CHAT_ROBOT_DISPATCH", "UK_CHAT_ROBOT_DISPATCH_EVENT")).isTrue();
        assertThat(constraintExists("CHAT_ROBOT_DISPATCH", "FK_CHAT_ROBOT_DISPATCH_EVENT")).isTrue();
    }

    private boolean tableExists(String tableName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                 WHERE UPPER(TABLE_NAME) = ?
                """, Integer.class, tableName) == 1;
    }

    private boolean constraintExists(String tableName, String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                 WHERE UPPER(TABLE_NAME) = ? AND UPPER(CONSTRAINT_NAME) = ?
                """, Integer.class, tableName, constraintName) == 1;
    }
}
