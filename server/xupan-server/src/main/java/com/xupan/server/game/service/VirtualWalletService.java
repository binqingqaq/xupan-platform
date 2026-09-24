package com.xupan.server.game.service;

import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletOperationResult;
import com.xupan.server.game.domain.WalletStatistics;
import com.xupan.server.game.repository.VirtualWalletRepository;
import com.xupan.server.identity.service.PlayerIdentityService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Service
public class VirtualWalletService {

    private final VirtualWalletRepository walletRepository;
    private final UserRepository userRepository;
    private final OperationAuditRepository auditRepository;
    private final PlayerIdentityService playerIdentityService;

    public VirtualWalletService(VirtualWalletRepository walletRepository,
                                UserRepository userRepository,
                                OperationAuditRepository auditRepository,
                                PlayerIdentityService playerIdentityService) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.playerIdentityService = playerIdentityService;
    }

    @Transactional(readOnly = true)
    public VirtualWallet getForCurrentUser(long authenticatedUserId) {
        requireActiveUser(authenticatedUserId);
        return walletOrNotFound(walletRepository.findByUserId(authenticatedUserId));
    }

    @Transactional(readOnly = true)
    public VirtualWallet getForAdmin(long targetUserId) {
        requireActiveUser(targetUserId);
        return walletOrNotFound(walletRepository.findByUserId(targetUserId));
    }

    /** Admin history views must remain available after a player is soft-deleted. */
    @Transactional(readOnly = true)
    public VirtualWallet getForAdminHistory(long targetUserId) {
        return walletOrNotFound(walletRepository.findByUserId(targetUserId));
    }

    /** Resolves the legacy game_bet.user_id account id back to the owning sys_user id. */
    @Transactional(readOnly = true)
    public VirtualWallet getByAccountId(long accountId) {
        return walletRepository.findByAccountId(accountId)
                .orElseThrow(() -> BusinessException.notFound("WALLET_NOT_FOUND", "虚拟钱包不存在"));
    }

    @Transactional
    public WalletOperationResult grant(long operatorUserId, long targetUserId,
                                       BigDecimal amount, String reason, String idempotencyKey) {
        requireActiveUser(operatorUserId);
        requireActiveUser(targetUserId);
        BigDecimal normalizedAmount = positiveAmount(amount);
        String normalizedReason = requiredText(reason, "分配原因不能为空", 255);
        String normalizedKey = requiredText(idempotencyKey, "幂等键不能为空", 128);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendAdminGrant(operatorUserId,
                targetUserId, normalizedAmount, normalizedReason, normalizedKey));
        WalletOperationResult result = resultFor(targetUserId, ledger);
        auditWalletOperation(operatorUserId, targetUserId, "WALLET_GRANT", "grants", result);
        return result;
    }

    @Transactional
    public WalletOperationResult adjust(long operatorUserId, long targetUserId,
                                        BigDecimal amount, String reason, String idempotencyKey) {
        requireActiveUser(operatorUserId);
        requireActiveUser(targetUserId);
        BigDecimal normalizedAmount = adjustmentAmount(amount);
        String normalizedReason = requiredText(reason, "调整原因不能为空", 255);
        String normalizedKey = requiredText(idempotencyKey, "幂等键不能为空", 128);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendAdminAdjustment(operatorUserId,
                targetUserId, normalizedAmount, normalizedReason, normalizedKey));
        WalletOperationResult result = resultFor(targetUserId, ledger);
        auditWalletOperation(operatorUserId, targetUserId, "WALLET_ADJUST", "adjustments", result);
        return result;
    }

    @Transactional
    public WalletOperationResult reset(long operatorUserId, long targetUserId,
                                       String reason, String idempotencyKey) {
        requireActiveUser(operatorUserId);
        requireActiveUser(targetUserId);
        String normalizedReason = requiredText(reason, "重置原因不能为空", 255);
        String normalizedKey = requiredText(idempotencyKey, "幂等键不能为空", 128);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendAdminReset(operatorUserId,
                targetUserId, normalizedReason, normalizedKey));
        WalletOperationResult result = resultFor(targetUserId, ledger);
        auditWalletOperation(operatorUserId, targetUserId, "WALLET_ADJUST", "resets", result);
        return result;
    }

    @Transactional
    public WalletOperationResult debitForBet(long targetUserId, long betId,
                                             String betCode, String issueNumber,
                                             BigDecimal stake) {
        requireActiveUser(targetUserId);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendBetDebit(targetUserId, betId,
                requiredText(betCode, "注单编号不能为空", 64),
                requiredText(issueNumber, "期号不能为空", 64), positiveAmount(stake)));
        return resultFor(targetUserId, ledger);
    }

    @Transactional
    public WalletOperationResult creditForSettlement(long targetUserId, long betId,
                                                     String issueNumber, BigDecimal amount,
                                                     String reason) {
        BigDecimal normalizedAmount = positiveAmount(amount);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendSettlementCredit(targetUserId, betId,
                requiredText(issueNumber, "期号不能为空", 64), normalizedAmount,
                requiredText(reason, "结算原因不能为空", 255)));
        return resultFor(targetUserId, ledger);
    }

    @Transactional
    public WalletOperationResult refundForCancellation(long targetUserId, long betId,
                                                       String issueNumber, BigDecimal amount) {
        BigDecimal normalizedAmount = positiveAmount(amount);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendCancellationRefund(
                targetUserId, betId, requiredText(issueNumber, "期号不能为空", 64), normalizedAmount));
        return resultFor(targetUserId, ledger);
    }

    @Transactional(readOnly = true)
    public List<WalletLedgerEntry> ledger(long targetUserId, int limit) {
        requireActiveUser(targetUserId);
        if (limit < 1 || limit > 100) {
            throw BusinessException.badRequest("REQUEST_INVALID", "流水条数必须在 1 至 100 之间");
        }
        requireWallet(targetUserId);
        return walletRepository.findLedgerByUserId(targetUserId, limit);
    }

    @Transactional(readOnly = true)
    public List<WalletLedgerEntry> ledgerForAdminHistory(long targetUserId, int limit) {
        if (limit < 1 || limit > 100) {
            throw BusinessException.badRequest("REQUEST_INVALID", "流水条数必须在 1 至 100 之间");
        }
        requireWallet(targetUserId);
        return walletRepository.findLedgerByUserId(targetUserId, limit);
    }

    @Transactional(readOnly = true)
    public WalletStatistics statistics(long targetUserId) {
        requireActiveUser(targetUserId);
        VirtualWallet wallet = requireWallet(targetUserId);
        return walletRepository.findStatisticsByAccountId(wallet.accountId());
    }

    @Transactional(readOnly = true)
    public WalletStatistics statisticsForAdminHistory(long targetUserId) {
        VirtualWallet wallet = requireWallet(targetUserId);
        return walletRepository.findStatisticsByAccountId(wallet.accountId());
    }

    @Transactional
    public long ensureWalletForUser(long userId, String displayName) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "用户不存在"));
        return walletRepository.findByUserId(userId).map(wallet -> {
            playerIdentityService.ensureForUser(userId, wallet.accountId());
            return wallet.accountId();
        }).orElseGet(() -> {
            String code = "USER-" + userId;
            try {
                long accountId = walletRepository.createForUser(userId,
                        code, requiredText(displayName == null ? user.displayName() : displayName,
                                "显示名称不能为空", 128));
                playerIdentityService.ensureForUser(userId, accountId);
                return accountId;
            } catch (DataIntegrityViolationException exception) {
                return walletRepository.findByUserId(userId)
                        .map(wallet -> {
                            playerIdentityService.ensureForUser(userId, wallet.accountId());
                            return wallet.accountId();
                        })
                        .orElseThrow(() -> exception);
            }
        });
    }

    @Transactional
    public long ensureTestWalletForUser(long userId, String userCode, String displayName) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "用户不存在"));
        if (!"TEST".equals(user.userType())) {
            throw BusinessException.conflict("TEST_PLAYER_IDENTITY_INVALID", "目标身份不是测试玩家");
        }
        return walletRepository.findByUserId(userId).map(wallet -> {
            playerIdentityService.ensureForUser(userId, wallet.accountId());
            return wallet.accountId();
        }).orElseGet(() -> {
            try {
                long accountId = walletRepository.createForTestPlayer(userId,
                        requiredText(userCode, "用户编码不能为空", 64),
                        requiredText(displayName == null ? user.displayName() : displayName,
                                "显示名称不能为空", 128));
                playerIdentityService.ensureForUser(userId, accountId);
                return accountId;
            } catch (DataIntegrityViolationException exception) {
                return walletRepository.findByUserId(userId)
                        .map(wallet -> {
                            playerIdentityService.ensureForUser(userId, wallet.accountId());
                            return wallet.accountId();
                        })
                        .orElseThrow(() -> exception);
            }
        });
    }

    private void requireActiveUser(long userId) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("WALLET_NOT_FOUND", "虚拟钱包不存在"));
        if (!"ACTIVE".equals(user.status())) {
            throw BusinessException.conflict("WALLET_INACTIVE", "用户或虚拟钱包已停用");
        }
    }

    private VirtualWallet requireWallet(long userId) {
        return walletOrNotFound(walletRepository.findByUserId(userId));
    }

    private WalletOperationResult resultFor(long targetUserId, WalletLedgerEntry ledger) {
        return new WalletOperationResult(requireWallet(targetUserId), ledger);
    }

    private void auditWalletOperation(long operatorUserId, long targetUserId,
                                      String permission, String action,
                                      WalletOperationResult result) {
        WalletLedgerEntry ledger = result.ledger();
        auditRepository.record(operatorUserId, permission, "POST",
                "/api/admin/users/" + targetUserId + "/wallet/" + action,
                Long.toString(targetUserId), "SUCCESS", null,
                "operationType=" + ledger.operationType() + ",amount=" + ledger.amount()
                        + ",ledgerId=" + ledger.id(), null, Instant.now());
    }

    private static VirtualWallet walletOrNotFound(java.util.Optional<VirtualWallet> wallet) {
        return wallet.orElseThrow(() -> BusinessException.notFound("WALLET_NOT_FOUND", "虚拟钱包不存在"));
    }

    private static WalletLedgerEntry walletOperation(Supplier<WalletLedgerEntry> operation) {
        try {
            return operation.get();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translateWalletFailure(exception);
        }
    }

    private static RuntimeException translateWalletFailure(RuntimeException exception) {
        String message = exception.getMessage();
        String code = message == null ? "" : message.split(":", 2)[0];
        return switch (code) {
            case "WALLET_NOT_FOUND", "WALLET_BET_NOT_FOUND" ->
                    BusinessException.notFound(code, "虚拟钱包或关联记录不存在");
            case "WALLET_INACTIVE" ->
                    BusinessException.conflict(code, "虚拟钱包已停用");
            case "WALLET_INSUFFICIENT_BALANCE", "WALLET_IDEMPOTENCY_CONFLICT",
                 "WALLET_CONCURRENT_UPDATE", "WALLET_BET_MISMATCH", "WALLET_ALREADY_RESET" ->
                    BusinessException.conflict(code, "本次钱包操作无法完成");
            case "WALLET_AMOUNT_INVALID" ->
                    BusinessException.badRequest(code, "金额格式无效");
            default -> exception;
        };
    }

    private static BigDecimal positiveAmount(BigDecimal amount) {
        BigDecimal normalized = normalizedAmount(amount);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("WALLET_AMOUNT_INVALID", "金额必须大于零");
        }
        return normalized;
    }

    private static BigDecimal adjustmentAmount(BigDecimal amount) {
        BigDecimal normalized = normalizedAmount(amount);
        if (normalized.compareTo(BigDecimal.ZERO) == 0) {
            throw BusinessException.badRequest("WALLET_AMOUNT_INVALID", "调整金额不能为零");
        }
        return normalized;
    }

    private static BigDecimal normalizedAmount(BigDecimal amount) {
        if (amount == null || amount.scale() > 2 || amount.precision() - amount.scale() > 16) {
            throw BusinessException.badRequest("WALLET_AMOUNT_INVALID", "金额格式无效");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw BusinessException.badRequest("WALLET_AMOUNT_INVALID", "金额最多保留两位小数");
        }
    }

    private static String requiredText(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw BusinessException.badRequest("REQUEST_INVALID", message);
        }
        return value.trim();
    }
}
