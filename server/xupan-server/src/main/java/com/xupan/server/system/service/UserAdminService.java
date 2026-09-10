package com.xupan.server.system.service;

import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class UserAdminService {

    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicy;
    private final VirtualWalletService walletService;
    private final OperationAuditRepository auditRepository;

    public UserAdminService(UserRepository userRepository,
                            PasswordPolicyService passwordPolicy,
                            VirtualWalletService walletService,
                            OperationAuditRepository auditRepository) {
        this.userRepository = userRepository;
        this.passwordPolicy = passwordPolicy;
        this.walletService = walletService;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public long createUser(String username, String displayName, String rawPassword,
                           String initialRoleCode, long operatorUserId) {
        String normalizedUsername = required(username, "用户名不能为空");
        String normalizedDisplayName = required(displayName, "显示名称不能为空");
        String roleCode = required(initialRoleCode, "用户角色不能为空");
        if (!"USER".equals(roleCode) && !"ADMIN".equals(roleCode)) {
            throw BusinessException.badRequest("USER_ROLE_INVALID", "不支持的用户角色");
        }
        passwordPolicy.validateForCreation(rawPassword);
        if (userRepository.findByUsername(normalizedUsername).isPresent()) {
            throw BusinessException.conflict("USER_USERNAME_EXISTS", "用户名已存在");
        }

        final long userId;
        try {
            userId = userRepository.insert(normalizedUsername, normalizedDisplayName,
                    passwordPolicy.encode(rawPassword), "ACTIVE");
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("USER_USERNAME_EXISTS", "用户名已存在");
        }
        if (userRepository.assignRole(userId, roleCode) != 1) {
            throw new IllegalStateException("用户默认角色不可用");
        }
        walletService.ensureWalletForUser(userId, normalizedDisplayName);
        if (operatorUserId > 0) {
            auditRepository.record(operatorUserId, "USER_MANAGE", "POST", "/api/admin/users",
                    Long.toString(userId), "SUCCESS", null,
                    "username=" + normalizedUsername + ",displayName=" + normalizedDisplayName,
                    null, Instant.now());
        }
        return userId;
    }

    @Transactional(readOnly = true)
    public List<UserAccount> listUsers(String status) {
        if (status != null && !status.isBlank()
                && !List.of("ACTIVE", "DISABLED", "LOCKED", "DELETED").contains(status)) {
            throw BusinessException.badRequest("USER_STATUS_INVALID", "用户状态无效");
        }
        return userRepository.findByStatus(status);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest("REQUEST_INVALID", message);
        }
        return value.trim();
    }
}
