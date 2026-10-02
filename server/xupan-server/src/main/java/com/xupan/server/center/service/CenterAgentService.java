package com.xupan.server.center.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 中心代理额度三层与中心账本。
 *
 * <p>账本原则 1：可用额度 / 冻结额度 / 上游未结算是**三个不同事实**，分别加锁、分别记账，
 * 绝不能用一个余额字段表示。本类是这三层的唯一写入方。</p>
 *
 * <p>原则 2、3：任何一层变化都追加一条 {@code center_agent_ledger} 流水，
 * 记录该层的变动前后值，使账本可独立验算。</p>
 *
 * <p>原则 9：Token 只保存哈希；创建时返回一次原文，之后任何接口都不再返回。</p>
 */
@Service
public class CenterAgentService {

    /** 额度层。 */
    public static final String BUCKET_AVAILABLE = "AVAILABLE";
    public static final String BUCKET_FROZEN = "FROZEN";
    public static final String BUCKET_UPSTREAM_UNSETTLED = "UPSTREAM_UNSETTLED";

    /** 流水类型。 */
    public static final String TYPE_OPENING = "OPENING_BALANCE";
    public static final String TYPE_CREDIT_GRANT = "CREDIT_GRANT";
    public static final String TYPE_CREDIT_ADJUST = "CREDIT_ADJUST";
    /** 订单被中心接受：可用 → 冻结。 */
    public static final String TYPE_BET_FREEZE = "BET_FREEZE";
    /** 订单被拒绝/超时撤回：冻结 → 可用。 */
    public static final String TYPE_BET_UNFREEZE = "BET_UNFREEZE";
    /** 开奖后：冻结 → 上游未结算。 */
    public static final String TYPE_UPSTREAM_MOVE = "UPSTREAM_MOVE";
    /** 第二层结算：中心赔给代理（代理赢）。 */
    public static final String TYPE_SETTLE_WIN = "SETTLE_WIN";
    /** 第二层结算：中心从代理收（代理输）。 */
    public static final String TYPE_SETTLE_LOSE = "SETTLE_LOSE";
    /** 结算退款/撤销。 */
    public static final String TYPE_SETTLE_REFUND = "SETTLE_REFUND";

    private static final String PERMISSION = "CENTER_AGENT_MANAGE";

    private final CenterAgentRepository repository;
    private final PermissionService permissionService;

    public CenterAgentService(CenterAgentRepository repository, PermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
    }

    /** 中心上游管理权限：与超级后台其它模块一致，在服务层判定。 */
    private void requireManage(Long operator) {
        if (operator == null || operator <= 0 || !permissionService.hasPermission(operator, PERMISSION)) {
            throw BusinessException.forbidden("CENTER_AGENT_FORBIDDEN", "没有中心上游管理权限");
        }
    }

    /** 创建中心代理并返回一次性的 Token 原文。 */
    @Transactional
    public Created create(String agentCode, String agentName, String upstreamAccount, BigDecimal creditLimit,
                          Long operator) {
        requireManage(operator);
        String code = require(agentCode, "CENTER_AGENT_CODE_REQUIRED", "中心代理编号不能为空");
        String name = require(agentName, "CENTER_AGENT_NAME_REQUIRED", "中心代理名称不能为空");
        String account = require(upstreamAccount, "CENTER_UPSTREAM_ACCOUNT_REQUIRED", "上游账号不能为空");
        if (repository.findByUpstreamAccount(account).isPresent()) {
            // 原则 8：共用上游账号会导致余额混合、订单归属不明、风险共用、退款不清
            throw BusinessException.conflict("CENTER_UPSTREAM_ACCOUNT_TAKEN", "该上游账号已被占用");
        }
        BigDecimal limit = nonNegative(creditLimit, "CENTER_CREDIT_LIMIT_INVALID", "信用额度不能为负数");
        CenterTokenService tokenService = new CenterTokenService();
        String token = tokenService.generate();
        long id = repository.insert(code, name, account, tokenService.hash(token),
                tokenService.visiblePrefix(token), limit, operator);
        repository.insertLedgerEntry(id, "CREDIT_LIMIT", TYPE_CREDIT_ADJUST, "IN",
                limit.signum() == 0 ? new BigDecimal("0.01") : limit, BigDecimal.ZERO.setScale(2), limit,
                "创建中心代理时设定信用额度", "CENTER_AGENT", Long.toString(id),
                "CENTER-CREDIT-LIMIT-" + id, operator);
        return new Created(repository.findById(id).orElseThrow(), token);
    }

