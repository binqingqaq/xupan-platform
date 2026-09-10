package com.xupan.server.auth.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PermissionServiceTest {

    @Autowired
    private PermissionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'permission-test-%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'permission-test-%'");
    }

    @Test
    void onlyActiveRolesAndPermissionsBecomeAuthorities() {
        long userId = insertUser("permission-test-user");
        assign(userId, "USER");
        long disabledRoleId = insertRole("permission-test-disabled-role", "DISABLED");
        long disabledPermissionId = insertPermission("PERMISSION_TEST_DISABLED", "DISABLED");
        assignRole(userId, disabledRoleId);
        assignPermission(disabledRoleId, disabledPermissionId);

        Set<String> codes = service.findPermissionCodes(userId);
        assertThat(codes).containsExactlyInAnyOrder("CHAT_ROOM_READ", "CHAT_MESSAGE_SEND",
                "GAME_CURRENT_READ", "GAME_BET_PLACE");
        assertThat(service.toAuthorities(codes)).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("PERM_CHAT_ROOM_READ", "PERM_CHAT_MESSAGE_SEND",
                        "PERM_GAME_CURRENT_READ", "PERM_GAME_BET_PLACE");
        assertThat(service.hasPermission(userId, "USER_MANAGE")).isFalse();
    }

    private long insertUser(String username) {
        jdbcTemplate.update("INSERT INTO sys_user (username, display_name, password_hash) VALUES (?, ?, ?)",
                username, username, "hash");
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private void assign(long userId, String roleCode) {
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE role_code = ?",
                userId, roleCode);
    }

    private long insertRole(String roleCode, String status) {
        jdbcTemplate.update("INSERT INTO sys_role (role_code, display_name, status) VALUES (?, ?, ?)",
                roleCode, roleCode, status);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE role_code = ?", Long.class, roleCode);
    }

    private long insertPermission(String code, String status) {
        jdbcTemplate.update("INSERT INTO sys_permission (permission_code, display_name, status) VALUES (?, ?, ?)",
                code, code, status);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_permission WHERE permission_code = ?", Long.class, code);
    }

    private void assignRole(long userId, long roleId) {
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }

    private void assignPermission(long roleId, long permissionId) {
        jdbcTemplate.update("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (?, ?)", roleId, permissionId);
    }
}
