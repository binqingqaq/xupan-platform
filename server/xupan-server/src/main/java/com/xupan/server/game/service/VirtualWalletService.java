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
import com.xupan.server.web.MoneyAmounts;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /** Acquires the player account row lock before quota reads in a bet placement transaction. */
    @Transactional
    public VirtualWallet lockForBet(long authenticatedUserId) {
        requireActiveUser(authenticatedUserId);
        return walletOrNotFound(walletRepository.findByUserIdForUpdate(authenticatedUserId));
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
    public WalletOperationResult adjustForBetEdit(long operatorUserId, long targetUserId, long betId,
                                                  String issueNumber, BigDecimal delta,
                                                  String reason, String idempotencyKey) {
        requireActiveUser(operatorUserId);
        requireActiveUser(targetUserId);
        BigDecimal normalizedDelta = adjustmentAmount(delta);
        String normalizedIssue = requiredText(issueNumber, "期号不能为空", 64);
        String normalizedReason = requiredText(reason, "改单原因不能为空", 255);
        String normalizedKey = requiredText(idempotencyKey, "幂等键不能为空", 128);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendBetEditAdjustment(
                operatorUserId, targetUserId, betId, normalizedIssue, normalizedDelta,
                normalizedReason, normalizedKey));
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

    /**
     * 撤单退款。BY220 撤单会物理删除注单，因此调用方必须在删除前校验注单归属与金额，
     * 并把校验后的 {@code accountId} 一起传入。
     */
    @Transactional
    public WalletOperationResult refundForCancellation(long targetUserId, long betId, long accountId,
                                                       String issueNumber, BigDecimal amount) {
        BigDecimal normalizedAmount = positiveAmount(amount);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendCancellationRefund(
                targetUserId, betId, accountId, requiredText(issueNumber, "期号不能为空", 64), normalizedAmount));
        return resultFor(targetUserId, ledger);
    }

    /** Manual refund after a center cancellation/refund; uses its own durable idempotency key. */
    @Transactional
    public WalletOperationResult refundForCenterDisposition(long operatorUserId, long targetUserId,
                                                             long betId, long accountId, String issueNumber,
                                                             BigDecimal amount, String reason) {
        requireActiveUser(operatorUserId);
        BigDecimal normalizedAmount = positiveAmount(amount);
        String normalizedReason = requiredText(reason, "处理原因不能为空", 255);
        WalletLedgerEntry ledger = walletOperation(() -> walletRepository.appendCenterDispositionRefund(
                operatorUserId, targetUserId, betId, accountId, requiredText(issueNumber, "期号不能为空", 64),
                normalizedAmount, normalizedReason));
        return resultFor(targetUserId, ledger);
    }

    /**
     * 聊天室**下分申请创建时的即时预扣**（BY220 {@code application/common.php:1874-1876}）。
     *
     * <p>BY220 在下分申请建单时就把玩家余额 {@code setDec} 掉，钱先离开玩家余额；同意后归机器池，
     * 拒绝则原路退回。本方法只负责「离开玩家余额」这一步，且必须同时写流水（账本原则 2）。</p>
     *
     * <p>发起人是玩家本人（自助指令），没有人工审批人，流水操作者记为 {@code SYSTEM}。
     * 余额不足时钱包层抛 {@code WALLET_INSUFFICIENT_BALANCE}（调用方在创建申请前已做业务校验并给 400）。</p>
     *
     * <p><b>竞态兜底</b>：调用方的余额预检与本方法的写入之间存在竞态窗口（例如同一瞬间的下注
     * 把余额花掉）。此时本方法抛**同一个 code** {@code WALLET_INSUFFICIENT_BALANCE}
     * （经 {@code translateWalletFailure} 转成 HTTP 409），外层事务整体回滚，
     * 申请行/预扣流水/余额变动都不落地——不变量（不允许负余额）仍然成立。</p>
     */
    @Transactional
    public WalletOperationResult holdForPointRequest(long targetUserId, BigDecimal amount,
                                                     String reason, String idempotencyKey) {
        requireActiveUser(targetUserId);
        BigDecimal normalizedAmount = positiveAmount(amount);
        String normalizedReason = requiredText(reason, "预扣原因不能为空", 255);
        String normalizedKey = requiredText(idempotencyKey, "幂等键不能为空", 128);
        return resultFor(targetUserId, walletOperation(() -> walletRepository.appendPointRequestHold(
                targetUserId, normalizedAmount, normalizedReason, normalizedKey)));
    }

    /**
     * **下分申请被驳回时的退回**（BY220 {@code application/rbt/controller/Index.php:312-314}）。
     *
     * @param operatorUserId 审批人（机器账号 / 子账号 / 超管），写入流水的操作者列
     */
    @Transactional
    public WalletOperationResult refundForPointRequest(long operatorUserId, long targetUserId, BigDecimal amount,
                                                       String reason, String idempotencyKey) {
        requireActiveUser(operatorUserId);
        requireActiveUser(targetUserId);
        BigDecimal normalizedAmount = positiveAmount(amount);
        String normalizedReason = requiredText(reason, "退回原因不能为空", 255);
        String normalizedKey = requiredText(idempotencyKey, "幂等键不能为空", 128);
        return resultFor(targetUserId, walletOperation(() -> walletRepository.appendPointRequestRefund(
                operatorUserId, targetUserId, normalizedAmount, normalizedReason, normalizedKey)));
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

    @Transactional(readOnly = true)
    public Optional<WalletLedgerEntry> findLedgerByIdempotencyKey(String idempotencyKey) {
        return walletRepository.findLedgerByIdempotencyKey(idempotencyKey);
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

    /**
     * 玩家钱包金额的两位小数口径。
     *
     * <p>语义与修复前完全一致（超精度一律 400、禁止静默四舍五入），实现改为复用全站共享校验
     * {@link MoneyAmounts#requireTwoDecimals}，与代理账本（机器/子账号积分）走同一口径，
     * 见计划 §3.5。</p>
     */
    private static BigDecimal normalizedAmount(BigDecimal amount) {
        return MoneyAmounts.requireTwoDecimals(amount, "WALLET_AMOUNT_INVALID",
                "金额格式无效", "金额最多保留两位小数");
    }

    private static String requiredText(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw BusinessException.badRequest("REQUEST_INVALID", message);
        }
        return value.trim();
    }
}
