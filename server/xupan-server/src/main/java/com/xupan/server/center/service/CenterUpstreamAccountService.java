package com.xupan.server.center.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.center.repository.CenterUpstreamAccountRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Manages center upstream login accounts and machine-scoped sessions. */
@Service
public class CenterUpstreamAccountService {
    private static final SecureRandom CREDENTIAL_RANDOM = new SecureRandom();
    private static final String CREDENTIAL_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final String MANAGE_PERMISSION = "CENTER_AGENT_MANAGE";
    private static final String MACHINE_LOGIN_AUDIT = "CENTER_MACHINE_LOGIN";
    private static final String INVALID_CREDENTIALS = "CENTER_UPSTREAM_AUTH_INVALID";
    private static final String INVALID_CREDENTIALS_MESSAGE = "中心账号或密码错误";
    private static final String ACCOUNT_PATH = "/api/admin/center/accounts";
    private static final String MACHINE_PATH = "/api/console/report/center";
    private static final String DEFAULT_GENERATED_PASSWORD = "test123456";
    private static final BigDecimal DEFAULT_MAX_SINGLE_AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal DEFAULT_MAX_ISSUE_EXPOSURE = new BigDecimal("5000.00");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private final CenterUpstreamAccountRepository repository;
    private final CenterAgentService centerAgentService;
    private final PermissionService permissionService;
    private final PasswordPolicyService passwordPolicy;
    private final CenterTokenService tokenService;
    private final OperationAuditRepository auditRepository;

    public CenterUpstreamAccountService(CenterUpstreamAccountRepository repository,
                                        CenterAgentService centerAgentService,
                                        PermissionService permissionService,
                                        PasswordPolicyService passwordPolicy,
                                        CenterTokenService tokenService,
                                        OperationAuditRepository auditRepository) {
        this.repository = repository;
        this.centerAgentService = centerAgentService;
        this.permissionService = permissionService;
        this.passwordPolicy = passwordPolicy;
        this.tokenService = tokenService;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public List<AccountView> listAccounts(long operatorUserId) {
        requireManage(operatorUserId);
        Instant now = Instant.now();
        return repository.findAllAccounts().stream().map(row -> accountView(row, now)).toList();
    }

    @Transactional(readOnly = true)
    public AccountView requireManagedAccount(long accountId, long operatorUserId) {
        requireManage(operatorUserId);
        return requireAccountView(accountId, Instant.now());
    }

    @Transactional
    public CreatedAccount createAccount(long groupId, String username, String password,
                                        BigDecimal initialQuota, Instant expiresAt, long operatorUserId) {
        return createAccount(groupId, username, password, initialQuota,
                DEFAULT_MAX_SINGLE_AMOUNT, DEFAULT_MAX_ISSUE_EXPOSURE, expiresAt, operatorUserId);
    }

    @Transactional
    public CreatedAccount createAccount(long groupId, String username, String password,
                                        BigDecimal initialQuota, BigDecimal maxSingleAmount,
                                        BigDecimal maxIssueExposure, Instant expiresAt, long operatorUserId) {
        requireManage(operatorUserId);
        String normalizedUsername = requireUsername(username);
        String passwordHash = encodePassword(password);
        BigDecimal quota = normalizeQuota(initialQuota);
        BigDecimal singleLimit = requirePositiveLimit(maxSingleAmount, "CENTER_RISK_SINGLE_INVALID",
                "单笔上限必须大于零");
        BigDecimal exposureLimit = requirePositiveLimit(maxIssueExposure, "CENTER_RISK_EXPOSURE_INVALID",
                "单期敞口上限必须大于零");
        if (exposureLimit.compareTo(singleLimit) < 0) {
            throw BusinessException.badRequest("CENTER_RISK_LIMIT_CONFLICT", "单期敞口上限不能低于单笔上限");
        }
        Instant now = Instant.now();
        Instant expiry = expiresAt == null ? now.plus(7, ChronoUnit.DAYS) : expiresAt;
        if (!expiry.isAfter(now)) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_EXPIRY_INVALID", "账号到期时间必须晚于当前时间");
        }
        CenterUpstreamAccountRepository.GroupRow group = repository.lockGroup(groupId)
                .orElseThrow(() -> BusinessException.notFound("CENTER_ACCOUNT_GROUP_NOT_FOUND", "代理子账号不存在"));
        if (!"ACTIVE".equals(group.status()) || group.deletedAt() != null) {
            throw BusinessException.conflict("CENTER_ACCOUNT_GROUP_INACTIVE", "只能为有效代理子账号创建中心账号");
        }
        if (repository.accountExistsForGroup(groupId)) {
            throw BusinessException.conflict("CENTER_ACCOUNT_GROUP_TAKEN", "该代理子账号已有中心账号");
        }
        if (repository.usernameExists(normalizedUsername)) {
            throw BusinessException.conflict("CENTER_ACCOUNT_USERNAME_TAKEN", "中心账号名已被使用");
        }

        String unusableToken = tokenService.generate();
        long accountId;
        try {
            accountId = repository.insertAccount(groupId, normalizedUsername, passwordHash,
                    tokenService.hash(unusableToken), CenterTokenService.PREFIX, quota,
                    singleLimit, exposureLimit, expiry, operatorUserId);
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("CENTER_ACCOUNT_UNIQUE_CONFLICT", "中心账号名或子账号绑定已被占用");
        }
        if (quota.signum() > 0) {
            repository.insertOpeningBalance(accountId, quota, operatorUserId);
        }
        AccountView account = requireAccountView(accountId, now);
        audit(operatorUserId, "POST", ACCOUNT_PATH, Long.toString(accountId), "SUCCESS", null,
                "创建中心账号 groupId=" + groupId + " username=" + normalizedUsername
                        + " initialQuota=" + quota + " expiresAt=" + expiry
                        + " maxSingleAmount=" + singleLimit + " maxIssueExposure=" + exposureLimit, null);
        return new CreatedAccount(account);
    }

