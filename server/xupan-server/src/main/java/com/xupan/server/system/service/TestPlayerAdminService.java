package com.xupan.server.system.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.repository.DemoAccountRepository;
import com.xupan.server.game.service.DemoGameService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.media.AvatarProperties;
import com.xupan.server.media.AvatarStorageService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TestPlayerAdminService {

    private final DemoAccountRepository accountRepository;
    private final UserRepository userRepository;
    private final VirtualWalletService walletService;
    private final PasswordPolicyService passwordPolicy;
    private final PermissionService permissionService;
    private final SessionRepository sessionRepository;
    private final OperationAuditRepository auditRepository;
    private final DemoGameService gameService;
    private final AvatarStorageService avatarStorageService;
    private final AvatarProperties avatarProperties;

    public TestPlayerAdminService(DemoAccountRepository accountRepository,
                                  UserRepository userRepository,
                                  VirtualWalletService walletService,
                                  PasswordPolicyService passwordPolicy,
                                  PermissionService permissionService,
                                  SessionRepository sessionRepository,
                                  OperationAuditRepository auditRepository,
                                  DemoGameService gameService,
                                  AvatarStorageService avatarStorageService,
                                  AvatarProperties avatarProperties) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.walletService = walletService;
        this.passwordPolicy = passwordPolicy;
        this.permissionService = permissionService;
        this.sessionRepository = sessionRepository;
        this.auditRepository = auditRepository;
        this.gameService = gameService;
        this.avatarStorageService = avatarStorageService;
        this.avatarProperties = avatarProperties;
    }

    @Transactional(readOnly = true)
    public TestPlayerPage list(String status, String keyword, int page, int pageSize, long operatorUserId) {
        requireAdmin(operatorUserId);
        validateQuery(status, keyword, page, pageSize);
        List<DemoAccountRepository.TestPlayerRecord> items = accountRepository
                .findTestPlayers(normalizeStatus(status), normalizeKeyword(keyword), page, pageSize);
        return new TestPlayerPage(items, page, pageSize,
                accountRepository.countTestPlayers(normalizeStatus(status), normalizeKeyword(keyword)));
    }

    @Transactional(readOnly = true)
    public TestPlayerAdminView detail(String userCode, long operatorUserId) {
        requireAdmin(operatorUserId);
        DemoAccountRepository.TestPlayerRecord player = requirePlayer(userCode);
        return view(player);
    }

    @Transactional
    public TestPlayerAdminView create(String userCode, String displayName, String avatarKey,
                                      long operatorUserId) {
        requireAdmin(operatorUserId);
        String code = optional(userCode, 64);
        if (code == null) {
            code = generatedUserCode();
        }
        String name = required(displayName, "REQUEST_INVALID", "显示名称不能为空", 128);
        String avatar = optional(avatarKey, 255);
        if (avatar == null && avatarProperties.isAutoGenerate()) {
            avatar = avatarStorageService.storeGenerated(code).avatarKey();
        }
        if (accountRepository.existsByUserCode(code) || userRepository.findByUsername(code).isPresent()) {
            throw BusinessException.conflict("TEST_PLAYER_EXISTS", "测试玩家编码已存在");
        }
        String generatedPassword = "TestPlayer-" + UUID.randomUUID();
        try {
            long userId = userRepository.insertTestPlayer(code, name, avatar,
                    passwordPolicy.encode(generatedPassword));
            if (userRepository.assignRole(userId, "USER") != 1) {
                throw BusinessException.badRequest("TEST_PLAYER_ROLE_INVALID", "测试玩家角色不可用");
            }
            walletService.ensureTestWalletForUser(userId, code, name);
            audit(operatorUserId, "POST", "/api/admin/test-players", Long.toString(userId),
                    "userCode=" + code + ",identityType=TEST");
            return view(requirePlayer(code));
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("TEST_PLAYER_EXISTS", "测试玩家编码已存在");
        }
    }

    @Transactional
    public TestPlayerAdminView changeStatus(String userCode, String status, long operatorUserId) {
        requireAdmin(operatorUserId);
        DemoAccountRepository.TestPlayerRecord player = requirePlayer(userCode);
        String normalizedStatus = normalizeStatus(status);
        if (!List.of("ACTIVE", "DISABLED").contains(normalizedStatus)) {
            throw BusinessException.badRequest("TEST_PLAYER_STATUS_INVALID", "测试玩家状态无效");
        }
        if (!normalizedStatus.equals(player.status()) || !normalizedStatus.equals(player.userStatus())) {
            if (userRepository.updateManagedStatus(player.userId(), normalizedStatus) != 1
                    || accountRepository.updateTestPlayerStatus(player.id(), normalizedStatus) != 1) {
                throw BusinessException.notFound("TEST_PLAYER_NOT_FOUND", "测试玩家不存在");
            }
            sessionRepository.revokeAllActiveByUserId(player.userId(), Instant.now());
            audit(operatorUserId, "PATCH", "/api/admin/test-players/" + player.userCode() + "/status",
                    Long.toString(player.id()), "status=" + normalizedStatus);
        }
        return view(requirePlayer(userCode));
    }

    @Transactional
    public TestPlayerAdminView grant(String userCode, BigDecimal amount, String reason,
                                     String idempotencyKey, long operatorUserId) {
        requireAdmin(operatorUserId);
        DemoAccountRepository.TestPlayerRecord player = requirePlayer(userCode);
        var result = walletService.grant(operatorUserId, player.userId(), amount, reason, idempotencyKey);
        audit(operatorUserId, "POST", "/api/admin/test-players/" + player.userCode() + "/balance/grants",
                Long.toString(player.id()), "ledgerId=" + result.ledger().id());
        return view(requirePlayer(userCode));
    }

    @Transactional
    public TestPlayerAdminView reset(String userCode, String reason, String idempotencyKey,
                                     long operatorUserId) {
        requireAdmin(operatorUserId);
        DemoAccountRepository.TestPlayerRecord player = requirePlayer(userCode);
        var result = walletService.reset(operatorUserId, player.userId(), reason, idempotencyKey);
        audit(operatorUserId, "POST", "/api/admin/test-players/" + player.userCode() + "/balance/reset",
                Long.toString(player.id()), "ledgerId=" + result.ledger().id());
        return view(requirePlayer(userCode));
    }

    @Transactional
    public DemoGameService.BetView placeBet(String userCode, PlaceBetRequest request,
                                             long operatorUserId) {
        requireAdmin(operatorUserId);
        DemoAccountRepository.TestPlayerRecord player = requirePlayer(userCode);
        try {
            if (!"ACTIVE".equals(player.status()) || !"ACTIVE".equals(player.userStatus())) {
                throw BusinessException.conflict("TEST_PLAYER_DISABLED", "测试玩家已停用");
            }
            DemoGameService.BetView result = gameService.placeBetForUser(player.userId(), request);
            audit(operatorUserId, "POST", "/api/admin/test-players/" + player.userCode() + "/bets",
                    Long.toString(player.id()), "betCode=" + result.id());
            return result;
        } catch (RuntimeException exception) {
            auditRepository.record(operatorUserId, "USER_MANAGE", "POST",
                    "/api/admin/test-players/" + player.userCode() + "/bets",
                    Long.toString(player.id()), "FAILURE", "TEST_PLAYER_BET_FAILED", null,
                    null, Instant.now());
            throw exception;
        }
    }

    /** Resolves the target player so a caller can serialize that player's bet flow. */
    public long betTargetUserId(String userCode, long operatorUserId) {
        requireAdmin(operatorUserId);
        return requirePlayer(userCode).userId();
    }

    @Transactional
    public TestPlayerAdminView updateAvatar(String userCode, String avatarKey, long operatorUserId) {
        requireAdmin(operatorUserId);
        DemoAccountRepository.TestPlayerRecord player = requirePlayer(userCode);
        if (avatarKey == null || avatarKey.isBlank() || avatarKey.length() > 255) {
            throw BusinessException.badRequest("AVATAR_FILE_INVALID", "头像标识无效");
        }
        if (userRepository.updateAvatarKey(player.userId(), avatarKey) != 1) {
            throw BusinessException.notFound("TEST_PLAYER_NOT_FOUND", "测试玩家不存在");
        }
        audit(operatorUserId, "PUT", "/api/admin/test-players/" + player.userCode() + "/avatar",
                Long.toString(player.id()), "avatarKey=" + avatarKey);
        return view(requirePlayer(userCode));
    }

    private TestPlayerAdminView view(DemoAccountRepository.TestPlayerRecord player) {
        return new TestPlayerAdminView(player, accountRepository.findLedger(player.userCode()));
    }

    private DemoAccountRepository.TestPlayerRecord requirePlayer(String userCode) {
        String code = required(userCode, "TEST_PLAYER_CODE_INVALID", "用户编码不能为空", 64);
        return accountRepository.findTestPlayerByCode(code)
                .orElseThrow(() -> BusinessException.notFound("TEST_PLAYER_NOT_FOUND", "测试玩家不存在"));
    }

    private void requireAdmin(long operatorUserId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, "USER_MANAGE")) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "没有用户管理权限");
        }
    }

    private void audit(long operatorUserId, String method, String path, String resourceId, String summary) {
        auditRepository.record(operatorUserId, "USER_MANAGE", method, path, resourceId,
                "SUCCESS", null, summary, null, Instant.now());
    }

    private static void validateQuery(String status, String keyword, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100
                || (long) (page - 1) * pageSize > Integer.MAX_VALUE * 100L) {
            throw BusinessException.badRequest("TEST_PLAYER_QUERY_INVALID", "分页参数无效");
        }
        if (status != null && !status.isBlank()
                && !List.of("ACTIVE", "DISABLED").contains(status.trim().toUpperCase(Locale.ROOT))) {
            throw BusinessException.badRequest("TEST_PLAYER_STATUS_INVALID", "测试玩家状态无效");
        }
        if (keyword != null && keyword.trim().length() > 64) {
            throw BusinessException.badRequest("TEST_PLAYER_QUERY_INVALID", "关键字长度不能超过 64 个字符");
        }
    }

    private static String normalizeStatus(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private static String required(String value, String code, String message, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw BusinessException.badRequest(code, message);
        }
        return value.trim();
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.trim().length() > maxLength) {
            throw BusinessException.badRequest("REQUEST_INVALID", "头像标识长度无效");
        }
        return value.trim();
    }

    private String generatedUserCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "BOT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (!accountRepository.existsByUserCode(candidate) && userRepository.findByUsername(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw BusinessException.conflict("TEST_PLAYER_EXISTS", "托编码生成失败，请重试");
    }

    public record TestPlayerPage(List<DemoAccountRepository.TestPlayerRecord> items,
                                 int page, int pageSize, long total) {
    }

    public record TestPlayerAdminView(DemoAccountRepository.TestPlayerRecord player,
                                      List<DemoAccountRepository.LedgerRecord> ledger) {
    }
}
