package com.xupan.server.game.repository;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class VirtualWalletMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void v8AddsFormalWalletColumnsConstraintsAndSeedsWithoutRemovingLegacyData() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");

        assertThat(columnCount("demo_user_account", "sys_user_id")).isEqualTo(1);
        assertThat(columnCount("game_bet", "request_idempotency_key")).isEqualTo(1);
        assertThat(columnCount("demo_balance_ledger", "operator_user_id")).isEqualTo(1);
        assertThat(columnCount("demo_balance_ledger", "idempotency_key")).isEqualTo(1);
        assertThat(columnCount("demo_balance_ledger", "related_bet_id")).isEqualTo(1);
        assertThat(columnCount("demo_balance_ledger", "issue_number")).isEqualTo(1);

        assertThat(constraintCount("demo_user_account", "uk_demo_user_account_sys_user")).isEqualTo(1);
        assertThat(constraintCount("demo_user_account", "fk_demo_user_account_sys_user")).isEqualTo(1);
        assertThat(constraintCount("game_bet", "uk_game_bet_user_request_key")).isEqualTo(1);
        assertThat(constraintCount("demo_balance_ledger", "uk_demo_balance_ledger_idempotency")).isEqualTo(1);
        assertThat(constraintCount("demo_balance_ledger", "fk_demo_balance_ledger_operator")).isEqualTo(1);
        assertThat(constraintCount("demo_balance_ledger", "fk_demo_balance_ledger_bet")).isEqualTo(1);
        assertThat(constraintCount("game_bet", "fk_game_bet_demo_user")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT numeric_precision FROM information_schema.columns "
                        + "WHERE UPPER(table_schema) = UPPER(?) "
                        + "AND UPPER(table_name) = 'DEMO_USER_ACCOUNT' AND UPPER(column_name) = 'BALANCE'",
                Integer.class, currentSchema())).isEqualTo(18);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT numeric_scale FROM information_schema.columns "
                        + "WHERE UPPER(table_schema) = UPPER(?) "
                        + "AND UPPER(table_name) = 'DEMO_USER_ACCOUNT' AND UPPER(column_name) = 'BALANCE'",
                Integer.class, currentSchema())).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE user_code = 'DEMO-USER'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_permission WHERE permission_code IN "
                        + "('WALLET_READ', 'WALLET_GRANT', 'WALLET_ADJUST', 'WALLET_LEDGER_READ')",
                Integer.class)).isEqualTo(4);
    }

    @Test
    void rerunningEquivalentWalletSeedsDoesNotDuplicatePermissionsOrMappings() {
        int permissionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_permission WHERE permission_code LIKE 'WALLET_%'", Integer.class);
        int mappingsBefore = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_role_permission rp
                  JOIN sys_permission p ON p.id = rp.permission_id
                 WHERE p.permission_code LIKE 'WALLET_%'
                """, Integer.class);

        jdbcTemplate.update("""
                INSERT INTO sys_permission (permission_code, display_name)
                SELECT 'WALLET_READ', '重复执行测试'
                 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'WALLET_READ')
                """);
        jdbcTemplate.update("""
                INSERT INTO sys_role_permission (role_id, permission_id)
                SELECT r.id, p.id
                  FROM sys_role r CROSS JOIN sys_permission p
                 WHERE r.role_code = 'ADMIN' AND p.permission_code = 'WALLET_READ'
                   AND NOT EXISTS (SELECT 1 FROM sys_role_permission x
                                    WHERE x.role_id = r.id AND x.permission_id = p.id)
                """);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_permission WHERE permission_code LIKE 'WALLET_%'", Integer.class))
                .isEqualTo(permissionsBefore);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_role_permission rp
                  JOIN sys_permission p ON p.id = rp.permission_id
                 WHERE p.permission_code LIKE 'WALLET_%'
                """, Integer.class)).isEqualTo(mappingsBefore);
    }

    private int columnCount(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE UPPER(table_schema) = UPPER(?) "
                        + "AND UPPER(table_name) = UPPER(?) AND UPPER(column_name) = UPPER(?)",
                Integer.class, currentSchema(), tableName, columnName);
    }

    private int constraintCount(String tableName, String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT constraint_name) FROM information_schema.table_constraints "
                        + "WHERE UPPER(table_schema) = UPPER(?) "
                        + "AND UPPER(table_name) = UPPER(?) AND UPPER(constraint_name) = UPPER(?)",
                Integer.class, currentSchema(), tableName, constraintName);
    }

    private String currentSchema() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String schema = connection.getSchema();
            return schema == null || schema.isBlank() ? connection.getCatalog() : schema;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取当前数据库 schema", exception);
        }
    }
}
