package com.xupan.server.center.service;

import com.xupan.server.center.repository.ReconciliationDispositionRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 对账差异处置服务（规划 C-2 ②、C-4 ③）。
 *
 * <p><b>核心原则：处置是"记录判断"，不是"修复数据"。</b>
 * 规划明确差异应先人工确认再自动化；如果系统自动去改 {@code center_order} 或账本，
 * 就会在真相未明时改写账本事实——这比差异本身更危险。因此本服务只写
 * {@code center_reconciliation_disposition}，绝不触碰订单、额度或账本。</p>
 *
 * <p><b>保留与清理策略（C-4 ③）</b>：</p>
 * <ul>
 *   <li>记录**只追加、不删除**：它是"差异为什么被认可"的凭据（账本原则 3）。</li>
 *   <li>但处置可以**过期**（{@code effectiveFor}）：到期后差异重新回到待处置状态，
 *       避免"标一次已知就永远不再看"。</li>
 *   <li>撤销也是状态迁移（{@code revoked_at}），不物理删除，判断历史可追溯。</li>
 *   <li>本服务**不提供删除方法**——长期增长只通过归档观察，不通过删数据解决。</li>
 * </ul>
 */
@Service
public class ReconciliationDispositionService {

    /** 允许的差异类型，与 V68 的 CHECK 约束保持一致。 */
    public static final Set<String> DIFFERENCE_TYPES = Set.of(
            "MISSING_IN_CENTER", "MISSING_IN_LOCAL", "STATUS_MISMATCH", "AMOUNT_MISMATCH");

    /** 允许的处置结论，与 V68 的 CHECK 约束保持一致。 */
    public static final Set<String> RESOLUTIONS = Set.of(
            "ACKNOWLEDGED", "ACCEPTED", "FIXED", "IGNORED");

    /** 默认生效时长：7 天。到期后差异重新回到待处置列表。 */
    private static final Duration DEFAULT_EFFECTIVE_FOR = Duration.ofDays(7);

    private final ReconciliationDispositionRepository repository;

    public ReconciliationDispositionService(ReconciliationDispositionRepository repository) {
        this.repository = repository;
    }

    /**
     * 处置一条差异。
     *
     * @param effectiveFor 生效时长；{@code null} 表示永久生效（不推荐，会让差异永久沉默）
     */
    @Transactional
    public DispositionResult dispose(String differenceType, long betId, String resolution,
                                     Long centerAgentId, String reason, Duration effectiveFor,
                                     String idempotencyKey, Long operatorUserId) {
        String type = requireType(differenceType);
        String normalized = requireResolution(resolution);
        if (betId <= 0) {
            throw BusinessException.badRequest("CENTER_RECON_BET_REQUIRED", "注单号必须为正数");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BusinessException.badRequest("CENTER_RECON_KEY_REQUIRED", "处置幂等键不能为空");
        }

        // 幂等：同一幂等键回放首次结果，不写第二行
        Optional<ReconciliationDispositionRepository.Row> existing =
                repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return DispositionResult.from(existing.get(), true);
        }

        Duration effective = effectiveFor == null ? DEFAULT_EFFECTIVE_FOR : effectiveFor;
        Instant expiresAt = effective.isZero() || effective.isNegative() ? null : Instant.now().plus(effective);
        long id = repository.insert(type, betId, normalized, centerAgentId, reason, expiresAt,
                idempotencyKey, operatorUserId);
        ReconciliationDispositionRepository.Row row = repository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("写入对账差异处置后未找到记录：" + id));
        return DispositionResult.from(row, false);
    }

    /** 撤销一条处置：差异重新回到待处置列表，但判断历史保留。 */
    @Transactional
    public DispositionResult revoke(long dispositionId, Long operatorUserId) {
        ReconciliationDispositionRepository.Row row = repository.findHistoryById(dispositionId)
                .orElseThrow(() -> BusinessException.notFound("CENTER_RECON_DISPOSITION_NOT_FOUND",
                        "对账差异处置记录不存在"));
        if (row.revokedAt() != null) {
            // 已撤销：幂等返回，不重复写
            return DispositionResult.from(row, true);
        }
        repository.revoke(dispositionId, operatorUserId);
        return DispositionResult.from(repository.findHistoryById(dispositionId).orElse(row), false);
    }

    /** 当前生效的处置列表（未撤销、未过期）。 */
    @Transactional(readOnly = true)
    public List<ReconciliationDispositionRepository.Row> effectiveDispositions(int limit) {
        if (limit < 1 || limit > 500) {
            throw BusinessException.badRequest("CENTER_RECON_QUERY_INVALID", "limit 必须为 1 到 500");
        }
        return repository.findEffective(limit);
    }

    /** 某笔差异的全部处置历史（含已撤销、已过期——审计要看得全）。 */
    @Transactional(readOnly = true)
    public List<ReconciliationDispositionRepository.Row> history(String differenceType, long betId) {
        return repository.findHistory(requireType(differenceType), betId);
    }

    /**
     * 保留策略观察值：生效中与已失效的处置记录数量。
     *
     * <p>用于运维判断表增长与归档时机。**不提供自动删除**——处置记录是判断凭据。</p>
     */
    @Transactional(readOnly = true)
    public RetentionStats retentionStats() {
        return new RetentionStats(repository.countEffective(), repository.countExpired());
    }

    private static String requireType(String differenceType) {
        if (differenceType == null || differenceType.isBlank()) {
            throw BusinessException.badRequest("CENTER_RECON_TYPE_REQUIRED", "差异类型不能为空");
        }
        String normalized = differenceType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!DIFFERENCE_TYPES.contains(normalized)) {
            throw BusinessException.badRequest("CENTER_RECON_TYPE_INVALID",
                    "差异类型必须是 " + String.join(" / ", DIFFERENCE_TYPES));
        }
        return normalized;
    }

    private static String requireResolution(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            throw BusinessException.badRequest("CENTER_RECON_RESOLUTION_REQUIRED", "处置结论不能为空");
        }
        String normalized = resolution.trim().toUpperCase(java.util.Locale.ROOT);
        if (!RESOLUTIONS.contains(normalized)) {
            throw BusinessException.badRequest("CENTER_RECON_RESOLUTION_INVALID",
                    "处置结论必须是 " + String.join(" / ", RESOLUTIONS));
        }
        return normalized;
    }

    /** 一次处置的结果。 */
    public record DispositionResult(long id, String differenceType, long betId, String resolution,
                                    String reason, Instant expiresAt, Instant revokedAt, boolean duplicate) {
        static DispositionResult from(ReconciliationDispositionRepository.Row row, boolean duplicate) {
            return new DispositionResult(row.id(), row.differenceType(), row.betId(), row.resolution(),
                    row.reason(), row.expiresAt(), row.revokedAt(), duplicate);
        }
    }

    /** 保留策略观察值。 */
    public record RetentionStats(long effective, long expired) {
    }
}
