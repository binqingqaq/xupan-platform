package com.xupan.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlywayAuthMigrationTest {

    private static final Set<String> PERMISSION_CODES = Set.of(
            "CHAT_ROOM_READ",
            "CHAT_MESSAGE_SEND",
            "GAME_CURRENT_READ",
            "GAME_BET_PLACE",
            "CHAT_MESSAGE_REVIEW",
            "CHAT_MESSAGE_RECALL",
            "CHAT_USER_MUTE",
            "CHAT_USER_KICK",
            "GAME_ODDS_READ",
            "GAME_ODDS_WRITE",
            "ROBOT_READ",
            "ROBOT_WRITE",
            "ROBOT_TEMPLATE_WRITE",
            "USER_MANAGE",
            "ROLE_MANAGE",
            "PERMISSION_MANAGE",
            "AUDIT_READ");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesV1ThroughV6InOrder() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT \"version\" FROM \"flyway_schema_history\" "
                        + "WHERE \"success\" = TRUE AND \"version\" IS NOT NULL "
                        + "ORDER BY \"installed_rank\"",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void createsIdentitySessionAndAuditTables() {
        assertThat(tableNames()).containsExactlyInAnyOrder(
                "SYS_USER", "SYS_ROLE", "SYS_PERMISSION", "SYS_USER_ROLE", "SYS_ROLE_PERMISSION",
                "AUTH_SESSION", "AUTH_WS_TICKET", "SYS_LOGIN_LOG", "SYS_OPERATION_LOG");

        assertThat(columnNames("SYS_USER")).containsExactlyInAnyOrder(
                "ID", "USERNAME", "DISPLAY_NAME", "AVATAR_KEY", "PASSWORD_HASH", "STATUS",
                "FAILED_LOGIN_COUNT", "LOCKED_UNTIL", "SECURITY_VERSION", "LAST_LOGIN_AT",
                "LAST_LOGIN_IP", "CREATED_AT", "UPDATED_AT");
        assertThat(columnNames("AUTH_SESSION")).containsExactlyInAnyOrder(
                "ID", "SESSION_ID", "USER_ID", "ACCESS_TOKEN_HASH", "ACCESS_EXPIRES_AT",
                "REFRESH_TOKEN_HASH", "REFRESH_EXPIRES_AT", "DEVICE_LABEL", "IP_DIGEST",
                "USER_AGENT_DIGEST", "LAST_SEEN_AT", "REVOKED_AT", "CREATED_AT");
        assertThat(columnNames("SYS_OPERATION_LOG")).containsExactlyInAnyOrder(
                "ID", "OPERATOR_USER_ID", "PERMISSION_CODE", "HTTP_METHOD", "REQUEST_PATH",
                "RESOURCE_ID", "RESULT", "ERROR_CODE", "REQUEST_SUMMARY", "IP_DIGEST", "CREATED_AT");
    }

    @Test
    void createsExpectedKeysForeignKeysAndIndexes() {
        assertThat(constraintNames("SYS_USER")).contains(
                "UK_SYS_USER_USERNAME", "CK_SYS_USER_STATUS", "CK_SYS_USER_FAILED_LOGIN_COUNT");
        assertThat(constraintNames("SYS_ROLE")).contains("UK_SYS_ROLE_CODE", "CK_SYS_ROLE_STATUS");
        assertThat(constraintNames("SYS_PERMISSION")).contains(
                "UK_SYS_PERMISSION_CODE", "CK_SYS_PERMISSION_TYPE", "CK_SYS_PERMISSION_STATUS");
        assertThat(constraintNames("AUTH_SESSION")).contains(
                "UK_AUTH_SESSION_ID", "UK_AUTH_SESSION_ACCESS_HASH", "UK_AUTH_SESSION_REFRESH_HASH",
                "FK_AUTH_SESSION_USER");
        assertThat(constraintNames("AUTH_WS_TICKET")).contains(
                "UK_AUTH_WS_TICKET_HASH", "FK_AUTH_WS_TICKET_USER", "FK_AUTH_WS_TICKET_SESSION");
        assertThat(constraintNames("SYS_LOGIN_LOG")).contains(
                "FK_SYS_LOGIN_LOG_USER", "CK_SYS_LOGIN_LOG_RESULT");
        assertThat(constraintNames("SYS_OPERATION_LOG")).contains(
                "FK_SYS_OPERATION_LOG_USER", "CK_SYS_OPERATION_LOG_RESULT");

        assertThat(indexNames("SYS_USER")).contains("IDX_SYS_USER_STATUS");
        assertThat(indexNames("SYS_USER_ROLE")).contains("IDX_SYS_USER_ROLE_ROLE");
        assertThat(indexNames("SYS_ROLE_PERMISSION")).contains("IDX_SYS_ROLE_PERMISSION_PERMISSION");
        assertThat(indexNames("AUTH_SESSION")).contains(
                "IDX_AUTH_SESSION_USER", "IDX_AUTH_SESSION_ACCESS_EXPIRY");
        assertThat(indexNames("AUTH_WS_TICKET")).contains("IDX_AUTH_WS_TICKET_EXPIRY");
        assertThat(indexNames("SYS_LOGIN_LOG")).contains(
                "IDX_SYS_LOGIN_LOG_USER", "IDX_SYS_LOGIN_LOG_CREATED");
        assertThat(indexNames("SYS_OPERATION_LOG")).contains(
                "IDX_SYS_OPERATION_LOG_OPERATOR", "IDX_SYS_OPERATION_LOG_CREATED");
    }

    @Test
    void seedsRolesPermissionsAndMappingsWithoutUsersOrSecrets() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_session", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_ws_ticket", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role", Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_permission", Integer.class))
                .isEqualTo(PERMISSION_CODES.size());

        Set<String> actualPermissionCodes = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT permission_code FROM sys_permission ORDER BY permission_code", String.class));
        assertThat(actualPermissionCodes).isEqualTo(PERMISSION_CODES);

        List<Map<String, Object>> mappingRows = jdbcTemplate.queryForList(
                "SELECT r.role_code, COUNT(rp.permission_id) AS permission_count "
                        + "FROM sys_role r LEFT JOIN sys_role_permission rp ON rp.role_id = r.id "
                        + "GROUP BY r.role_code");
        Map<String, Integer> mappingCounts = mappingRows.stream().collect(Collectors.toMap(
                row -> String.valueOf(row.get("ROLE_CODE")),
                row -> ((Number) row.get("PERMISSION_COUNT")).intValue()));
        assertThat(mappingCounts).containsEntry("USER", 4)
                .containsEntry("MODERATOR", 8)
                .containsEntry("OPERATOR", 6)
                .containsEntry("ADMIN", 17);
    }

    private Set<String> tableNames() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT UPPER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' "
                        + "AND UPPER(TABLE_NAME) NOT LIKE 'FLYWAY%' AND UPPER(TABLE_NAME) NOT LIKE 'GAME_%' "
                        + "AND UPPER(TABLE_NAME) NOT LIKE 'DEMO_%'",
                String.class));
    }

    private Set<String> columnNames(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT UPPER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' AND UPPER(TABLE_NAME) = ?",
                String.class, tableName));
    }

    private Set<String> constraintNames(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT UPPER(CONSTRAINT_NAME) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' AND UPPER(TABLE_NAME) = ?",
                String.class, tableName.toUpperCase()));
    }

    private Set<String> indexNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT UPPER(INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES "
                                + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' AND UPPER(TABLE_NAME) = ?",
                        String.class, tableName.toUpperCase())
                .stream()
                .collect(Collectors.toSet());
    }
}