    @Transactional
    public GeneratedAccount createGeneratedAccount(long groupId, String requestedUsername,
                                                   String requestedPassword, BigDecimal maxSingleAmount,
                                                   BigDecimal maxIssueExposure, BigDecimal initialQuota,
                                                   Instant expiresAt, long operatorUserId) {
        String password = requestedPassword == null || requestedPassword.isBlank()
                ? DEFAULT_GENERATED_PASSWORD : requestedPassword;
        BigDecimal singleLimit = maxSingleAmount == null ? DEFAULT_MAX_SINGLE_AMOUNT : maxSingleAmount;
        BigDecimal exposureLimit = maxIssueExposure == null ? DEFAULT_MAX_ISSUE_EXPOSURE : maxIssueExposure;
        if (requestedUsername != null && !requestedUsername.isBlank()) {
            CreatedAccount created = createAccount(groupId, requestedUsername, password, initialQuota,
                    singleLimit, exposureLimit, expiresAt, operatorUserId);
            return new GeneratedAccount(created.account(), password);
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            String username = "loc" + groupId + "_" + randomCredential(10);
            try {
                return new GeneratedAccount(
                        createAccount(groupId, username, password, initialQuota, singleLimit,
                                exposureLimit, expiresAt, operatorUserId).account(),
                        password);
            } catch (BusinessException exception) {
                if (!"CENTER_ACCOUNT_USERNAME_TAKEN".equals(exception.code())) {
                    throw exception;
                }
            }
        }
        throw BusinessException.conflict("CENTER_ACCOUNT_USERNAME_TAKEN", "中心账号名生成冲突，请重试");
    }

