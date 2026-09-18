package com.xupan.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            "AUDIT_READ",
            "WALLET_READ",
            "WALLET_GRANT",
            "WALLET_ADJUST",
            "WALLET_LEDGER_READ",
            "SYSTEM_MONITOR_READ");

    private static final Map<String, Set<String>> EXPECTED_ROLE_PERMISSIONS = Map.of(
            "USER", Set.of(
                    "CHAT_ROOM_READ", "CHAT_MESSAGE_SEND", "GAME_CURRENT_READ", "GAME_BET_PLACE",
                    "WALLET_READ"),
            "MODERATOR", Set.of(
                    "CHAT_ROOM_READ", "CHAT_MESSAGE_SEND", "GAME_CURRENT_READ", "GAME_BET_PLACE",
                    "CHAT_MESSAGE_REVIEW", "CHAT_MESSAGE_RECALL", "CHAT_USER_MUTE", "CHAT_USER_KICK",
                    "WALLET_READ"),
            "OPERATOR", Set.of(
                    "GAME_CURRENT_READ", "GAME_ODDS_READ", "GAME_ODDS_WRITE",
                    "ROBOT_READ", "ROBOT_WRITE", "ROBOT_TEMPLATE_WRITE"),
            "ADMIN", PERMISSION_CODES);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesV1ThroughV15InOrder() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT \"version\" FROM \"flyway_schema_history\" "
                        + "WHERE \"success\" = TRUE AND \"version\" IS NOT NULL "
                        + "ORDER BY \"installed_rank\"",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");
    }

    @Test
    void createsIdentitySessionAndAuditTables() {
        assertThat(tableNames()).containsExactlyInAnyOrder(
                "SYS_USER", "SYS_ROLE", "SYS_PERMISSION", "SYS_USER_ROLE", "SYS_ROLE_PERMISSION",
                "AUTH_SESSION", "AUTH_WS_TICKET", "SYS_LOGIN_LOG", "SYS_OPERATION_LOG");

        assertThat(columnNames("SYS_USER")).containsExactlyInAnyOrder(
                "ID", "USERNAME", "DISPLAY_NAME", "AVATAR_KEY", "PASSWORD_HASH", "STATUS",
                "FAILED_LOGIN_COUNT", "LOCKED_UNTIL", "SECURITY_VERSION", "LAST_LOGIN_AT",
                "LAST_LOGIN_IP", "CREATED_AT", "UPDATED_AT", "USER_TYPE");
        assertThat(columnNames("AUTH_SESSION")).containsExactlyInAnyOrder(
                "ID", "SESSION_ID", "USER_ID", "ACCESS_TOKEN_HASH", "ACCESS_EXPIRES_AT",
                "REFRESH_TOKEN_HASH", "REFRESH_EXPIRES_AT", "DEVICE_LABEL", "IP_DIGEST",
                "USER_AGENT_DIGEST", "LAST_SEEN_AT", "REVOKED_AT", "CREATED_AT", "SECURITY_VERSION");
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
                "FK_AUTH_SESSION_USER", "CK_AUTH_SESSION_SECURITY_VERSION");
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
                "IDX_AUTH_SESSION_USER", "IDX_AUTH_SESSION_ACCESS_EXPIRY",
                "IDX_AUTH_SESSION_SECURITY_VERSION");
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

        EXPECTED_ROLE_PERMISSIONS.forEach((roleCode, expectedPermissions) ->
                assertThat(permissionCodesForRole(roleCode)).isEqualTo(expectedPermissions));
    }

    @Test
    void monitorPermissionIsBoundOnlyToAdmin() {
        assertThat(permissionCodesForRole("ADMIN")).contains("SYSTEM_MONITOR_READ");
        assertThat(permissionCodesForRole("USER")).doesNotContain("SYSTEM_MONITOR_READ");
        assertThat(permissionCodesForRole("MODERATOR")).doesNotContain("SYSTEM_MONITOR_READ");
        assertThat(permissionCodesForRole("OPERATOR")).doesNotContain("SYSTEM_MONITOR_READ");
    }

    @Test
    void appliesSessionSecurityVersionDefaultAndConstraint() {
        long userId = insertUser("migration-session-security-version");
        try {
            insertSession(userId, "migration-session-security-version", hash('v'), hash('w'));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT security_version FROM auth_session WHERE session_id = ?", Long.class,
                    "migration-session-security-version")).isZero();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE auth_session SET security_version = -1 WHERE session_id = ?",
                    "migration-session-security-version"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM auth_session WHERE session_id = ?",
                    "migration-session-security-version");
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
    }

    @Test
    void rejectsDuplicateIdentityAndTokenHashes() {
        String username = "migration-duplicate-username";
        long userId = insertUser(username);
        try {
            assertThatThrownBy(() -> insertUser(username))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO sys_role (role_code, display_name) VALUES (?, ?)",
                    "USER", "重复角色"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO sys_permission (permission_code, display_name) VALUES (?, ?)",
                    "CHAT_ROOM_READ", "重复权限"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            insertSession(userId, "migration-duplicate-session", hash('a'), hash('b'));
            assertThatThrownBy(() -> insertSession(
                    userId, "migration-duplicate-access", hash('a'), hash('c')))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertSession(
                    userId, "migration-duplicate-refresh", hash('d'), hash('b')))
                    .isInstanceOf(DataIntegrityViolationException.class);

            insertTicket(userId, "migration-duplicate-session", hash('e'));
            assertThatThrownBy(() -> insertTicket(
                    userId, "migration-duplicate-session", hash('e')))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE session_id LIKE 'migration-duplicate-%'");
            jdbcTemplate.update("DELETE FROM auth_session WHERE session_id LIKE 'migration-duplicate-%'");
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
    }

    @Test
    void rejectsReferencesToMissingUsers() {
        long invalidUserId = Long.MAX_VALUE;
        long userId = insertUser("migration-fk-user");
        insertSession(userId, "migration-fk-session", hash('f'), hash('g'));
        try {
            long roleId = idForRole("USER");
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                    invalidUserId, roleId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> insertSession(
                    invalidUserId, "migration-fk-invalid-session", hash('h'), hash('i')))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> insertTicket(
                    invalidUserId, "migration-fk-session", hash('j')))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO sys_login_log "
                            + "(username_snapshot, user_id, result) VALUES (?, ?, ?)",
                    "migration-fk-user", invalidUserId, "SUCCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO sys_operation_log "
                            + "(operator_user_id, http_method, request_path, result) VALUES (?, ?, ?, ?)",
                    invalidUserId, "POST", "/api/test", "SUCCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE session_id = 'migration-fk-session'");
            jdbcTemplate.update("DELETE FROM auth_session WHERE session_id = 'migration-fk-session'");
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
    }

    @Test
    void replayingEquivalentSeedInsertsDoesNotIncreaseCounts() {
        Map<String, Integer> before = seedCounts();

        EXPECTED_ROLE_PERMISSIONS.forEach((roleCode, permissionCodes) -> {
            jdbcTemplate.update(
                    "INSERT INTO sys_role (role_code, display_name) "
                            + "SELECT ?, ? WHERE NOT EXISTS "
                            + "(SELECT 1 FROM sys_role WHERE role_code = ?)",
                    roleCode, roleCode + "重复执行", roleCode);
            permissionCodes.forEach(permissionCode -> jdbcTemplate.update(
                    "INSERT INTO sys_permission (permission_code, display_name) "
                            + "SELECT ?, ? WHERE NOT EXISTS "
                            + "(SELECT 1 FROM sys_permission WHERE permission_code = ?)",
                    permissionCode, permissionCode + "重复执行", permissionCode));
        });
        EXPECTED_ROLE_PERMISSIONS.forEach((roleCode, permissionCodes) -> permissionCodes.forEach(permissionCode ->
                jdbcTemplate.update(
                        "INSERT INTO sys_role_permission (role_id, permission_id) "
                                + "SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p "
                                + "WHERE r.role_code = ? AND p.permission_code = ? "
                                + "AND NOT EXISTS (SELECT 1 FROM sys_role_permission existing "
                                + "WHERE existing.role_id = r.id AND existing.permission_id = p.id)",
                        roleCode, permissionCode)));

        assertThat(seedCounts()).isEqualTo(before);
    }

    private Set<String> permissionCodesForRole(String roleCode) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT p.permission_code FROM sys_role r "
                        + "JOIN sys_role_permission rp ON rp.role_id = r.id "
                        + "JOIN sys_permission p ON p.id = rp.permission_id "
                        + "WHERE r.role_code = ? ORDER BY p.permission_code",
                String.class, roleCode));
    }

    private Map<String, Integer> seedCounts() {
        return Map.of(
                "roles", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role", Integer.class),
                "permissions", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_permission", Integer.class),
                "userRoles", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user_role", Integer.class),
                "rolePermissions", jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sys_role_permission", Integer.class));
    }

    private long insertUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, display_name, password_hash) VALUES (?, ?, ?)",
                username, "迁移测试用户", "test-password-hash");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private long idForRole(String roleCode) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE role_code = ?", Long.class, roleCode);
    }

    private void insertSession(long userId, String sessionId, String accessHash, String refreshHash) {
        jdbcTemplate.update(
                "INSERT INTO auth_session "
                        + "(session_id, user_id, access_token_hash, access_expires_at, "
                        + "refresh_token_hash, refresh_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, userId, accessHash, futureTimestamp(30), refreshHash, futureTimestamp(60));
    }

    private void insertTicket(long userId, String sessionId, String ticketHash) {
        jdbcTemplate.update(
                "INSERT INTO auth_ws_ticket "
                        + "(ticket_hash, user_id, session_id, expires_at) VALUES (?, ?, ?, ?)",
                ticketHash, userId, sessionId, futureTimestamp(1));
    }

    private Timestamp futureTimestamp(long minutes) {
        return Timestamp.from(Instant.now().plusSeconds(minutes * 60));
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private Set<String> tableNames() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT UPPER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' "
                        + "AND UPPER(TABLE_NAME) NOT LIKE 'FLYWAY%' AND UPPER(TABLE_NAME) NOT LIKE 'GAME_%' "
                        + "AND UPPER(TABLE_NAME) NOT LIKE 'DEMO_%' "
                        + "AND UPPER(TABLE_NAME) NOT LIKE 'CHAT_%'",
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
