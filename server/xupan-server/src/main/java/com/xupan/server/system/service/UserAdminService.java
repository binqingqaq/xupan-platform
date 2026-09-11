package com.xupan.server.system.service;

import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.repository.VirtualWalletRepository;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.system.repository.RoleRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class UserAdminService {

    private static final Set<String> MANAGEABLE_ROLES = Set.of("USER", "MODERATOR", "OPERATOR", "ADMIN");
    private static final Set<String> MANAGED_STATUSES = Set.of("ACTIVE", "DISABLED", "LOCKED");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionService permissionService;
    private final SessionRepository sessionRepository;
    private final PasswordPolicyService passwordPolicy;
    private final VirtualWalletService walletService;
    private final VirtualWalletRepository walletRepository;
    private final OperationAuditRepository auditRepository;

    public UserAdminService(UserRepository userRepository, RoleRepository roleRepository,
                            SessionRepository sessionRepository, PasswordPolicyService passwordPolicy,
                            VirtualWalletService walletService, VirtualWalletRepository walletRepository,
                            OperationAuditRepository auditRepository, PermissionService permissionService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionService = permissionService;
        this.sessionRepository = sessionRepository;
        this.passwordPolicy = passwordPolicy;
        this.walletService = walletService;
        this.walletRepository = walletRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public UserPage listUsers(String status, String keyword, int page, int pageSize) {
        validateQuery(status, keyword, page, pageSize);
        String normalizedKeyword = normalizeKeyword(keyword);
        List<UserSummary> items = userRepository.findManagementPage(status, normalizedKeyword, page, pageSize)
                .stream().map(this::toSummary).toList();
        return new UserPage(items, page, pageSize,
                userRepository.countManagementUsers(status, normalizedKeyword));
    }

    @Transactional(readOnly = true)
    public UserDetail getUser(long targetUserId, long operatorUserId) {
        requireAdmin(operatorUserId);
        UserRepository.UserManagementRow user = userRepository.findManagementUser(targetUserId)
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "用户不存在"));
        return toDetail(user);
    }

    @Transactional(readOnly = true)
    public List<RoleOption> listRoles(long operatorUserId) {
        requireAdmin(operatorUserId);
        return roleRepository.findActiveRoles().stream()
                .map(role -> new RoleOption(role.id(), role.roleCode(), role.displayName(), role.status()))
                .toList();
    }

    @Transactional
    public long createUser(String username, String displayName, String rawPassword, long operatorUserId) {
        requireAdmin(operatorUserId);
        return createUserInternal(username, displayName, rawPassword, "USER", operatorUserId);
    }

    @Transactional
    public long bootstrapAdmin(String username, String rawPassword) {
        return createUserInternal(username, username, rawPassword, "ADMIN", 0L);
    }

    /** Compatibility entry point retained for bootstrap; ADMIN is legal only with operator id 0. */
    @Transactional
    public long createUser(String username, String displayName, String rawPassword,
                           String initialRoleCode, long operatorUserId) {
        if (operatorUserId != 0L) {
            requireAdmin(operatorUserId);
            if (!"USER".equals(initialRoleCode)) {
                throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "只能创建普通用户");
            }
        } else if (!"ADMIN".equals(initialRoleCode)) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "初始化用户只能是管理员");
        }
        return createUserInternal(username, displayName, rawPassword, initialRoleCode, operatorUserId);
    }

    @Transactional
    public UserDetail changeStatus(long targetUserId, String status, long operatorUserId) {
        requireAdmin(operatorUserId);
        List<Long> administratorIds = roleRepository.findActiveAdminUserIdsForUpdate();
        String normalizedStatus = required(status, "USER_STATUS_INVALID", "用户状态不能为空");
        if (!MANAGED_STATUSES.contains(normalizedStatus)) {
            throw BusinessException.badRequest("USER_STATUS_INVALID", "用户状态无效");
        }
        UserAccount target = userRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "用户不存在"));
        if (targetUserId == operatorUserId && !normalizedStatus.equals(target.status())) {
            throw BusinessException.conflict("USER_SELF_OPERATION_FORBIDDEN", "不能修改自己的登录状态");
        }
        if (roleRepository.userHasRole(targetUserId, "ADMIN") && isInactive(normalizedStatus)
                && administratorIds.size() <= 1) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "不能停用系统最后一个管理员");
        }
        if (normalizedStatus.equals(target.status())) {
            return getUser(targetUserId, operatorUserId);
        }
        if (userRepository.updateManagedStatus(targetUserId, normalizedStatus) != 1) {
            throw BusinessException.notFound("USER_NOT_FOUND", "用户不存在");
        }
        sessionRepository.revokeAllActiveByUserId(targetUserId, Instant.now());
        audit(operatorUserId, "PATCH", "/api/admin/users/" + targetUserId + "/status",
                Long.toString(targetUserId), "status=" + normalizedStatus);
        return getUser(targetUserId, operatorUserId);
    }

    @Transactional
    public void resetPassword(long targetUserId, String rawPassword, long operatorUserId) {
        requireAdmin(operatorUserId);
        UserAccount target = userRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "用户不存在"));
        String passwordHash = encodeManagedPassword(rawPassword);
        if (userRepository.updatePasswordHash(target.id(), passwordHash) != 1) {
            throw BusinessException.notFound("USER_NOT_FOUND", "用户不存在");
        }
        sessionRepository.revokeAllActiveByUserId(targetUserId, Instant.now());
        audit(operatorUserId, "POST", "/api/admin/users/" + targetUserId + "/password",
                Long.toString(targetUserId), "username=" + target.username());
    }

    @Transactional
    public UserDetail replaceRoles(long targetUserId, Collection<String> roleCodes, long operatorUserId) {
        requireAdmin(operatorUserId);
        List<Long> administratorIds = roleRepository.findActiveAdminUserIdsForUpdate();
        Set<String> requested = normalizeRoles(roleCodes);
        UserAccount target = userRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "用户不存在"));
        Set<String> current = new LinkedHashSet<>(userRepository.findRoleCodes(targetUserId));
        List<com.xupan.server.system.domain.RoleOption> roles = roleRepository.findActiveByCodes(requested);
        if (roles.size() != requested.size()
                || roles.stream().anyMatch(role -> !MANAGEABLE_ROLES.contains(role.roleCode()))) {
            throw BusinessException.badRequest("USER_ROLE_INVALID", "角色不存在、已停用或不允许授予");
        }
        if (targetUserId == operatorUserId && !current.equals(requested)) {
            throw BusinessException.conflict("USER_SELF_OPERATION_FORBIDDEN", "不能修改自己的角色");
        }
        if (current.contains("ADMIN") && !requested.contains("ADMIN")
                && administratorIds.size() <= 1) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "不能移除系统最后一个管理员");
        }
        if (current.equals(requested)) {
            return getUser(targetUserId, operatorUserId);
        }
        userRepository.deleteRoles(targetUserId);
        userRepository.assignRoles(targetUserId, roles.stream()
                .map(com.xupan.server.system.domain.RoleOption::id).toList());
        userRepository.incrementSecurityVersion(targetUserId);
        sessionRepository.revokeAllActiveByUserId(targetUserId, Instant.now());
        audit(operatorUserId, "PUT", "/api/admin/users/" + targetUserId + "/roles",
                Long.toString(targetUserId), "roleCodes=" + String.join(",", requested));
        return getUser(targetUserId, operatorUserId);
    }

    private long createUserInternal(String username, String displayName, String rawPassword,
                                    String roleCode, long operatorUserId) {
        String normalizedUsername = required(username, "REQUEST_INVALID", "用户名不能为空");
        String normalizedDisplayName = required(displayName, "REQUEST_INVALID", "显示名称不能为空");
        if (!MANAGEABLE_ROLES.contains(roleCode)) {
            throw BusinessException.badRequest("USER_ROLE_INVALID", "用户角色无效");
        }
        String passwordHash = encodeManagedPassword(rawPassword);
        if (userRepository.findByUsername(normalizedUsername).isPresent()) {
            throw BusinessException.conflict("USER_USERNAME_EXISTS", "用户名已存在");
        }
        if (roleRepository.findActiveByCodes(Set.of(roleCode)).size() != 1) {
            throw BusinessException.badRequest("USER_ROLE_INVALID", "用户角色不存在或已停用");
        }
        try {
            long userId = userRepository.insert(normalizedUsername, normalizedDisplayName,
                    passwordHash, "ACTIVE");
            if (userRepository.assignRole(userId, roleCode) != 1) {
                throw BusinessException.badRequest("USER_ROLE_INVALID", "用户角色不可用");
            }
            walletService.ensureWalletForUser(userId, normalizedDisplayName);
            if (operatorUserId > 0) {
                audit(operatorUserId, "POST", "/api/admin/users", Long.toString(userId),
                        "username=" + normalizedUsername + ",displayName=" + normalizedDisplayName);
            }
            return userId;
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("USER_USERNAME_EXISTS", "用户名已存在");
        }
    }

    private UserSummary toSummary(UserRepository.UserManagementRow user) {
        return new UserSummary(user.id(), user.username(), user.displayName(), user.status(),
                userRepository.findRoleCodes(user.id()), user.createdAt(), user.lastLoginAt());
    }

    private UserDetail toDetail(UserRepository.UserManagementRow user) {
        VirtualWallet wallet = walletRepository.findByUserId(user.id()).orElse(null);
        WalletSummary walletSummary = wallet == null ? null
                : new WalletSummary(wallet.accountId(), wallet.balance(), wallet.status());
        return new UserDetail(user.id(), user.username(), user.displayName(), user.status(),
                userRepository.findRoleCodes(user.id()), user.createdAt(), user.lastLoginAt(), walletSummary);
    }

    private void requireAdmin(long operatorUserId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, "USER_MANAGE")) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "没有用户管理权限");
        }
    }

    private String encodeManagedPassword(String rawPassword) {
        try {
            return passwordPolicy.encode(rawPassword);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("USER_PASSWORD_INVALID", "密码不符合安全策略");
        }
    }

    private static Set<String> normalizeRoles(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw BusinessException.badRequest("USER_ROLE_INVALID", "至少需要一个角色");
        }
        Set<String> normalized = roleCodes.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (normalized.size() != roleCodes.size()
                || normalized.stream().anyMatch(value -> !MANAGEABLE_ROLES.contains(value))) {
            throw BusinessException.badRequest("USER_ROLE_INVALID", "角色不存在、已停用或不允许授予");
        }
        return normalized;
    }

    private static void validateQuery(String status, String keyword, int page, int pageSize) {
        if (status != null && !status.isBlank()
                && !Set.of("ACTIVE", "DISABLED", "LOCKED", "DELETED").contains(status)) {
            throw BusinessException.badRequest("USER_STATUS_INVALID", "用户状态无效");
        }
        if (page < 1 || pageSize < 1 || pageSize > 100
                || (long) (page - 1) * pageSize > Integer.MAX_VALUE * 100L) {
            throw BusinessException.badRequest("USER_QUERY_INVALID", "分页参数无效");
        }
        if (keyword != null && keyword.trim().length() > 64) {
            throw BusinessException.badRequest("USER_QUERY_INVALID", "关键字长度不能超过 64 个字符");
        }
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private static boolean isInactive(String status) {
        return "DISABLED".equals(status) || "LOCKED".equals(status);
    }

    private static String required(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest(code, message);
        }
        return value.trim();
    }

    private void audit(long operatorUserId, String method, String path, String resourceId, String summary) {
        auditRepository.record(operatorUserId, "USER_MANAGE", method, path, resourceId,
                "SUCCESS", null, summary, null, Instant.now());
    }

    public record UserPage(List<UserSummary> items, int page, int pageSize, long total) {
    }

    public record UserSummary(long id, String username, String displayName, String status,
                              List<String> roles, Instant createdAt, Instant lastLoginAt) {
    }

    public record UserDetail(long id, String username, String displayName, String status,
                             List<String> roles, Instant createdAt, Instant lastLoginAt,
                             WalletSummary wallet) {
    }

    public record WalletSummary(long accountId, BigDecimal balance, String status) {
    }

    public record RoleOption(long id, String code, String displayName, String status) {
    }
}