    private static String randomCredential(int length) {
        String value;
        do {
            StringBuilder result = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                result.append(CREDENTIAL_ALPHABET.charAt(CREDENTIAL_RANDOM.nextInt(CREDENTIAL_ALPHABET.length())));
            }
            value = result.toString();
        } while (value.chars().noneMatch(Character::isLetter)
                || value.chars().noneMatch(Character::isDigit));
        return value;
    }

    @Transactional
    public AccountView changeStatus(long accountId, String status, String reason, long operatorUserId) {
        requireManage(operatorUserId);
        String normalizedReason = requireReason(reason);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_STATUS_INVALID", "中心账号状态无效");
        }
        AccountView before = requireAccountView(accountId, Instant.now());
        repository.updateStatus(accountId, normalized);
        if ("DISABLED".equals(normalized)) {
            repository.revokeAccountSessions(accountId, Instant.now(), "ACCOUNT_DISABLED");
        }
        AccountView updated = requireAccountView(accountId, Instant.now());
        audit(operatorUserId, "PATCH", ACCOUNT_PATH + "/" + accountId + "/status", Long.toString(accountId),
                "SUCCESS", null, "reason=" + normalizedReason
                        + ",beforeStatus=" + before.status() + ",afterStatus=" + updated.status(), null);
        return updated;
    }

    @Transactional
    public AccountView renew(long accountId, Instant expiresAt, String reason, long operatorUserId) {
        requireManage(operatorUserId);
        String normalizedReason = requireReason(reason);
        Instant now = Instant.now();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_EXPIRY_INVALID", "新到期时间必须晚于当前时间");
        }
        AccountView before = requireAccountView(accountId, now);
        repository.updateExpiry(accountId, expiresAt);
        repository.revokeAccountSessions(accountId, now, "ACCOUNT_RENEWED");
        AccountView updated = requireAccountView(accountId, now);
        audit(operatorUserId, "PATCH", ACCOUNT_PATH + "/" + accountId + "/expiry", Long.toString(accountId),
                "SUCCESS", null, "reason=" + normalizedReason
                        + ",beforeExpiresAt=" + before.expiresAt() + ",afterExpiresAt=" + updated.expiresAt(), null);
        return updated;
    }

    @Transactional
    public AccountView resetPassword(long accountId, String newPassword, String reason, long operatorUserId) {
        requireManage(operatorUserId);
        String normalizedReason = requireReason(reason);
        String passwordHash = encodePassword(newPassword);
        AccountView before = requireAccountView(accountId, Instant.now());
        repository.updatePassword(accountId, passwordHash);
        repository.revokeAccountSessions(accountId, Instant.now(), "PASSWORD_RESET");
        AccountView updated = requireAccountView(accountId, Instant.now());
        audit(operatorUserId, "POST", ACCOUNT_PATH + "/" + accountId + "/password", Long.toString(accountId),
                "SUCCESS", null, "reason=" + normalizedReason + ",beforeStatus=" + before.status()
                        + ",afterStatus=" + updated.status() + ",passwordChanged=true", null);
        return updated;
    }

    @Transactional
    public AccountView editAccount(long accountId, Instant expiresAt, String newPassword,
                                   BigDecimal additionalQuota, BigDecimal maxSingleAmount,
                                   BigDecimal maxIssueExposure, long operatorUserId) {
        requireManage(operatorUserId);
        Instant now = Instant.now();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_EXPIRY_INVALID", "有效期必须晚于当前时间");
        }
        BigDecimal quota = normalizeAdditionalQuota(additionalQuota);
        String passwordHash = newPassword == null || newPassword.isBlank() ? null : encodePassword(newPassword);
        CenterUpstreamAccountRepository.EditableAccountRow before = repository.lockAccountForEdit(accountId)
                .orElseThrow(() -> BusinessException.notFound("CENTER_ACCOUNT_NOT_FOUND", "中心账号不存在"));

        centerAgentService.setRiskLimits(accountId, maxSingleAmount, maxIssueExposure, operatorUserId);
        if (repository.updateEditableAccount(accountId, expiresAt, passwordHash) != 1) {
            throw BusinessException.conflict("CENTER_ACCOUNT_EDIT_CONFLICT", "中心账号已变化，请刷新后重试");
        }
        boolean passwordChanged = passwordHash != null;
        boolean expiryChanged = !expiresAt.equals(before.expiresAt());
        if (passwordChanged || expiryChanged) {
            repository.revokeAccountSessions(accountId, now, passwordChanged && expiryChanged
                    ? "ACCOUNT_EDITED" : passwordChanged ? "PASSWORD_RESET" : "ACCOUNT_RENEWED");
        }
        if (quota.signum() > 0) {
            centerAgentService.grantAvailable(accountId, quota, "中心账号编辑追加可用额度", operatorUserId,
                    "CENTER-ACCOUNT-EDIT-GRANT-" + accountId + "-" + UUID.randomUUID());
        }

        audit(operatorUserId, "PATCH", ACCOUNT_PATH + "/" + accountId + "/edit", Long.toString(accountId),
                "SUCCESS", null, "expiresAt=" + expiresAt + ",passwordChanged=" + passwordChanged
                        + ",additionalQuota=" + quota + ",maxSingleAmount=" + maxSingleAmount
                        + ",maxIssueExposure=" + maxIssueExposure, null);
        return requireAccountView(accountId, now);
    }

    @Transactional
    public void deleteAccount(long accountId, String reason, long operatorUserId) {
        requireManage(operatorUserId);
        String normalizedReason = requireReason(reason);
        CenterUpstreamAccountRepository.DeletionContext account = repository.lockAccountForDeletion(accountId)
                .orElseThrow(() -> BusinessException.notFound("CENTER_ACCOUNT_NOT_FOUND", "中心账号不存在"));

        long unresolvedAttempts = repository.countUnownedCenterAttempts(account.groupId());
        if (unresolvedAttempts > 0) {
            throw BusinessException.conflict("CENTER_ACCOUNT_DELETE_ATTEMPTS_UNRESOLVED",
                    "该代理子账号存在无法确认中心账号归属的历史报网记录，请先核对后再删除");
        }

        PurgeResult result = purgeAccountData(account, true);

        audit(operatorUserId, "DELETE", ACCOUNT_PATH + "/" + accountId, Long.toString(accountId),
                "SUCCESS", null,
                "reason=" + normalizedReason + ",username=" + account.username()
                        + ",groupId=" + account.groupId() + result.auditCounts()
                        + ",machineBalancesChanged=false", null);
    }

    /** Caller holds the target group lock and has authorized deletion of the entire sub-account. */
    @Transactional
    public Optional<PurgeResult> purgeForSubAccountDeletion(long groupId) {
        Optional<CenterUpstreamAccountRepository.DeletionContext> account =
                repository.lockAccountForGroupDeletion(groupId);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        if (repository.hasCrossGroupReferences(account.get().accountId(), groupId)) {
            throw BusinessException.conflict("CENTER_ACCOUNT_CROSS_GROUP_REFERENCE",
                    "中心账号存在其他子账号的机器或订单引用，请先核对归属");
        }
        return Optional.of(purgeAccountData(account.get(), false));
    }

    private PurgeResult purgeAccountData(CenterUpstreamAccountRepository.DeletionContext account,
                                        boolean preserveLocalData) {
        long accountId = account.accountId();
        repository.updateStatus(accountId, "DISABLED");
        int machineConfigs = repository.clearMachineCenterConfig(accountId, account.groupId());
        int machineSessions = repository.deleteMachineSessions(accountId);
        int markedPlayerBets = preserveLocalData
                ? repository.markBetsWithOnlyDeletedAccountAttempts(accountId) : 0;
        int reportAttempts = repository.deleteReportAttempts(accountId);

        List<Long> orderIds = repository.findCenterOrderIds(accountId);
        int detachedMachineLedgerRows = 0;
        if (preserveLocalData) {
            for (Long orderId : orderIds) {
                detachedMachineLedgerRows += repository.detachMachineLedgerOrderReference(orderId, accountId);
            }
        }

        int orderDispositions = repository.deleteCenterOrderDispositions(accountId);
        int reconciliationDispositions = repository.deleteReconciliationDispositions(accountId);
        int riskHits = repository.deleteRiskHits(accountId);
        int centerLedgerRows = repository.deleteCenterLedger(accountId);
        int centerOrders = repository.deleteCenterOrders(accountId);
        int deleted = repository.deleteAccount(accountId);
        if (deleted != 1) {
            throw BusinessException.conflict("CENTER_ACCOUNT_DELETE_CONFLICT", "中心账号状态已变化，删除未完成");
        }
        return new PurgeResult(accountId, account.username(), centerOrders, centerLedgerRows, reportAttempts,
                machineSessions, markedPlayerBets, orderDispositions, reconciliationDispositions,
                riskHits, machineConfigs, detachedMachineLedgerRows);
    }

    public record PurgeResult(long accountId, String username, int centerOrders, int centerLedgerRows,
                              int reportAttempts, int machineSessions, int markedPlayerBets,
                              int orderDispositions, int reconciliationDispositions, int riskHits,
                              int machineConfigs, int detachedMachineLedgerRows) {
        public String auditCounts() {
            return ",deletedCenterOrders=" + centerOrders + ",deletedCenterLedgerRows=" + centerLedgerRows
                    + ",deletedReportAttempts=" + reportAttempts + ",deletedMachineSessions=" + machineSessions
                    + ",markedPlayerBetsNotToRereport=" + markedPlayerBets
                    + ",deletedOrderDispositions=" + orderDispositions
                    + ",deletedReconciliationDispositions=" + reconciliationDispositions
                    + ",deletedRiskHits=" + riskHits + ",unlinkedMachineConfigs=" + machineConfigs
                    + ",detachedMachineLedgerRows=" + detachedMachineLedgerRows;
        }
    }

    @Transactional(readOnly = true)
    public List<MachineSessionView> machineSessions(long accountId, long operatorUserId) {
        requireManage(operatorUserId);
        AccountView account = requireAccountView(accountId, Instant.now());
        Instant now = Instant.now();
        return repository.findMachineSessions(accountId).stream().map(row -> {
            String sessionStatus;
            if ("DISABLED".equals(account.status())) {
                sessionStatus = "ACCOUNT_DISABLED";
            } else if ("EXPIRED".equals(account.status())) {
                sessionStatus = "ACCOUNT_EXPIRED";
            } else if (!"ACTIVE".equals(row.groupStatus()) || row.groupDeletedAt() != null) {
                sessionStatus = "GROUP_INACTIVE";
            } else if (!"ACTIVE".equals(row.machineStatus()) || row.machineDeletedAt() != null) {
                sessionStatus = "MACHINE_INACTIVE";
            } else if (row.revokedAt() != null) {
                sessionStatus = "REVOKED";
            } else if (row.authenticatedAt() == null) {
                sessionStatus = "NOT_LOGGED_IN";
            } else if (row.sessionExpiresAt() == null || !now.isBefore(row.sessionExpiresAt())) {
                sessionStatus = "EXPIRED";
            } else {
                sessionStatus = "ONLINE";
            }
            return new MachineSessionView(row.machineId(), row.machineCode(), row.machineName(),
                    row.machineStatus(), sessionStatus, row.authenticatedAt(), row.sessionExpiresAt(),
                    row.revokedAt(), row.revokeReason());
        }).toList();
    }

    /** The machine identity comes from the authenticated Xupan principal, never from request fields. */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginView login(long authenticatedMachineUserId, String username, String rawPassword, String ip) {
        Instant now = Instant.now();
        CenterUpstreamAccountRepository.MachineRow machine = repository
                .lockMachineByAccountUserId(authenticatedMachineUserId).orElse(null);
        if (!usableMachine(machine)) {
            passwordPolicy.matchesUnknownUser(rawPassword);
            auditLogin(authenticatedMachineUserId, "FAILURE", INVALID_CREDENTIALS, "machine account unavailable", ip);
            throw invalidCredentials();
        }
        String normalizedUsername = username == null ? "" : username.trim();
        CenterUpstreamAccountRepository.AccountCredential credential = repository
                .lockCredentialsByUsername(normalizedUsername).orElse(null);
        if (credential == null) {
            passwordPolicy.matchesUnknownUser(rawPassword);
            auditLogin(authenticatedMachineUserId, "FAILURE", INVALID_CREDENTIALS, "account unavailable", ip);
            throw invalidCredentials();
        }

        boolean passwordMatches = safeMatches(rawPassword, credential.passwordHash());
        if (credential.lockedUntil() != null && !now.isBefore(credential.lockedUntil())) {
            repository.resetExpiredLoginLock(credential.accountId());
            credential = repository.lockCredentialsByUsername(normalizedUsername).orElse(credential);
        }
        if (credential.lockedUntil() != null && now.isBefore(credential.lockedUntil())) {
            auditLogin(authenticatedMachineUserId, "FAILURE", INVALID_CREDENTIALS, "account locked", ip);
            throw invalidCredentials();
        }

        boolean bindingMatches = machine.groupId() != null && machine.groupId().equals(credential.groupId());
        boolean accountEnabled = "ACTIVE".equals(credential.status()) && credential.expiresAt() != null
                && now.isBefore(credential.expiresAt());
        boolean groupEnabled = "ACTIVE".equals(credential.groupStatus()) && credential.groupDeletedAt() == null;
        if (!passwordMatches) {
            if (bindingMatches && accountEnabled && groupEnabled) {
                int nextFailureCount = credential.failedLoginCount() + 1;
                Instant lockedUntil = passwordPolicy.shouldLock(nextFailureCount)
                        ? passwordPolicy.lockUntil(now) : null;
                repository.recordLoginFailure(credential.accountId(), nextFailureCount, lockedUntil);
            }
            auditLogin(authenticatedMachineUserId, "FAILURE", INVALID_CREDENTIALS, "credentials rejected", ip);
            throw invalidCredentials();
        }
        if (!bindingMatches || !accountEnabled || !groupEnabled) {
            auditLogin(authenticatedMachineUserId, "FAILURE", INVALID_CREDENTIALS, "credentials rejected", ip);
            throw invalidCredentials();
        }

        repository.resetLoginFailures(credential.accountId());
        repository.revokeMachineSession(machine.machineAgentId(), now, "SESSION_REPLACED");
        int updated = repository.updateCurrentSession(credential.accountId(), machine.machineAgentId(),
                now, credential.expiresAt());
        if (updated == 0) {
            repository.insertCurrentSession(credential.accountId(), machine.machineAgentId(),
                    now, credential.expiresAt());
        }
        auditLogin(authenticatedMachineUserId, "SUCCESS", null,
                "accountId=" + credential.accountId() + " machineAgentId=" + machine.machineAgentId(), ip);
        AccountView view = repository.findAccountForMachine(authenticatedMachineUserId)
                .map(row -> accountView(row, now))
                .orElseThrow(() -> invalidCredentials());
        return new LoginView(view, machine.machineAgentId(), now, credential.expiresAt());
    }

    @Transactional
    public void logout(long authenticatedMachineUserId, String ip) {
        Instant now = Instant.now();
        CenterUpstreamAccountRepository.MachineRow machine = repository
                .lockMachineByAccountUserId(authenticatedMachineUserId)
                .filter(CenterUpstreamAccountService::usableMachine)
                .orElseThrow(CenterUpstreamAccountService::invalidCredentials);
        repository.revokeMachineSession(machine.machineAgentId(), now, "MACHINE_LOGOUT");
        auditMachine(authenticatedMachineUserId, "POST", MACHINE_PATH + "/logout", "SUCCESS", null,
                "logout machineAgentId=" + machine.machineAgentId(), ip);
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> currentForMachine(long authenticatedMachineUserId) {
        Instant now = Instant.now();
        return repository.findAccountForMachine(authenticatedMachineUserId)
                .map(row -> accountView(row, now));
    }

    private void requireManage(long operatorUserId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, MANAGE_PERMISSION)) {
            throw BusinessException.forbidden("CENTER_AGENT_FORBIDDEN", "没有中心上游管理权限");
        }
    }

    private AccountView requireAccountView(long accountId, Instant now) {
        return repository.findAccount(accountId).map(row -> accountView(row, now))
                .orElseThrow(() -> BusinessException.notFound("CENTER_ACCOUNT_NOT_FOUND", "中心账号不存在"));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_REASON_REQUIRED", "请填写操作原因");
        }
        String normalized = reason.trim();
        if (normalized.length() > 255) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_REASON_INVALID", "操作原因不能超过255个字符");
        }
        return normalized;
    }

    private static AccountView accountView(CenterUpstreamAccountRepository.AccountRow row, Instant now) {
        if (row.accountId() == null) {
            throw BusinessException.notFound("CENTER_ACCOUNT_NOT_FOUND", "当前机器未绑定中心账号");
        }
        String status = "ACTIVE".equals(row.status()) && (row.expiresAt() == null || !now.isBefore(row.expiresAt()))
                ? "EXPIRED" : row.status();
        return new AccountView(row.accountId(), row.groupId(), row.groupName(), row.username(), status,
                row.expiresAt(), row.availableBalance(), row.frozenBalance(), row.openStake(), row.lastLoginAt(),
                row.online(), row.onlineMachineCount(), row.maxSingleAmount(), row.maxIssueExposure());
    }

    private String encodePassword(String rawPassword) {
        try {
            return passwordPolicy.encode(rawPassword);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_PASSWORD_INVALID", exception.getMessage());
        }
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_USERNAME_REQUIRED", "中心账号名不能为空");
        }
        String normalized = username.trim();
        if (normalized.length() > 64) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_USERNAME_INVALID", "中心账号名不能超过64个字符");
        }
        return normalized;
    }

    private static BigDecimal normalizeQuota(BigDecimal amount) {
        BigDecimal value = amount == null ? ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() < 0 || value.precision() - value.scale() > 16) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_QUOTA_INVALID", "初始额度超出允许范围");
        }
        return value;
    }

    private static BigDecimal normalizeAdditionalQuota(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 0
                || value.precision() - value.scale() > 16) {
            throw BusinessException.badRequest("CENTER_ACCOUNT_EDIT_QUOTA_INVALID", "追加额度必须是非负整数");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal requirePositiveLimit(BigDecimal value, String code, String message) {
        if (value == null || value.signum() <= 0) {
            throw BusinessException.badRequest(code, message);
        }
        BigDecimal normalized = value.setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() <= 0) {
            throw BusinessException.badRequest(code, message);
        }
        return normalized;
    }

    private boolean safeMatches(String rawPassword, String passwordHash) {
        try {
            return passwordHash == null ? passwordPolicy.matchesUnknownUser(rawPassword)
                    : passwordPolicy.matches(rawPassword, passwordHash);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean usableMachine(CenterUpstreamAccountRepository.MachineRow machine) {
        return machine != null && !machine.systemOwned() && machine.deletedAt() == null
                && "ACTIVE".equals(machine.status()) && machine.groupId() != null
                && "ACTIVE".equals(machine.groupStatus()) && machine.groupDeletedAt() == null;
    }

    private static BusinessException invalidCredentials() {
        return BusinessException.unauthorized(INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
    }

    private void auditLogin(long operatorUserId, String result, String errorCode, String summary, String ip) {
        auditMachine(operatorUserId, "POST", MACHINE_PATH + "/login", result, errorCode, summary, ip);
    }

    private void auditMachine(long operatorUserId, String method, String path, String result,
                             String errorCode, String summary, String ip) {
        auditRepository.record(operatorUserId > 0 ? operatorUserId : null, MACHINE_LOGIN_AUDIT,
                method, path, null, result, errorCode, summary, ip, Instant.now());
    }

    private void audit(long operatorUserId, String method, String path, String resourceId,
                       String result, String errorCode, String summary, String ip) {
        auditRepository.record(operatorUserId, MANAGE_PERMISSION, method, path, resourceId,
                result, errorCode, summary, ip, Instant.now());
    }

    public record AccountView(long accountId, Long groupId, String groupName, String username,
                              String status, Instant expiresAt, BigDecimal availableBalance,
                              BigDecimal frozenBalance, BigDecimal openStake, Instant lastLoginAt,
                              boolean online, int onlineMachineCount,
                              BigDecimal maxSingleAmount, BigDecimal maxIssueExposure) {
    }

    public record CreatedAccount(AccountView account) {
    }

    public record GeneratedAccount(AccountView account, String password) {
        @Override
        public String toString() {
            return "GeneratedAccount[account=" + account + ", password=[REDACTED]]";
        }
    }

    public record LoginView(AccountView account, long machineAgentId, Instant authenticatedAt,
                            Instant sessionExpiresAt) {
    }

    public record MachineSessionView(long machineId, String machineCode, String machineName,
                                     String machineStatus, String sessionStatus, Instant authenticatedAt,
                                     Instant expiresAt, Instant revokedAt, String revokeReason) {
    }
}
