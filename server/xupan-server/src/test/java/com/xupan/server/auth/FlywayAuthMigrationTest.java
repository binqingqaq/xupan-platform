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
            "PLATFORM_HOME_READ",
            "ROLE_MANAGE",
            "PERMISSION_MANAGE",
            "AUDIT_READ",
            "AGENT_MANAGE",
            "SUB_ACCOUNT_MANAGE",
            "MACHINE_MANAGE",
            "REPORT_READ",
            "DRAW_HISTORY_READ",
            "DRAW_HISTORY_FORCE_SETTLE",
            "DRAW_HISTORY_SUPPLEMENT",
            "UNSETTLED_ORDER_READ",
            "UNSETTLED_ORDER_DELETE",
            "ORDER_CORRECTION_READ",
            "ORDER_CORRECTION_MANAGE",
            "ONLINE_PLAYER_READ",
            "ONLINE_PLAYER_DISCONNECT",
            "ONLINE_PLAYER_MESSAGE",
            "PLATFORM_SETTINGS_READ",
            "PLATFORM_SETTINGS_WRITE",
            "REPORT_NETWORK_READ",
            "REPORT_NETWORK_WRITE",
            "GAME_SETTINGS_READ",
            "GAME_SETTINGS_WRITE",
            "PLATFORM_PASSWORD_MANAGE",
            "AGENT_CONSOLE_READ",
            "AGENT_PLAYER_MANAGE",
            "WALLET_READ",
            "WALLET_GRANT",
            "WALLET_ADJUST",
            "WALLET_LEDGER_READ",
            "SYSTEM_MONITOR_READ",
            "CENTER_AGENT_MANAGE",
            "CENTER_ORDER_READ");

    /**
     * V76 之后 ADMIN 的权限集合：全部权限码 **减去已撤销的 {@code AGENT_CONSOLE_READ}**。
     *
     * <p>{@link #PERMISSION_CODES} 表达的是"权限码本身仍然存在"（sys_permission 行不删），
     * 而角色授权集合由本常量表达，两者不能混为一谈。</p>
     */
    private static final Set<String> ADMIN_PERMISSION_CODES = PERMISSION_CODES.stream()
            .filter(code -> !"AGENT_CONSOLE_READ".equals(code))
            .collect(Collectors.toUnmodifiableSet());

    private static final Map<String, Set<String>> EXPECTED_ROLE_PERMISSIONS = Map.of(
            "USER", Set.of(
                    "CHAT_ROOM_READ", "CHAT_MESSAGE_SEND", "GAME_CURRENT_READ", "GAME_BET_PLACE",
                    "WALLET_READ"),
            "MODERATOR", Set.of(
                    "CHAT_ROOM_READ", "CHAT_MESSAGE_SEND", "GAME_CURRENT_READ", "GAME_BET_PLACE",
                    "CHAT_MESSAGE_REVIEW", "CHAT_MESSAGE_RECALL", "CHAT_USER_MUTE", "CHAT_USER_KICK",
                    "WALLET_READ"),
            // V57 起机器账号也持有"查看当前期"只读权限：代理后台（/console）顶部状态栏与下注榜需要它，
            // BY220 robot.php 本身就能看到当前期号与开奖。
            "AGENT", Set.of("AGENT_CONSOLE_READ", "AGENT_PLAYER_MANAGE", "GAME_CURRENT_READ"),
            "OPERATOR", Set.of(
                    "GAME_CURRENT_READ", "GAME_ODDS_READ", "GAME_ODDS_WRITE",
                    "ROBOT_READ", "ROBOT_WRITE", "ROBOT_TEMPLATE_WRITE"),
            // V76（阶段 4 入口收敛）：/console 严格对齐 BY220 robot.php，只接受机器账号，
            // 因此从 ADMIN / SUB_ACCOUNT 撤销 AGENT_CONSOLE_READ；
            // 权限码本身保留（sys_permission 行与 PermissionCode 枚举都不动），
            // AGENT_PLAYER_MANAGE 保留给两者（平台后台的玩家工作台要用）。
            "ADMIN", ADMIN_PERMISSION_CODES);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesAllMigrationsInOrder() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT \"version\" FROM \"flyway_schema_history\" "
                        + "WHERE \"success\" = TRUE AND \"version\" IS NOT NULL "
                        + "ORDER BY \"installed_rank\"",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60", "61", "62", "63", "64", "65", "66", "67", "68", "69", "70", "71", "72", "73", "74", "75", "76", "77", "78");
    }

    @Test
    void createsIdentitySessionAndAuditTables() {
        assertThat(tableNames()).contains(
                "SYS_USER", "SYS_ROLE", "SYS_PERMISSION", "SYS_USER_ROLE", "SYS_ROLE_PERMISSION",
                "AUTH_SESSION", "AUTH_WS_TICKET", "SYS_LOGIN_LOG", "SYS_OPERATION_LOG", "PLAYER_ACCESS_LINK", "AGENT_GROUP", "AGENT");

        assertThat(tableNames()).contains("TEST_PLAYER_BEHAVIOR", "TEST_PLAYER_ACTION");
        assertThat(columnNames("DEMO_USER_ACCOUNT")).contains("PLAYER_KIND");
        assertThat(columnNames("TEST_PLAYER_BEHAVIOR")).contains("RUN_MODE");
        assertThat(constraintNames("DEMO_USER_ACCOUNT")).contains("CK_DEMO_USER_ACCOUNT_PLAYER_KIND");

        assertThat(columnNames("SYS_USER")).containsExactlyInAnyOrder(
                "ID", "USERNAME", "DISPLAY_NAME", "AVATAR_KEY", "PASSWORD_HASH", "STATUS",
                "FAILED_LOGIN_COUNT", "LOCKED_UNTIL", "SECURITY_VERSION", "LAST_LOGIN_AT",
                "LAST_LOGIN_IP", "CREATED_AT", "UPDATED_AT", "USER_TYPE", "INTERNAL_CODE", "AUTH_MODE");
        assertThat(columnNames("DEMO_USER_ACCOUNT")).contains("MEMBER_CODE", "AGENT_ID");
        assertThat(columnNames("AUTH_SESSION")).containsExactlyInAnyOrder(
                "ID", "SESSION_ID", "USER_ID", "ACCESS_TOKEN_HASH", "ACCESS_EXPIRES_AT",
                "REFRESH_TOKEN_HASH", "REFRESH_EXPIRES_AT", "DEVICE_LABEL", "IP_DIGEST",
                "USER_AGENT_DIGEST", "LAST_SEEN_AT", "REVOKED_AT", "CREATED_AT", "SECURITY_VERSION", "AUTH_MODE", "SCOPE",
                // V75：会话入口维度（PLATFORM / CONSOLE）
                "ENTRY");
        assertThat(columnNames("AUTH_WS_TICKET")).contains("SCOPE");
        assertThat(columnNames("PLAYER_ACCESS_LINK")).containsExactlyInAnyOrder(
                "ID", "USER_ID", "TOKEN_HASH", "SCOPE", "EXPIRES_AT", "REVOKED_AT",
                "LAST_USED_AT", "CREATED_BY", "CREATED_AT", "TOKEN_CIPHERTEXT");
        assertThat(columnNames("SYS_OPERATION_LOG")).containsExactlyInAnyOrder(
                "ID", "OPERATOR_USER_ID", "PERMISSION_CODE", "HTTP_METHOD", "REQUEST_PATH",
                "RESOURCE_ID", "RESULT", "ERROR_CODE", "REQUEST_SUMMARY", "IP_DIGEST", "CREATED_AT");
    }

    @Test
    void createsExpectedKeysForeignKeysAndIndexes() {
        assertThat(constraintNames("SYS_USER")).contains(
                "UK_SYS_USER_USERNAME", "CK_SYS_USER_STATUS", "CK_SYS_USER_FAILED_LOGIN_COUNT", "CK_SYS_USER_AUTH_MODE");
        assertThat(constraintNames("SYS_ROLE")).contains("UK_SYS_ROLE_CODE", "CK_SYS_ROLE_STATUS");
        assertThat(constraintNames("SYS_PERMISSION")).contains(
                "UK_SYS_PERMISSION_CODE", "CK_SYS_PERMISSION_TYPE", "CK_SYS_PERMISSION_STATUS");
        assertThat(constraintNames("AUTH_SESSION")).contains(
                "UK_AUTH_SESSION_ID", "UK_AUTH_SESSION_ACCESS_HASH", "UK_AUTH_SESSION_REFRESH_HASH",
                "FK_AUTH_SESSION_USER", "CK_AUTH_SESSION_SECURITY_VERSION");
        assertThat(constraintNames("AUTH_SESSION")).contains("CK_AUTH_SESSION_AUTH_MODE", "CK_AUTH_SESSION_SCOPE",
                // V75：会话入口只允许 PLATFORM / CONSOLE
                "CK_AUTH_SESSION_ENTRY");
        assertThat(constraintNames("AUTH_WS_TICKET")).contains(
                "UK_AUTH_WS_TICKET_HASH", "FK_AUTH_WS_TICKET_USER", "FK_AUTH_WS_TICKET_SESSION", "CK_AUTH_WS_TICKET_SCOPE");
        assertThat(constraintNames("PLAYER_ACCESS_LINK")).contains(
                "UK_PLAYER_ACCESS_LINK_HASH", "FK_PLAYER_ACCESS_LINK_USER", "FK_PLAYER_ACCESS_LINK_CREATOR",
                "CK_PLAYER_ACCESS_LINK_SCOPE");
        assertThat(constraintNames("SYS_LOGIN_LOG")).contains(
                "FK_SYS_LOGIN_LOG_USER", "CK_SYS_LOGIN_LOG_RESULT");
        assertThat(constraintNames("SYS_OPERATION_LOG")).contains(
                "FK_SYS_OPERATION_LOG_USER", "CK_SYS_OPERATION_LOG_RESULT");

        assertThat(indexNames("SYS_USER")).contains("IDX_SYS_USER_STATUS");
        assertThat(indexNames("SYS_USER")).contains("UK_SYS_USER_INTERNAL_CODE");
        assertThat(indexNames("DEMO_USER_ACCOUNT")).contains("UK_DEMO_USER_ACCOUNT_MEMBER_CODE");
        assertThat(indexNames("SYS_USER_ROLE")).contains("IDX_SYS_USER_ROLE_ROLE");
        assertThat(indexNames("SYS_ROLE_PERMISSION")).contains("IDX_SYS_ROLE_PERMISSION_PERMISSION");
        assertThat(indexNames("AUTH_SESSION")).contains(
                "IDX_AUTH_SESSION_USER", "IDX_AUTH_SESSION_ACCESS_EXPIRY",
                "IDX_AUTH_SESSION_SECURITY_VERSION", "IDX_AUTH_SESSION_SCOPE");
        assertThat(indexNames("AUTH_WS_TICKET")).contains("IDX_AUTH_WS_TICKET_EXPIRY", "IDX_AUTH_WS_TICKET_SCOPE");
        assertThat(indexNames("PLAYER_ACCESS_LINK")).contains("IDX_PLAYER_ACCESS_LINK_USER");
        assertThat(indexNames("SYS_LOGIN_LOG")).contains(
                "IDX_SYS_LOGIN_LOG_USER", "IDX_SYS_LOGIN_LOG_CREATED");
        assertThat(indexNames("SYS_OPERATION_LOG")).contains(
                "IDX_SYS_OPERATION_LOG_OPERATOR", "IDX_SYS_OPERATION_LOG_CREATED");
    }

    @Test
    void seedsRolesPermissionsAndMappingsWithoutUsersOrSecrets() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_user
                 WHERE username LIKE 'migration-%'
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_session", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_ws_ticket", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role", Integer.class)).isEqualTo(6);
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

    /**
     * V75：会话入口维度的默认值与 CHECK 约束。
     *
     * <p>{@code DEFAULT 'PLATFORM'} 是刻意设计：升级前已签发的会话没有入口信息，
     * 统一按"老会话只对平台入口有效"处理，代理入口需要重登一次（计划 §9）。</p>
     */
    @Test
    void appliesSessionEntryDefaultAndConstraint() {
        long userId = insertUser("migration-session-entry");
        try {
            insertSession(userId, "migration-session-entry-default", hash('m'), hash('n'));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT entry FROM auth_session WHERE session_id = ?", String.class,
                    "migration-session-entry-default")).isEqualTo("PLATFORM");

            jdbcTemplate.update("UPDATE auth_session SET entry = 'CONSOLE' WHERE session_id = ?",
                    "migration-session-entry-default");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT entry FROM auth_session WHERE session_id = ?", String.class,
                    "migration-session-entry-default")).isEqualTo("CONSOLE");

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE auth_session SET entry = 'PLAYER' WHERE session_id = ?",
                    "migration-session-entry-default"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM auth_session WHERE session_id = ?",
                    "migration-session-entry-default");
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
    }

    /**
     * V76：从 ADMIN / SUB_ACCOUNT 撤销 {@code AGENT_CONSOLE_READ}，
     * 但权限码本身保留，且 {@code AGENT_PLAYER_MANAGE} 必须留在两个角色上。
     *
     * <p>BY220 依据：{@code application/rbt/controller/Login.php:55-77} 与
     * {@code application/rbt/common/Base.php:9-20} —— 代理后台只认 {@code x_robot} 表账号。</p>
     */
    @Test
    void v76RevokesConsoleReadFromPlatformRolesAndKeepsPlayerManage() {
        assertThat(permissionCodesForRole("ADMIN")).doesNotContain("AGENT_CONSOLE_READ");
        assertThat(permissionCodesForRole("SUB_ACCOUNT")).doesNotContain("AGENT_CONSOLE_READ");
        assertThat(permissionCodesForRole("AGENT")).contains("AGENT_CONSOLE_READ");

        assertThat(permissionCodesForRole("ADMIN")).contains("AGENT_PLAYER_MANAGE");
        assertThat(permissionCodesForRole("SUB_ACCOUNT")).contains("AGENT_PLAYER_MANAGE");
        assertThat(permissionCodesForRole("AGENT")).contains("AGENT_PLAYER_MANAGE");

        // 权限码本身保留：sys_permission 行与 PermissionCode 枚举都不动，
        // /api/agent/** 仍然只认 AGENT_CONSOLE_READ（收敛后只有机器账号能调）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_permission WHERE permission_code = 'AGENT_CONSOLE_READ'",
                Integer.class)).isEqualTo(1);
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
    void businessIdentityColumnsAreRequiredAndUnique() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = 'SYS_USER' AND UPPER(COLUMN_NAME) = 'INTERNAL_CODE'",
                String.class)).isEqualTo("NO");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = 'DEMO_USER_ACCOUNT' AND UPPER(COLUMN_NAME) = 'MEMBER_CODE'",
                String.class)).isEqualTo("NO");

        long userId = insertUser("migration-business-identity");
        long duplicateUserId = insertUser("migration-business-identity-duplicate");
        try {
            jdbcTemplate.update("UPDATE sys_user SET internal_code = ? WHERE id = ?", "wxid_migration", userId);
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE sys_user SET internal_code = ? WHERE id = ?", "wxid_migration", duplicateUserId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", duplicateUserId);
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

    @Test
    void createsAvatarPresetPool() {
        assertThat(tableNames()).contains("AVATAR_PRESET");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM avatar_preset", Integer.class)).isEqualTo(40);
        assertThat(constraintNames("AVATAR_PRESET")).contains(
                "UK_AVATAR_PRESET_ORDER", "UK_AVATAR_PRESET_ASSIGNEE", "FK_AVATAR_PRESET_USER");
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
                "INSERT INTO sys_user (username, display_name, password_hash, internal_code, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                username, "迁移测试用户", "test-password-hash", "wxid_" + username);
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
