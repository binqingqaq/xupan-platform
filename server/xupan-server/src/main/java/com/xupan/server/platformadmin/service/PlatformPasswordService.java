package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** BY220 admin.php - Pwd::ModifyPwd：超级管理账号只能修改当前登录账号。 */
@Service
public class PlatformPasswordService {

    private static final String PERMISSION = "PLATFORM_PASSWORD_MANAGE";
    private static final long SUPER_ADMIN_USER_ID = 1L;

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final PasswordPolicyService passwordPolicy;

    public PlatformPasswordService(JdbcTemplate jdbc, PermissionService permissionService,
                                   PasswordPolicyService passwordPolicy) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional(readOnly = true)
    public PasswordForm get(long operator) {
        requirePermission(operator);
        UserCredential user = requireUser(operator);
        return new PasswordForm(user.username(), operator == SUPER_ADMIN_USER_ID);
    }

    @Transactional
    public ChangeResult change(ChangeInput input, long operator) {
        requirePermission(operator);
        UserCredential current = lockUser(operator);

        String oldPassword = emptyToNull(input.oldPassword());
        String newPassword = emptyToNull(input.newPassword());
        String confirmation = emptyToNull(input.confirmPassword());
        String requestedUsername = trimToNull(input.username());
        boolean usernameChanged = false;
        boolean passwordChanged = false;
        String newPasswordHash = current.passwordHash();

        if (requestedUsername != null && operator == SUPER_ADMIN_USER_ID
                && !requestedUsername.equals(current.username())) {
            validateUsername(requestedUsername);
            usernameChanged = true;
        }

        if (oldPassword != null) {
            if (newPassword == null || confirmation == null || !newPassword.equals(confirmation)) {
                throw BusinessException.badRequest(
                        "PLATFORM_PASSWORD_CONFIRM_MISMATCH", "新密码与确认密码不一致");
            }
            if (!passwordPolicy.matches(oldPassword, current.passwordHash())) {
                throw BusinessException.badRequest("PLATFORM_PASSWORD_OLD_MISMATCH", "旧密码错误");
            }
            try {
                newPasswordHash = passwordPolicy.encode(newPassword);
            } catch (IllegalArgumentException exception) {
                throw BusinessException.badRequest("USER_PASSWORD_INVALID", exception.getMessage());
            }
            passwordChanged = true;
        }

        if (!usernameChanged && !passwordChanged) {
            throw BusinessException.badRequest("PLATFORM_PASSWORD_NO_CHANGE", "未做任何更改");
        }

        String username = usernameChanged ? requestedUsername : current.username();
        try {
            int updated = jdbc.update("""
                    UPDATE sys_user
                       SET username = ?, password_hash = ?, security_version = security_version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """, username, newPasswordHash, operator);
            if (updated != 1) {
                throw BusinessException.forbidden("PLATFORM_PASSWORD_FORBIDDEN", "当前账号不可修改");
            }
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("USER_USERNAME_EXISTS", "用户名已存在");
        }

        audit(operator, usernameChanged, passwordChanged);
        return new ChangeResult("1", usernameChanged, passwordChanged, username);
    }

    private void validateUsername(String username) {
        if (username.length() > 64 || username.chars().anyMatch(Character::isWhitespace)
                || username.chars().anyMatch(Character::isISOControl)) {
            throw BusinessException.badRequest("USER_USERNAME_INVALID", "登录账号格式无效");
        }
    }

    private void requirePermission(long operator) {
        if (operator <= 0 || !permissionService.hasPermission(operator, PERMISSION)) {
            throw BusinessException.forbidden("PLATFORM_PASSWORD_FORBIDDEN", "没有修改密码权限");
        }
    }

    private UserCredential requireUser(long operator) {
        List<UserCredential> rows = jdbc.query("""
                SELECT id, username, password_hash
                  FROM sys_user
                 WHERE id = ?
                """, (rs, rowNum) -> new UserCredential(
                rs.getLong("id"), rs.getString("username"), rs.getString("password_hash")), operator);
        if (rows.isEmpty()) {
            throw BusinessException.forbidden("PLATFORM_PASSWORD_FORBIDDEN", "当前账号不可修改");
        }
        return rows.get(0);
    }

    private UserCredential lockUser(long operator) {
        List<UserCredential> rows = jdbc.query("""
                SELECT id, username, password_hash
                  FROM sys_user
                 WHERE id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new UserCredential(
                rs.getLong("id"), rs.getString("username"), rs.getString("password_hash")), operator);
        if (rows.isEmpty()) {
            throw BusinessException.forbidden("PLATFORM_PASSWORD_FORBIDDEN", "当前账号不可修改");
        }
        return rows.get(0);
    }

    private void audit(long operator, boolean usernameChanged, boolean passwordChanged) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'PUT', '/api/admin/password', ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, PERMISSION, Long.toString(operator),
                "usernameChanged=" + usernameChanged + ",passwordChanged=" + passwordChanged);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record PasswordForm(String username, boolean canChangeUsername) {
    }

    public record ChangeInput(String username, String oldPassword, String newPassword,
                              String confirmPassword) {
    }

    public record ChangeResult(String message, boolean usernameChanged, boolean passwordChanged,
                               String username) {
    }

    private record UserCredential(long id, String username, String passwordHash) {
    }
}