    @Transactional(readOnly = true)
    public List<CenterAgentRepository.AgentRow> list(long operator) {
        requireManage(operator);
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public CenterAgentRepository.AgentRow require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("CENTER_AGENT_NOT_FOUND", "中心代理不存在"));
    }

    public int changeStatus(long id, String status, Long operator) {
        String normalized = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw BusinessException.badRequest("CENTER_AGENT_STATUS_INVALID", "中心代理状态无效");
        }
        requireManage(operator);
        require(id);
        return repository.updateStatus(id, normalized);
    }

    /**
     * 给可用额度充值（中心给代理加额度，虚拟积分）。
     *
     * @param idempotencyKey 幂等键；重复提交按重放处理，不写第二条流水
     */
    @Transactional
    public BigDecimal grantAvailable(long id, BigDecimal amount, String reason, Long operator,
                                     String idempotencyKey) {
        requireManage(operator);
        BigDecimal positive = positive(amount, "CENTER_GRANT_AMOUNT_INVALID", "充值金额必须大于零");
        return move(id, BUCKET_AVAILABLE, TYPE_CREDIT_GRANT, "IN", positive, reason,
                "CENTER_AGENT", Long.toString(id), idempotencyKey, operator);
    }

    /** 直接调整信用额度。 */
    @Transactional
    public BigDecimal setCreditLimit(long id, BigDecimal limit, Long operator, String idempotencyKey) {
        requireManage(operator);
        BigDecimal target = nonNegative(limit, "CENTER_CREDIT_LIMIT_INVALID", "信用额度不能为负数");
        CenterAgentRepository.AgentRow locked = lock(id);
        if (locked.creditLimit().compareTo(target) == 0) {
            return target;
        }
        boolean up = target.compareTo(locked.creditLimit()) > 0;
        repository.updateBucket(id, "CREDIT_LIMIT", target);
        repository.insertLedgerEntry(id, "CREDIT_LIMIT", TYPE_CREDIT_ADJUST, up ? "IN" : "OUT",
                target.subtract(locked.creditLimit()).abs(), locked.creditLimit(), target,
                "调整信用额度", "CENTER_AGENT", Long.toString(id),
                idempotencyKey + "-LIMIT", operator);
        return target;
    }

    /**
     * 额度三层之间的变动（唯一入口）。
     *
     * <p>先锁行，再按当前值算差额，写回该层，追加一条该层的流水。
     * 同一幂等键命中时按重放返回，不重复记账。</p>
     */
    @Transactional
    public BigDecimal move(long id, String bucket, String entryType, String direction, BigDecimal amount,
                           String reason, String referenceType, String referenceId,
                           String idempotencyKey, Long operator) {
        // 这是三层额度唯一的公开变动入口，自己也要校验权限：
        // 后续中心订单接收（Token 认证）走另一条内部通道，不复用这里的超管权限。
        requireManage(operator);
        CenterAgentRepository.AgentRow locked = lock(id);
        BigDecimal before = bucketValue(locked, bucket);
        Optional<CenterAgentRepository.LedgerRow> replay = repository.findLedgerByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            CenterAgentRepository.LedgerRow row = replay.get();
            if (row.centerAgentId() != id || !row.bucket().equals(bucket)
                    || row.amount().compareTo(amount) != 0 || !row.direction().equals(direction)) {
                throw BusinessException.conflict("CENTER_LEDGER_IDEMPOTENCY_CONFLICT", "中心账本幂等键参数不一致");
            }
            return row.balanceAfter();
        }
        BigDecimal after = "IN".equals(direction) ? before.add(amount) : before.subtract(amount);
        if (after.signum() < 0) {
            throw BusinessException.conflict("CENTER_AVAILABLE_INSUFFICIENT", "中心代理可用额度不足");
        }
        repository.updateBucket(id, bucket, after);
        repository.insertLedgerEntry(id, bucket, entryType, direction, amount, before, after, reason,
                referenceType, referenceId, idempotencyKey, operator);
        return after;
    }

    @Transactional(readOnly = true)
    public List<CenterAgentRepository.LedgerRow> ledger(long id, int page, int pageSize) {
        return repository.findLedgerEntries(id, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long ledgerCount(long id) {
        return repository.countLedgerEntries(id);
    }

    /** 账本与额度三层是否一致（对账用）。 */
    @Transactional(readOnly = true)
    public boolean ledgerBalanced(long id) {
        return repository.countInconsistentBuckets(id) == 0;
    }

    private CenterAgentRepository.AgentRow lock(long id) {
        return repository.lockById(id)
                .orElseThrow(() -> BusinessException.notFound("CENTER_AGENT_NOT_FOUND", "中心代理不存在"));
    }

    static BigDecimal bucketValue(CenterAgentRepository.AgentRow row, String bucket) {
        return switch (bucket) {
            case BUCKET_AVAILABLE -> row.availableBalance();
            case BUCKET_FROZEN -> row.frozenBalance();
            case BUCKET_UPSTREAM_UNSETTLED -> row.upstreamUnsettled();
            default -> throw new IllegalArgumentException("未知额度层：" + bucket);
        };
    }

    private static String require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest(code, message);
        }
        return value.trim();
    }

    private static BigDecimal nonNegative(BigDecimal value, String code, String message) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() < 0) {
            throw BusinessException.badRequest(code, message);
        }
        return normalized;
    }

    private static BigDecimal positive(BigDecimal value, String code, String message) {
        BigDecimal normalized = nonNegative(value, code, message);
        if (normalized.signum() == 0) {
            throw BusinessException.badRequest(code, message);
        }
        return normalized;
    }

    /** 创建结果：账户快照 + **只返回一次**的 Token 原文。 */
    public record Created(CenterAgentRepository.AgentRow agent, String token) {
    }
}
