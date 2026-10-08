package com.xupan.server.center.service;

import com.xupan.server.center.repository.ReconciliationDispositionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 两侧账目对账（本地 {@code report_upstream_order} ↔ 中心 {@code center_order}）。
 *
 * <p>对应闭环判据（《代理报网盘口与结算业务规则说明》§23.10）："没有超时/退款/对账遗留"。
 * 对账不是"看一眼总数差不多"，而是把**每一笔**上报订单与中心订单一一对上，
 * 并把差异按类别列出来，这样遗留项才能被逐条处理。</p>
 *
 * <p>对齐口径：中心侧 {@code agent_order_no = 'BET-' || 本地 bet_id}，因此两边可以按注单一一对应。</p>
 *
 * <p>差异分类：</p>
 * <ul>
 *   <li>{@code MISSING_IN_CENTER}：本地有上报记录，中心没有对应订单（上报未落中心）；</li>
 *   <li>{@code MISSING_IN_LOCAL}：中心有订单，本地没有上报记录（脏数据或串号）；</li>
 *   <li>{@code STATUS_MISMATCH}：两侧状态语义不一致（例如本地 ACCEPTED 而中心 REJECTED）；</li>
 *   <li>{@code AMOUNT_MISMATCH}：两侧金额不一致（最危险的一类，必须逐笔查）。</li>
 * </ul>
 */
@Service
public class CenterReconciliationService {

    /** 中心侧订单号前缀，与 {@code CenterOrderGateway} 保持一致。 */
    public static final String ORDER_NO_PREFIX = "BET-";

    private final JdbcTemplate jdbc;
    private final ReconciliationDispositionRepository dispositionRepository;
    private final ReconciliationDispositionService dispositionService;

    public CenterReconciliationService(JdbcTemplate jdbc,
                                       ReconciliationDispositionRepository dispositionRepository,
                                       ReconciliationDispositionService dispositionService) {
        this.jdbc = jdbc;
        this.dispositionRepository = dispositionRepository;
        this.dispositionService = dispositionService;
    }

    /**
     * 对某一台机器（可选）做一次全量对账。
     *
     * @param machineId 为空时对全部机器
     * @param limit     单类差异最多返回多少条，避免响应过大
     */
    @Transactional(readOnly = true)
    public Report reconcile(Long machineId, int limit) {
        List<Difference> missingInCenter = jdbc.query("""
                SELECT o.bet_id AS bet_id, o.status AS local_status, NULL AS center_status,
                       b.stake AS local_amount, NULL AS center_amount
                  FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                 WHERE o.report_type = 'CENTER'
                   AND o.status = 'ACCEPTED'
                   AND (? IS NULL OR o.agent_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM center_order c
                                    WHERE c.agent_order_no = CONCAT(?, o.bet_id))
                 ORDER BY o.bet_id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("MISSING_IN_CENTER", rs.getLong("bet_id"),
                rs.getString("local_status"), null, rs.getBigDecimal("local_amount"), null),
                machineId, machineId, ORDER_NO_PREFIX, limit);

        List<Difference> missingInLocal = jdbc.query("""
                SELECT c.local_bet_id AS bet_id, NULL AS local_status, c.status AS center_status,
                       NULL AS local_amount, c.amount AS center_amount
                  FROM center_order c
                 WHERE c.local_bet_id IS NOT NULL
                   AND (? IS NULL OR c.local_agent_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM report_upstream_order o
                                    WHERE o.bet_id = c.local_bet_id AND o.report_type = 'CENTER')
                 ORDER BY c.id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("MISSING_IN_LOCAL", rs.getLong("bet_id"),
                null, rs.getString("center_status"), null, rs.getBigDecimal("center_amount")),
                machineId, machineId, limit);

        // 状态语义映射：本地 ACCEPTED ↔ 中心 ACCEPTED/SETTLED（中心结算后仍是"已被接受"这件事）
        List<Difference> statusMismatch = jdbc.query("""
                SELECT o.bet_id AS bet_id, o.status AS local_status, c.status AS center_status,
                       b.stake AS local_amount, c.amount AS center_amount
                  FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                  JOIN center_order c ON c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER'
                   AND (? IS NULL OR o.agent_id = ?)
                   AND NOT (
                        (o.status = 'ACCEPTED' AND c.status IN ('ACCEPTED', 'SETTLED'))
                     OR (o.status = 'REJECTED' AND c.status IN ('REJECTED', 'CANCELLED'))
                     OR (o.status = 'TIMEOUT'  AND c.status IN ('PENDING', 'TIMEOUT'))
                     OR (o.status = 'PENDING'  AND c.status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT'))
                   )
                 ORDER BY o.bet_id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("STATUS_MISMATCH", rs.getLong("bet_id"),
                rs.getString("local_status"), rs.getString("center_status"),
                rs.getBigDecimal("local_amount"), rs.getBigDecimal("center_amount")),
                ORDER_NO_PREFIX, machineId, machineId, limit);

        List<Difference> amountMismatch = jdbc.query("""
                SELECT o.bet_id AS bet_id, o.status AS local_status, c.status AS center_status,
                       b.stake AS local_amount, c.amount AS center_amount
                  FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                  JOIN center_order c ON c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER'
                   AND (? IS NULL OR o.agent_id = ?)
                   AND b.stake <> c.amount
                 ORDER BY o.bet_id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("AMOUNT_MISMATCH", rs.getLong("bet_id"),
                rs.getString("local_status"), rs.getString("center_status"),
                rs.getBigDecimal("local_amount"), rs.getBigDecimal("center_amount")),
                ORDER_NO_PREFIX, machineId, machineId, limit);

        Counts counts = jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM report_upstream_order o
                    WHERE o.report_type = 'CENTER' AND (? IS NULL OR o.agent_id = ?)) AS local_total,
                  (SELECT COUNT(*) FROM center_order c
                    WHERE (? IS NULL OR c.local_agent_id = ?)) AS center_total,
                  (SELECT COUNT(*) FROM report_upstream_order o
                    WHERE o.report_type = 'CENTER' AND (? IS NULL OR o.agent_id = ?)
                      AND o.status IN ('PENDING', 'TIMEOUT')) AS local_unresolved,
                  (SELECT COUNT(*) FROM center_order c
                    WHERE (? IS NULL OR c.local_agent_id = ?)
                      AND c.status IN ('PENDING', 'TIMEOUT')) AS center_unresolved,
                  (SELECT COUNT(*) FROM center_agent WHERE status = 'ACTIVE') AS active_center_agents
                """, (rs, rowNum) -> new Counts(rs.getLong("local_total"), rs.getLong("center_total"),
                rs.getLong("local_unresolved"), rs.getLong("center_unresolved"),
                rs.getLong("active_center_agents")),
                machineId, machineId, machineId, machineId, machineId, machineId, machineId, machineId);

        List<Difference> all = new java.util.ArrayList<>();
        all.addAll(missingInCenter);
        all.addAll(missingInLocal);
        all.addAll(statusMismatch);
        all.addAll(amountMismatch);
        int differenceCount = (int) (countMissingInCenter(machineId) + countMissingInLocal(machineId)
                + countStatusMismatch(machineId) + countAmountMismatch(machineId));

        // C-2 ②：把"这条差异是否已被人工处置"标在每条差异上。
        // 刻意**不**把已处置的差异从结果里过滤掉——事实不能被隐藏，
        // 但运营需要一眼看出哪些已经看过、哪些还没人管。
        List<Difference> annotated = all.stream().map(item -> item.withDisposed(
                dispositionRepository.isEffectivelyDisposed(item.type(), item.betId()))).toList();

        return new Report(counts, differenceCount, annotated,
                missingInCenter.size(), missingInLocal.size(), statusMismatch.size(), amountMismatch.size());
    }

    /**
     * 处置一条对账差异（C-2 ②）。
     *
     * <p><b>只记录判断，不修复数据。</b>规划明确"差异应先人工确认再自动化"，
     * 自动改账会在真相未明时改写账本事实。这里记录"这笔差异被判断为什么、依据是什么、何时失效"。</p>
     *
     * <p>幂等：{@code idempotencyKey} 唯一，重复提交回放首次结果。</p>
     */
    @Transactional
    public ReconciliationDispositionService.DispositionResult disposeDifference(
            String differenceType, long betId, String resolution, Long centerAgentId, String reason,
            java.time.Duration effectiveFor, String idempotencyKey, Long operatorUserId) {
        return dispositionService.dispose(differenceType, betId, resolution, centerAgentId, reason,
                effectiveFor, idempotencyKey, operatorUserId);
    }

    /** 撤销一条差异处置。 */
    @Transactional
    public ReconciliationDispositionService.DispositionResult revokeDifferenceDisposition(
            long dispositionId, Long operatorUserId) {
        return dispositionService.revoke(dispositionId, operatorUserId);
    }

    /** 当前生效的差异处置列表。 */
    @Transactional(readOnly = true)
    public List<ReconciliationDispositionRepository.Row> effectiveDispositions(int limit) {
        return dispositionService.effectiveDispositions(limit);
    }

    /** 保留策略观察值（C-4 ③）。 */
    @Transactional(readOnly = true)
    public ReconciliationDispositionService.RetentionStats retentionStats() {
        return dispositionService.retentionStats();
    }

    /**
     * 账本自检：中心每一层的最新流水是否等于该层当前值。
     *
     * @return 不一致的层数；0 表示中心账本自洽
     */
    @Transactional(readOnly = true)
    public long countInconsistentCenterBuckets() {
        Long bad = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT l.balance_after,
                           CASE l.bucket
                               WHEN 'AVAILABLE' THEN a.available_balance
                               WHEN 'FROZEN' THEN a.frozen_balance
                               WHEN 'UPSTREAM_UNSETTLED' THEN a.upstream_unsettled
                               WHEN 'CREDIT_LIMIT' THEN a.credit_limit
                           END AS current_value
                      FROM center_agent_ledger l
                      JOIN center_agent a ON a.id = l.center_agent_id
                     WHERE l.id IN (SELECT MAX(id) FROM center_agent_ledger GROUP BY center_agent_id, bucket)
                ) t WHERE t.balance_after <> t.current_value
                """, Long.class);
        return bad == null ? 0 : bad;
    }

    /**
     * 本地代理账本自检：机器最新流水的变动后余额是否等于当前积分。
     *
     * @return 不一致的机器数；0 表示本地代理账本自洽
     */
    @Transactional(readOnly = true)
    public long countInconsistentAgentBuckets() {
        Long bad = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT a.score, (SELECT l.balance_after FROM agent_ledger l
                                      WHERE l.agent_id = a.id ORDER BY l.id DESC LIMIT 1) AS latest
                      FROM agent a
                     WHERE EXISTS (SELECT 1 FROM agent_ledger l WHERE l.agent_id = a.id)
                ) t WHERE t.latest <> t.score
                """, Long.class);
        return bad == null ? 0 : bad;
    }

    private long countMissingInCenter(Long machineId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_upstream_order o
                 WHERE o.report_type = 'CENTER' AND o.status = 'ACCEPTED'
                   AND (? IS NULL OR o.agent_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM center_order c
                                    WHERE c.agent_order_no = CONCAT(?, o.bet_id))
                """, Long.class, machineId, machineId, ORDER_NO_PREFIX);
        return total == null ? 0 : total;
    }

    private long countMissingInLocal(Long machineId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_order c
                 WHERE c.local_bet_id IS NOT NULL AND (? IS NULL OR c.local_agent_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM report_upstream_order o
                                    WHERE o.bet_id = c.local_bet_id AND o.report_type = 'CENTER')
                """, Long.class, machineId, machineId);
        return total == null ? 0 : total;
    }

    private long countStatusMismatch(Long machineId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_upstream_order o
                  JOIN center_order c ON c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER' AND (? IS NULL OR o.agent_id = ?)
                   AND NOT (
                        (o.status = 'ACCEPTED' AND c.status IN ('ACCEPTED', 'SETTLED'))
                     OR (o.status = 'REJECTED' AND c.status IN ('REJECTED', 'CANCELLED'))
                     OR (o.status = 'TIMEOUT'  AND c.status IN ('PENDING', 'TIMEOUT'))
                     OR (o.status = 'PENDING'  AND c.status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT'))
                   )
                """, Long.class, ORDER_NO_PREFIX, machineId, machineId);
        return total == null ? 0 : total;
    }

    private long countAmountMismatch(Long machineId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                  JOIN center_order c ON c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER' AND (? IS NULL OR o.agent_id = ?)
                   AND b.stake <> c.amount
                """, Long.class, ORDER_NO_PREFIX, machineId, machineId);
        return total == null ? 0 : total;
    }

    /** 一条差异。 */
    public record Difference(String type, long betId, String localStatus, String centerStatus,
                             BigDecimal localAmount, BigDecimal centerAmount, boolean disposed) {

        /** 兼容旧调用点：默认未处置；已处置由 {@link #withDisposed} 标注。 */
        public Difference(String type, long betId, String localStatus, String centerStatus,
                          BigDecimal localAmount, BigDecimal centerAmount) {
            this(type, betId, localStatus, centerStatus, localAmount, centerAmount, false);
        }

        Difference withDisposed(boolean value) {
            return new Difference(type, betId, localStatus, centerStatus, localAmount, centerAmount, value);
        }
    }

    /** 两侧计数（含未解决项）。 */
    public record Counts(long localTotal, long centerTotal, long localUnresolved, long centerUnresolved,
                         long activeCenterAgents) {
    }

    /**
     * 对账结果。
     *
     * @param differenceCount 差异总数（不受 {@code limit} 截断影响）
     */
    public record Report(Counts counts, int differenceCount, List<Difference> differences,
                         int missingInCenterCount, int missingInLocalCount, int statusMismatchCount,
                         int amountMismatchCount) {

        /** 是否已经对平（无差异）。 */
        public boolean balanced() {
            return differenceCount == 0;
        }
    }
}
