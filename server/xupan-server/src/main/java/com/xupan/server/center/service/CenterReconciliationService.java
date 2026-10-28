package com.xupan.server.center.service;

import com.xupan.server.center.repository.ReconciliationDispositionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 两侧账目对账（本地 {@code report_upstream_order} ↔ 中心 {@code center_order}）。
 *
 * <p>对应闭环判据（《代理报网盘口与结算业务规则说明》§23.10）："没有超时/退款/对账遗留"。
 * 对账不是"看一眼总数差不多"，而是把**每一笔**上报订单与中心订单一一对上，
 * 并把差异按类别列出来，这样遗留项才能被逐条处理。</p>
 *
 * <p>对齐口径：中心侧以 {@code (center_agent_id, agent_order_no)} 幂等；本地按
 * {@code (bet_id, center_agent_id)} 取最新一次尝试。不同中心账号的同号订单是不同业务事实。</p>
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
        return reconcile(machineId, null, null, limit);
    }

    /** 按机器、中心账号和期号（均可选）核对当前有效尝试及中心账务。 */
    @Transactional(readOnly = true)
    public Report reconcile(Long machineId, Long centerAgentId, String issueNumber, int limit) {
        String normalizedIssue = issueNumber == null || issueNumber.isBlank() ? null : issueNumber.trim();
        List<Difference> missingInCenter = jdbc.query("""
                SELECT o.bet_id AS bet_id, o.status AS local_status, NULL AS center_status,
                       b.stake AS local_amount, NULL AS center_amount,
                       o.center_agent_id AS center_agent_id, b.issue_number AS issue_number
                  FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                 WHERE o.report_type = 'CENTER'
                   AND o.center_agent_id IS NOT NULL
                   AND o.status = 'ACCEPTED'
                   AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                  AND o2.center_agent_id = o.center_agent_id)
                   AND (? IS NULL OR o.agent_id = ?)
                   AND (? IS NULL OR o.center_agent_id = ?)
                   AND (? IS NULL OR b.issue_number = ?)
                   AND NOT EXISTS (SELECT 1 FROM center_order c
                                    WHERE c.center_agent_id = o.center_agent_id
                                      AND c.agent_order_no = CONCAT(?, o.bet_id))
                 ORDER BY o.bet_id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("MISSING_IN_CENTER", rs.getLong("bet_id"),
                rs.getString("local_status"), null, rs.getBigDecimal("local_amount"), null,
                rs.getObject("center_agent_id", Long.class), rs.getString("issue_number")),
                machineId, machineId, centerAgentId, centerAgentId, normalizedIssue, normalizedIssue,
                ORDER_NO_PREFIX, limit);

        List<Difference> missingInLocal = jdbc.query("""
                SELECT c.local_bet_id AS bet_id, NULL AS local_status, c.status AS center_status,
                       NULL AS local_amount, c.amount AS center_amount,
                       c.center_agent_id AS center_agent_id, c.issue_number AS issue_number
                  FROM center_order c
                 WHERE c.local_bet_id IS NOT NULL
                   AND (? IS NULL OR c.local_agent_id = ?)
                   AND (? IS NULL OR c.center_agent_id = ?)
                   AND (? IS NULL OR c.issue_number = ?)
                   AND NOT EXISTS (SELECT 1 FROM report_upstream_order o
                                    WHERE o.bet_id = c.local_bet_id AND o.report_type = 'CENTER'
                                      AND o.center_agent_id = c.center_agent_id
                                      AND c.agent_order_no = CONCAT(?, c.local_bet_id))
                 ORDER BY c.id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("MISSING_IN_LOCAL", rs.getLong("bet_id"),
                null, rs.getString("center_status"), null, rs.getBigDecimal("center_amount"),
                rs.getObject("center_agent_id", Long.class), rs.getString("issue_number")),
                machineId, machineId, centerAgentId, centerAgentId, normalizedIssue, normalizedIssue,
                ORDER_NO_PREFIX, limit);

        // 状态语义映射：本地 ACCEPTED ↔ 中心 ACCEPTED/SETTLED（中心结算后仍是"已被接受"这件事）
        List<Difference> statusMismatch = jdbc.query("""
                SELECT o.bet_id AS bet_id, o.status AS local_status, c.status AS center_status,
                       b.stake AS local_amount, c.amount AS center_amount,
                       o.center_agent_id AS center_agent_id, b.issue_number AS issue_number
                  FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                  JOIN center_order c ON c.center_agent_id = o.center_agent_id
                                     AND c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER'
                   AND o.center_agent_id IS NOT NULL
                   AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                  AND o2.center_agent_id = o.center_agent_id)
                   AND (? IS NULL OR o.agent_id = ?)
                   AND (? IS NULL OR o.center_agent_id = ?)
                   AND (? IS NULL OR b.issue_number = ?)
                   AND NOT (
                        (o.status = 'ACCEPTED' AND c.status IN ('ACCEPTED', 'SETTLED'))
                     OR (o.status = 'REJECTED' AND c.status IN ('REJECTED', 'CANCELLED'))
                     OR (o.status = 'CANCELLED' AND c.status = 'CANCELLED')
                     OR (o.status = 'REFUNDED' AND c.status = 'REFUNDED')
                     OR (o.status = 'TIMEOUT'  AND c.status IN ('PENDING', 'TIMEOUT'))
                     OR (o.status = 'PENDING'  AND c.status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT'))
                   )
                 ORDER BY o.bet_id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("STATUS_MISMATCH", rs.getLong("bet_id"),
                rs.getString("local_status"), rs.getString("center_status"),
                rs.getBigDecimal("local_amount"), rs.getBigDecimal("center_amount"),
                rs.getObject("center_agent_id", Long.class), rs.getString("issue_number")),
                ORDER_NO_PREFIX, machineId, machineId, centerAgentId, centerAgentId,
                normalizedIssue, normalizedIssue, limit);

        List<Difference> amountMismatch = jdbc.query("""
                SELECT o.bet_id AS bet_id, o.status AS local_status, c.status AS center_status,
                       b.stake AS local_amount, c.amount AS center_amount,
                       o.center_agent_id AS center_agent_id, b.issue_number AS issue_number
                  FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                  JOIN center_order c ON c.center_agent_id = o.center_agent_id
                                     AND c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER'
                   AND o.center_agent_id IS NOT NULL
                   AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                  AND o2.center_agent_id = o.center_agent_id)
                   AND (? IS NULL OR o.agent_id = ?)
                   AND (? IS NULL OR o.center_agent_id = ?)
                   AND (? IS NULL OR b.issue_number = ?)
                   AND b.stake <> c.amount
                 ORDER BY o.bet_id
                 LIMIT ?
                """, (rs, rowNum) -> new Difference("AMOUNT_MISMATCH", rs.getLong("bet_id"),
                rs.getString("local_status"), rs.getString("center_status"),
                rs.getBigDecimal("local_amount"), rs.getBigDecimal("center_amount"),
                rs.getObject("center_agent_id", Long.class), rs.getString("issue_number")),
                ORDER_NO_PREFIX, machineId, machineId, centerAgentId, centerAgentId,
                normalizedIssue, normalizedIssue, limit);

        Counts counts = jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM report_upstream_order o
                    WHERE o.report_type = 'CENTER' AND o.center_agent_id IS NOT NULL
                      AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                   WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                     AND o2.center_agent_id = o.center_agent_id)
                      AND (? IS NULL OR o.agent_id = ?)
                      AND (? IS NULL OR o.center_agent_id = ?)
                      AND (? IS NULL OR o.bet_id IN (SELECT b.id FROM game_bet b
                                                      WHERE b.issue_number = ?))) AS local_total,
                  (SELECT COUNT(*) FROM center_order c
                    WHERE (? IS NULL OR c.local_agent_id = ?)
                      AND (? IS NULL OR c.center_agent_id = ?)
                      AND (? IS NULL OR c.issue_number = ?)) AS center_total,
                  (SELECT COUNT(*) FROM report_upstream_order o
                    WHERE o.report_type = 'CENTER' AND o.center_agent_id IS NOT NULL
                      AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                   WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                     AND o2.center_agent_id = o.center_agent_id)
                      AND (? IS NULL OR o.agent_id = ?)
                      AND (? IS NULL OR o.center_agent_id = ?)
                      AND (? IS NULL OR o.bet_id IN (SELECT b.id FROM game_bet b
                                                      WHERE b.issue_number = ?))
                      AND o.status IN ('PENDING', 'TIMEOUT')) AS local_unresolved,
                  (SELECT COUNT(*) FROM center_order c
                    WHERE (? IS NULL OR c.local_agent_id = ?)
                      AND (? IS NULL OR c.center_agent_id = ?)
                      AND (? IS NULL OR c.issue_number = ?)
                      AND c.status IN ('PENDING', 'TIMEOUT')) AS center_unresolved,
                  (SELECT COUNT(*) FROM center_agent WHERE status = 'ACTIVE') AS active_center_agents
                """, (rs, rowNum) -> new Counts(rs.getLong("local_total"), rs.getLong("center_total"),
                rs.getLong("local_unresolved"), rs.getLong("center_unresolved"),
                rs.getLong("active_center_agents")),
                machineId, machineId, centerAgentId, centerAgentId, normalizedIssue, normalizedIssue,
                machineId, machineId, centerAgentId, centerAgentId, normalizedIssue, normalizedIssue,
                machineId, machineId, centerAgentId, centerAgentId, normalizedIssue, normalizedIssue,
                machineId, machineId, centerAgentId, centerAgentId, normalizedIssue, normalizedIssue);

        List<Difference> all = new java.util.ArrayList<>();
        all.addAll(missingInCenter);
        all.addAll(missingInLocal);
        all.addAll(statusMismatch);
        all.addAll(amountMismatch);
        int differenceCount = (int) (countMissingInCenter(machineId, centerAgentId, normalizedIssue)
                + countMissingInLocal(machineId, centerAgentId, normalizedIssue)
                + countStatusMismatch(machineId, centerAgentId, normalizedIssue)
                + countAmountMismatch(machineId, centerAgentId, normalizedIssue));

        // C-2 ②：把"这条差异是否已被人工处置"标在每条差异上。
        // 刻意**不**把已处置的差异从结果里过滤掉——事实不能被隐藏，
        // 但运营需要一眼看出哪些已经看过、哪些还没人管。
        List<Difference> annotated = all.stream().map(item -> item.withDisposed(
                dispositionRepository.isEffectivelyDisposed(item.type(), item.betId(), item.centerAgentId()))).toList();

        FinancialSummary financial = financialSummary(machineId, centerAgentId, normalizedIssue);
        OrderLedgerChecks orderLedgerChecks = checkOrderLedgers(machineId, centerAgentId, normalizedIssue);
        financial = financial.withOrderLedgerChecks(orderLedgerChecks);
        long inconsistentCenterBuckets = countInconsistentCenterBuckets();
        long inconsistentAgentBuckets = countInconsistentAgentBuckets();
        return new Report(counts, financial, differenceCount, annotated,
                missingInCenter.size(), missingInLocal.size(), statusMismatch.size(), amountMismatch.size(),
                inconsistentCenterBuckets, inconsistentAgentBuckets);
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
        Long total = jdbc.queryForObject("""
                WITH sequenced AS (
                    SELECT center_agent_id, bucket, direction, amount, balance_before, balance_after,
                           ROW_NUMBER() OVER (PARTITION BY center_agent_id, bucket ORDER BY id) AS row_no,
                           LAG(balance_after) OVER (PARTITION BY center_agent_id, bucket ORDER BY id) AS prior_after
                      FROM center_agent_ledger
                ),
                invalid_ledger_buckets AS (
                    SELECT center_agent_id, bucket
                      FROM sequenced
                     WHERE (row_no = 1 AND balance_before <> 0)
                        OR (row_no > 1 AND (prior_after IS NULL OR balance_before <> prior_after))
                        OR direction NOT IN ('IN', 'OUT') OR amount <= 0
                        OR balance_after <> CASE direction
                              WHEN 'IN' THEN balance_before + amount
                              WHEN 'OUT' THEN balance_before - amount
                              ELSE NULL
                           END
                     GROUP BY center_agent_id, bucket
                ),
                invalid_account_buckets AS (
                    SELECT a.id AS center_agent_id, 'AVAILABLE' AS bucket
                      FROM center_agent a
                     WHERE a.available_balance <> COALESCE((
                           SELECT l.balance_after FROM center_agent_ledger l
                            WHERE l.center_agent_id = a.id AND l.bucket = 'AVAILABLE'
                            ORDER BY l.id DESC LIMIT 1), 0)
                    UNION ALL
                    SELECT a.id, 'FROZEN'
                      FROM center_agent a
                     WHERE a.frozen_balance <> COALESCE((
                           SELECT l.balance_after FROM center_agent_ledger l
                            WHERE l.center_agent_id = a.id AND l.bucket = 'FROZEN'
                            ORDER BY l.id DESC LIMIT 1), 0)
                    UNION ALL
                    SELECT a.id, 'UPSTREAM_UNSETTLED'
                      FROM center_agent a
                     WHERE a.upstream_unsettled <> COALESCE((
                           SELECT l.balance_after FROM center_agent_ledger l
                            WHERE l.center_agent_id = a.id AND l.bucket = 'UPSTREAM_UNSETTLED'
                            ORDER BY l.id DESC LIMIT 1), 0)
                    UNION ALL
                    SELECT a.id, 'CREDIT_LIMIT'
                      FROM center_agent a
                     WHERE a.credit_limit <> COALESCE((
                           SELECT l.balance_after FROM center_agent_ledger l
                            WHERE l.center_agent_id = a.id AND l.bucket = 'CREDIT_LIMIT'
                            ORDER BY l.id DESC LIMIT 1), 0)
                ),
                inconsistent_buckets AS (
                    SELECT center_agent_id, bucket FROM invalid_ledger_buckets
                    UNION
                    SELECT center_agent_id, bucket FROM invalid_account_buckets
                )
                SELECT COUNT(*) FROM inconsistent_buckets
                """, Long.class);
        return total == null ? 0 : total;
    }

    /**
     * 本地代理账本自检：机器最新流水的变动后余额是否等于当前积分。
     *
     * @return 不一致的机器数；0 表示本地代理账本自洽
     */
    @Transactional(readOnly = true)
    public long countInconsistentAgentBuckets() {
        Long total = jdbc.queryForObject("""
                WITH sequenced AS (
                    SELECT agent_id, direction, amount, balance_before, balance_after,
                           ROW_NUMBER() OVER (PARTITION BY agent_id ORDER BY id) AS row_no,
                           LAG(balance_after) OVER (PARTITION BY agent_id ORDER BY id) AS prior_after
                      FROM agent_ledger
                     WHERE agent_id IS NOT NULL
                ),
                invalid_ledger_agents AS (
                    SELECT agent_id
                      FROM sequenced
                     WHERE (row_no = 1 AND balance_before <> 0)
                        OR (row_no > 1 AND (prior_after IS NULL OR balance_before <> prior_after))
                        OR direction NOT IN ('IN', 'OUT') OR amount <= 0
                        OR balance_after <> CASE direction
                              WHEN 'IN' THEN balance_before + amount
                              WHEN 'OUT' THEN balance_before - amount
                              ELSE NULL
                           END
                     GROUP BY agent_id
                ),
                invalid_account_agents AS (
                    SELECT a.id AS agent_id
                      FROM agent a
                     WHERE a.score <> COALESCE((
                           SELECT l.balance_after FROM agent_ledger l
                            WHERE l.agent_id = a.id ORDER BY l.id DESC LIMIT 1), 0)
                ),
                inconsistent_agents AS (
                    SELECT agent_id FROM invalid_ledger_agents
                    UNION
                    SELECT agent_id FROM invalid_account_agents
                )
                SELECT COUNT(*) FROM inconsistent_agents
                """, Long.class);
        return total == null ? 0 : total;
    }

    /** 中心订单与本地玩家单只有一侧完成结算的数量，供运行指标告警。 */
    @Transactional(readOnly = true)
    public long countOneSidedSettlements() {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_order c
                  LEFT JOIN game_bet b ON b.id = c.local_bet_id
                 WHERE (c.status = 'SETTLED'
                        AND (b.id IS NULL OR b.settlement_status NOT IN ('WIN', 'DRAW', 'LOSE')))
                    OR (c.status = 'ACCEPTED'
                        AND b.settlement_status IN ('WIN', 'DRAW', 'LOSE'))
                """, Long.class);
        return total == null ? 0 : total;
    }

    /** 按当前筛选范围核验风险预留、二层结算及退款/撤单分录。 */
    private FinancialSummary financialSummary(Long machineId, Long centerAgentId, String issueNumber) {
        return jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN c.status = 'ACCEPTED' THEN 1 ELSE 0 END), 0) AS reserved_orders,
                  COALESCE(SUM(CASE WHEN c.status = 'ACCEPTED' THEN c.risk_amount ELSE 0 END), 0) AS reserved_risk,
                  COALESCE(SUM(CASE WHEN c.status = 'ACCEPTED' THEN c.frozen_amount ELSE 0 END), 0) AS center_frozen,
                  COALESCE(SUM(CASE WHEN c.status = 'ACCEPTED' THEN c.machine_funded_amount ELSE 0 END), 0) AS machine_frozen,
                  COALESCE(SUM(CASE WHEN c.status = 'ACCEPTED' AND c.risk_policy_version = 1
                                      AND c.risk_amount <> c.frozen_amount + c.machine_funded_amount
                                    THEN 1 ELSE 0 END), 0) AS reservation_mismatches,
                  COALESCE(SUM(CASE WHEN c.status = 'SETTLED' THEN 1 ELSE 0 END), 0) AS settled_orders,
                  COALESCE(SUM(CASE WHEN c.status = 'SETTLED' THEN c.settle_amount ELSE 0 END), 0) AS settled_amount,
                  COALESCE(SUM(CASE WHEN c.status = 'SETTLED'
                                      AND (b.id IS NULL OR b.settlement_status NOT IN ('WIN', 'DRAW', 'LOSE'))
                                    OR c.status = 'ACCEPTED'
                                      AND b.settlement_status IN ('WIN', 'DRAW', 'LOSE')
                                    THEN 1 ELSE 0 END), 0) AS one_sided_settlements,
                  COALESCE(SUM(CASE WHEN c.status = 'SETTLED' THEN
                    (SELECT COALESCE(SUM(CASE l.direction WHEN 'IN' THEN l.amount ELSE -l.amount END), 0)
                       FROM center_agent_ledger l
                      WHERE l.reference_type = 'CENTER_ORDER' AND l.reference_id = CAST(c.id AS CHAR))
                    ELSE 0 END), 0) AS settlement_ledger_net,
                  COALESCE(SUM(CASE WHEN c.status = 'SETTLED' THEN
                    CASE c.settle_result
                      WHEN 'AGENT_WIN' THEN -LEAST(c.settle_amount, c.frozen_amount)
                      WHEN 'AGENT_LOSE' THEN c.amount
                      ELSE 0
                    END ELSE 0 END), 0) AS settlement_ledger_expected,
                  COALESCE(SUM(CASE WHEN c.status = 'SETTLED' AND
                    (SELECT COALESCE(SUM(CASE l.direction WHEN 'IN' THEN l.amount ELSE -l.amount END), 0)
                       FROM center_agent_ledger l
                      WHERE l.reference_type = 'CENTER_ORDER' AND l.reference_id = CAST(c.id AS CHAR)) <>
                    CASE c.settle_result
                      WHEN 'AGENT_WIN' THEN -LEAST(c.settle_amount, c.frozen_amount)
                      WHEN 'AGENT_LOSE' THEN c.amount
                      ELSE 0
                    END THEN 1 ELSE 0 END), 0) AS settlement_ledger_mismatches,
                  COALESCE(SUM(CASE WHEN c.status = 'REFUNDED' THEN 1 ELSE 0 END), 0) AS refunded_orders,
                  COALESCE(SUM(CASE WHEN c.status = 'REFUNDED' THEN
                    CASE WHEN c.risk_policy_version = 1 THEN c.risk_amount
                         ELSE COALESCE(c.frozen_amount, 0) + COALESCE(c.machine_funded_amount, 0) END
                    ELSE 0 END), 0) AS refunded_risk,
                  COALESCE(SUM(CASE WHEN c.status = 'REFUNDED' THEN
                    (SELECT COALESCE(SUM(d.amount), 0) FROM center_order_disposition d
                      WHERE d.center_order_id = c.id AND d.action = 'REFUND') ELSE 0 END), 0) AS refund_disposition_amount,
                  COALESCE(SUM(CASE WHEN c.status = 'REFUNDED' AND
                    ((SELECT COUNT(*) FROM center_order_disposition d
                       WHERE d.center_order_id = c.id AND d.action = 'REFUND') <> 1 OR
                     (SELECT COALESCE(SUM(d.amount), -1) FROM center_order_disposition d
                       WHERE d.center_order_id = c.id AND d.action = 'REFUND') <>
                       CASE WHEN c.risk_policy_version = 1 THEN c.risk_amount
                            ELSE COALESCE(c.frozen_amount, 0) + COALESCE(c.machine_funded_amount, 0) END)
                    THEN 1 ELSE 0 END), 0) AS refund_mismatches,
                  COALESCE(SUM(CASE WHEN c.status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled_orders,
                  COALESCE(SUM(CASE WHEN c.status = 'CANCELLED' THEN
                    CASE WHEN c.risk_policy_version = 1 THEN c.risk_amount
                         ELSE COALESCE(c.frozen_amount, 0) + COALESCE(c.machine_funded_amount, 0) END
                    ELSE 0 END), 0) AS cancelled_risk,
                  COALESCE(SUM(CASE WHEN c.status = 'CANCELLED' THEN
                    (SELECT COALESCE(SUM(d.amount), 0) FROM center_order_disposition d
                      WHERE d.center_order_id = c.id AND d.action = 'CANCEL') ELSE 0 END), 0) AS cancel_disposition_amount,
                  COALESCE(SUM(CASE WHEN c.status = 'CANCELLED' AND
                    ((SELECT COUNT(*) FROM center_order_disposition d
                       WHERE d.center_order_id = c.id AND d.action = 'CANCEL') <> 1 OR
                     (SELECT COALESCE(SUM(d.amount), -1) FROM center_order_disposition d
                       WHERE d.center_order_id = c.id AND d.action = 'CANCEL') <>
                       CASE WHEN c.risk_policy_version = 1 THEN c.risk_amount
                            ELSE COALESCE(c.frozen_amount, 0) + COALESCE(c.machine_funded_amount, 0) END)
                    THEN 1 ELSE 0 END), 0) AS cancel_mismatches
                  FROM center_order c
                  LEFT JOIN game_bet b ON b.id = c.local_bet_id
                 WHERE (? IS NULL OR c.local_agent_id = ?)
                   AND (? IS NULL OR c.center_agent_id = ?)
                   AND (? IS NULL OR c.issue_number = ?)
                """, (rs, rowNum) -> new FinancialSummary(
                rs.getLong("reserved_orders"), rs.getBigDecimal("reserved_risk"),
                rs.getBigDecimal("center_frozen"), rs.getBigDecimal("machine_frozen"),
                rs.getLong("reservation_mismatches"), rs.getLong("settled_orders"),
                rs.getBigDecimal("settled_amount"), rs.getLong("one_sided_settlements"),
                rs.getBigDecimal("settlement_ledger_net"), rs.getBigDecimal("settlement_ledger_expected"),
                rs.getLong("settlement_ledger_mismatches"), rs.getLong("refunded_orders"),
                rs.getBigDecimal("refunded_risk"), rs.getBigDecimal("refund_disposition_amount"),
                rs.getLong("refund_mismatches"), rs.getLong("cancelled_orders"),
                rs.getBigDecimal("cancelled_risk"), rs.getBigDecimal("cancel_disposition_amount"),
                rs.getLong("cancel_mismatches")), machineId, machineId, centerAgentId, centerAgentId,
                issueNumber, issueNumber);
    }

    /** 核对每笔中心订单预留/释放/结算分录，并核对玩家与中心的结算结果方向。 */
    private OrderLedgerChecks checkOrderLedgers(Long machineId, Long centerAgentId, String issueNumber) {
        List<LedgerOrder> orders = jdbc.query("""
                SELECT c.id, c.local_agent_id, c.local_bet_id, c.status, c.amount, c.frozen_amount,
                       c.machine_funded_amount, c.settle_result, c.settle_amount,
                       b.settlement_status AS local_settlement_status,
                       b.stake AS local_stake, b.net_profit AS local_net_profit
                  FROM center_order c
                  LEFT JOIN game_bet b ON b.id = c.local_bet_id
                 WHERE (? IS NULL OR c.local_agent_id = ?)
                   AND (? IS NULL OR c.center_agent_id = ?)
                   AND (? IS NULL OR c.issue_number = ?)
                """, (rs, rowNum) -> new LedgerOrder(rs.getLong("id"),
                rs.getObject("local_agent_id", Long.class), rs.getObject("local_bet_id", Long.class),
                rs.getString("status"), rs.getBigDecimal("amount"), rs.getBigDecimal("frozen_amount"),
                rs.getBigDecimal("machine_funded_amount"), rs.getString("settle_result"),
                rs.getBigDecimal("settle_amount"), rs.getString("local_settlement_status"),
                rs.getBigDecimal("local_stake"), rs.getBigDecimal("local_net_profit")),
                machineId, machineId, centerAgentId, centerAgentId, issueNumber, issueNumber);

        Map<Long, Map<CenterLedgerKey, LedgerTotals>> centerEntries = new HashMap<>();
        jdbc.query("""
                SELECT c.id AS order_id, l.bucket, l.entry_type, l.direction, l.amount
                  FROM center_order c
                  JOIN center_agent_ledger l
                    ON l.reference_type = 'CENTER_ORDER' AND l.reference_id = CAST(c.id AS CHAR)
                 WHERE (? IS NULL OR c.local_agent_id = ?)
                   AND (? IS NULL OR c.center_agent_id = ?)
                   AND (? IS NULL OR c.issue_number = ?)
                """, rs -> {
            while (rs.next()) {
                merge(centerEntries, rs.getLong("order_id"),
                        new CenterLedgerKey(rs.getString("bucket"), rs.getString("entry_type"),
                                rs.getString("direction")), rs.getBigDecimal("amount"));
            }
            return null;
        }, machineId, machineId, centerAgentId, centerAgentId, issueNumber, issueNumber);

        Map<Long, Map<MachineLedgerKey, LedgerTotals>> machineEntries = new HashMap<>();
        jdbc.query("""
                SELECT c.id AS order_id, l.agent_id, l.entry_type, l.direction, l.amount
                  FROM center_order c
                  JOIN agent_ledger l
                    ON l.reference_type = 'CENTER_ORDER' AND l.reference_id = CAST(c.id AS CHAR)
                 WHERE (? IS NULL OR c.local_agent_id = ?)
                   AND (? IS NULL OR c.center_agent_id = ?)
                   AND (? IS NULL OR c.issue_number = ?)
                """, rs -> {
            while (rs.next()) {
                merge(machineEntries, rs.getLong("order_id"),
                        new MachineLedgerKey(rs.getObject("agent_id", Long.class),
                                rs.getString("entry_type"), rs.getString("direction")), rs.getBigDecimal("amount"));
            }
            return null;
        }, machineId, machineId, centerAgentId, centerAgentId, issueNumber, issueNumber);

        long reservationMismatches = 0;
        long settlementMismatches = 0;
        long refundMismatches = 0;
        long cancelMismatches = 0;
        long machineMismatches = 0;
        long settlementOutcomeMismatches = 0;
        for (LedgerOrder order : orders) {
            Map<CenterLedgerKey, LedgerTotals> expectedCenter = expectedCenterEntries(order);
            Map<MachineLedgerKey, LedgerTotals> expectedMachine = expectedMachineEntries(order);
            Map<CenterLedgerKey, LedgerTotals> actualCenter = centerEntries.getOrDefault(order.id(), Map.of());
            Map<MachineLedgerKey, LedgerTotals> actualMachine = machineEntries.getOrDefault(order.id(), Map.of());

            if (!sameTotals(expectedCenter, actualCenter)) {
                switch (order.status()) {
                    case "ACCEPTED" -> reservationMismatches++;
                    case "REFUNDED" -> refundMismatches++;
                    case "CANCELLED" -> cancelMismatches++;
                    case "SETTLED" -> settlementMismatches++;
                    default -> settlementMismatches++;
                }
            }
            if (!sameTotals(expectedMachine, actualMachine)) {
                machineMismatches++;
            }
            if ("SETTLED".equals(order.status()) && settlementOutcomeMismatch(order)) {
                settlementOutcomeMismatches++;
            }
        }
        return new OrderLedgerChecks(reservationMismatches, settlementMismatches,
                refundMismatches, cancelMismatches, machineMismatches, settlementOutcomeMismatches);
    }

    private static Map<CenterLedgerKey, LedgerTotals> expectedCenterEntries(LedgerOrder order) {
        Map<CenterLedgerKey, LedgerTotals> expected = new HashMap<>();
        BigDecimal centerFunded = nz(order.centerFunded());
        if (!Set.of("ACCEPTED", "SETTLED", "REFUNDED", "CANCELLED").contains(order.status())) {
            return expected;
        }
        addCenterEntry(expected, "AVAILABLE", "BET_FREEZE", "OUT", centerFunded);
        addCenterEntry(expected, "FROZEN", "BET_FREEZE", "IN", centerFunded);

        if ("REFUNDED".equals(order.status()) || "CANCELLED".equals(order.status())) {
            addCenterEntry(expected, "FROZEN", "BET_UNFREEZE", "OUT", centerFunded);
            addCenterEntry(expected, "AVAILABLE", "BET_UNFREEZE", "IN", centerFunded);
        } else if ("SETTLED".equals(order.status())) {
            String entryType = switch (order.settleResult() == null ? "" : order.settleResult()) {
                case "AGENT_WIN" -> "SETTLE_WIN";
                case "AGENT_LOSE" -> "SETTLE_LOSE";
                case "VOID" -> "SETTLE_REFUND";
                default -> null;
            };
            if (centerFunded.signum() > 0) {
                addCenterEntry(expected, "FROZEN", "UPSTREAM_MOVE", "OUT", centerFunded);
                addCenterEntry(expected, "UPSTREAM_UNSETTLED", "UPSTREAM_MOVE", "IN", centerFunded);
                addCenterEntry(expected, "UPSTREAM_UNSETTLED", entryType, "OUT", centerFunded);
            }
            BigDecimal consumed = "AGENT_WIN".equals(order.settleResult())
                    ? nz(order.settleAmount()).min(centerFunded) : BigDecimal.ZERO;
            BigDecimal centerRelease = centerFunded.subtract(consumed).max(BigDecimal.ZERO);
            addCenterEntry(expected, "AVAILABLE", entryType, "IN", centerRelease);
            if ("AGENT_LOSE".equals(order.settleResult())) {
                addCenterEntry(expected, "AVAILABLE", entryType, "IN", nz(order.amount()));
            }
        }
        return expected;
    }

    private static Map<MachineLedgerKey, LedgerTotals> expectedMachineEntries(LedgerOrder order) {
        Map<MachineLedgerKey, LedgerTotals> expected = new HashMap<>();
        BigDecimal machineFunded = nz(order.machineFunded());
        if (!Set.of("ACCEPTED", "SETTLED", "REFUNDED", "CANCELLED").contains(order.status())) {
            return expected;
        }
        addMachineEntry(expected, order.localAgentId(), "CENTER_RISK_RESERVE", "OUT", machineFunded);
        BigDecimal machineRelease = machineFunded;
        if ("SETTLED".equals(order.status()) && "AGENT_WIN".equals(order.settleResult())) {
            BigDecimal totalConsumed = nz(order.settleAmount());
            BigDecimal machineConsumed = totalConsumed.subtract(nz(order.centerFunded())).max(BigDecimal.ZERO);
            machineRelease = machineFunded.subtract(machineConsumed).max(BigDecimal.ZERO);
        }
        if (!"ACCEPTED".equals(order.status())) {
            addMachineEntry(expected, order.localAgentId(), "CENTER_RISK_RELEASE", "IN", machineRelease);
        }
        return expected;
    }

    private static boolean settlementOutcomeMismatch(LedgerOrder order) {
        if (order.localBetId() == null || order.localSettlementStatus() == null) {
            return true;
        }
        String expectedResult;
        BigDecimal expectedAmount;
        switch (order.localSettlementStatus()) {
            case "WIN" -> {
                expectedResult = "AGENT_WIN";
                expectedAmount = order.localNetProfit();
            }
            case "LOSE" -> {
                expectedResult = "AGENT_LOSE";
                expectedAmount = order.localStake();
            }
            case "DRAW" -> {
                expectedResult = "VOID";
                expectedAmount = BigDecimal.ZERO;
            }
            default -> {
                return true;
            }
        }
        return !expectedResult.equals(order.settleResult()) || expectedAmount == null
                || nz(order.settleAmount()).compareTo(expectedAmount) != 0
                || order.amount() == null || order.localStake() == null
                || order.amount().compareTo(order.localStake()) != 0;
    }

    private static void addCenterEntry(Map<CenterLedgerKey, LedgerTotals> entries,
                                       String bucket, String entryType, String direction, BigDecimal amount) {
        if (amount != null && amount.signum() > 0 && entryType != null) {
            entries.merge(new CenterLedgerKey(bucket, entryType, direction), new LedgerTotals(1, amount),
                    LedgerTotals::plus);
        }
    }

    private static void addMachineEntry(Map<MachineLedgerKey, LedgerTotals> entries, Long machineAgentId,
                                        String entryType, String direction, BigDecimal amount) {
        if (amount != null && amount.signum() > 0) {
            entries.merge(new MachineLedgerKey(machineAgentId, entryType, direction),
                    new LedgerTotals(1, amount), LedgerTotals::plus);
        }
    }

    private static <K> void merge(Map<Long, Map<K, LedgerTotals>> entries, long orderId,
                                  K key, BigDecimal amount) {
        entries.computeIfAbsent(orderId, ignored -> new HashMap<>())
                .merge(key, new LedgerTotals(1, amount), LedgerTotals::plus);
    }

    private static <K> boolean sameTotals(Map<K, LedgerTotals> expected, Map<K, LedgerTotals> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        return expected.entrySet().stream().allMatch(entry -> {
            LedgerTotals actualTotals = actual.get(entry.getKey());
            return actualTotals.count() == entry.getValue().count()
                    && actualTotals.amount().compareTo(entry.getValue().amount()) == 0;
        });
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long countMissingInCenter(Long machineId, Long centerAgentId, String issueNumber) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                 WHERE o.report_type = 'CENTER' AND o.center_agent_id IS NOT NULL AND o.status = 'ACCEPTED'
                   AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                  AND o2.center_agent_id = o.center_agent_id)
                   AND (? IS NULL OR o.agent_id = ?)
                   AND (? IS NULL OR o.center_agent_id = ?)
                   AND (? IS NULL OR b.issue_number = ?)
                   AND NOT EXISTS (SELECT 1 FROM center_order c
                                    WHERE c.center_agent_id = o.center_agent_id
                                      AND c.agent_order_no = CONCAT(?, o.bet_id))
                """, Long.class, machineId, machineId, centerAgentId, centerAgentId,
                issueNumber, issueNumber, ORDER_NO_PREFIX);
        return total == null ? 0 : total;
    }

    private long countMissingInLocal(Long machineId, Long centerAgentId, String issueNumber) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_order c
                 WHERE c.local_bet_id IS NOT NULL AND (? IS NULL OR c.local_agent_id = ?)
                   AND (? IS NULL OR c.center_agent_id = ?)
                   AND (? IS NULL OR c.issue_number = ?)
                   AND NOT EXISTS (SELECT 1 FROM report_upstream_order o
                                    WHERE o.bet_id = c.local_bet_id AND o.report_type = 'CENTER'
                                      AND o.center_agent_id = c.center_agent_id
                                      AND c.agent_order_no = CONCAT(?, c.local_bet_id))
                """, Long.class, machineId, machineId, centerAgentId, centerAgentId,
                issueNumber, issueNumber, ORDER_NO_PREFIX);
        return total == null ? 0 : total;
    }

    private long countStatusMismatch(Long machineId, Long centerAgentId, String issueNumber) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                  JOIN center_order c ON c.center_agent_id = o.center_agent_id
                                     AND c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER' AND o.center_agent_id IS NOT NULL
                   AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                  AND o2.center_agent_id = o.center_agent_id)
                   AND (? IS NULL OR o.agent_id = ?)
                   AND (? IS NULL OR o.center_agent_id = ?)
                   AND (? IS NULL OR b.issue_number = ?)
                   AND NOT (
                        (o.status = 'ACCEPTED' AND c.status IN ('ACCEPTED', 'SETTLED'))
                     OR (o.status = 'REJECTED' AND c.status IN ('REJECTED', 'CANCELLED'))
                     OR (o.status = 'CANCELLED' AND c.status = 'CANCELLED')
                     OR (o.status = 'REFUNDED' AND c.status = 'REFUNDED')
                     OR (o.status = 'TIMEOUT'  AND c.status IN ('PENDING', 'TIMEOUT'))
                     OR (o.status = 'PENDING'  AND c.status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT'))
                   )
                """, Long.class, ORDER_NO_PREFIX, machineId, machineId,
                centerAgentId, centerAgentId, issueNumber, issueNumber);
        return total == null ? 0 : total;
    }

    private long countAmountMismatch(Long machineId, Long centerAgentId, String issueNumber) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_upstream_order o
                  JOIN game_bet b ON b.id = o.bet_id
                  JOIN center_order c ON c.center_agent_id = o.center_agent_id
                                     AND c.agent_order_no = CONCAT(?, o.bet_id)
                 WHERE o.report_type = 'CENTER' AND o.center_agent_id IS NOT NULL
                   AND o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2
                                WHERE o2.bet_id = o.bet_id AND o2.report_type = 'CENTER'
                                  AND o2.center_agent_id = o.center_agent_id)
                   AND (? IS NULL OR o.agent_id = ?)
                   AND (? IS NULL OR o.center_agent_id = ?)
                   AND (? IS NULL OR b.issue_number = ?)
                   AND b.stake <> c.amount
                """, Long.class, ORDER_NO_PREFIX, machineId, machineId,
                centerAgentId, centerAgentId, issueNumber, issueNumber);
        return total == null ? 0 : total;
    }

    /** 一条差异。 */
    public record Difference(String type, long betId, String localStatus, String centerStatus,
                             BigDecimal localAmount, BigDecimal centerAmount, Long centerAgentId,
                             String issueNumber, boolean disposed) {

        /** 兼容旧调用点：默认未处置；已处置由 {@link #withDisposed} 标注。 */
        public Difference(String type, long betId, String localStatus, String centerStatus,
                          BigDecimal localAmount, BigDecimal centerAmount) {
            this(type, betId, localStatus, centerStatus, localAmount, centerAmount, null, null, false);
        }

        public Difference(String type, long betId, String localStatus, String centerStatus,
                          BigDecimal localAmount, BigDecimal centerAmount,
                          Long centerAgentId, String issueNumber) {
            this(type, betId, localStatus, centerStatus, localAmount, centerAmount,
                    centerAgentId, issueNumber, false);
        }

        Difference withDisposed(boolean value) {
            return new Difference(type, betId, localStatus, centerStatus, localAmount, centerAmount,
                    centerAgentId, issueNumber, value);
        }
    }

    /** 汇总订单风险、结算、退款和撤单金额，并报告跨账本不一致数。 */
    public record FinancialSummary(long reservedOrders, BigDecimal reservedRiskAmount,
                                   BigDecimal centerFrozenAmount, BigDecimal machineFrozenAmount,
                                   long reservationMismatchCount, long settledOrders,
                                   BigDecimal settledAmount, long oneSidedSettlementCount,
                                   BigDecimal settlementLedgerNetAmount,
                                   BigDecimal settlementLedgerExpectedNetAmount,
                                   long settlementLedgerMismatchCount, long refundedOrders,
                                   BigDecimal refundedRiskAmount, BigDecimal refundDispositionAmount,
                                   long refundMismatchCount, long cancelledOrders,
                                   BigDecimal cancelledRiskAmount, BigDecimal cancelDispositionAmount,
                                   long cancelMismatchCount, long settlementOrderLedgerMismatchCount,
                                   long reservationLedgerMismatchCount,
                                   long refundLedgerMismatchCount, long cancelLedgerMismatchCount,
                                   long machineLedgerMismatchCount, long settlementOutcomeMismatchCount) {

        public FinancialSummary(long reservedOrders, BigDecimal reservedRiskAmount,
                                BigDecimal centerFrozenAmount, BigDecimal machineFrozenAmount,
                                long reservationMismatchCount, long settledOrders,
                                BigDecimal settledAmount, long oneSidedSettlementCount,
                                BigDecimal settlementLedgerNetAmount,
                                BigDecimal settlementLedgerExpectedNetAmount,
                                long settlementLedgerMismatchCount, long refundedOrders,
                                BigDecimal refundedRiskAmount, BigDecimal refundDispositionAmount,
                                long refundMismatchCount, long cancelledOrders,
                                BigDecimal cancelledRiskAmount, BigDecimal cancelDispositionAmount,
                                long cancelMismatchCount) {
            this(reservedOrders, reservedRiskAmount, centerFrozenAmount, machineFrozenAmount,
                    reservationMismatchCount, settledOrders, settledAmount, oneSidedSettlementCount,
                    settlementLedgerNetAmount, settlementLedgerExpectedNetAmount, settlementLedgerMismatchCount,
                    refundedOrders, refundedRiskAmount, refundDispositionAmount, refundMismatchCount,
                    cancelledOrders, cancelledRiskAmount, cancelDispositionAmount, cancelMismatchCount,
                    0, 0, 0, 0, 0, 0);
        }

        FinancialSummary withOrderLedgerChecks(OrderLedgerChecks checks) {
            return new FinancialSummary(reservedOrders, reservedRiskAmount, centerFrozenAmount, machineFrozenAmount,
                    reservationMismatchCount, settledOrders, settledAmount, oneSidedSettlementCount,
                    settlementLedgerNetAmount, settlementLedgerExpectedNetAmount, settlementLedgerMismatchCount,
                    refundedOrders, refundedRiskAmount, refundDispositionAmount, refundMismatchCount,
                    cancelledOrders, cancelledRiskAmount, cancelDispositionAmount, cancelMismatchCount,
                    checks.settlementMismatches(), checks.reservationMismatches(),
                    checks.refundMismatches(), checks.cancelMismatches(),
                    checks.machineMismatches(), checks.settlementOutcomeMismatches());
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
    public record Report(Counts counts, FinancialSummary financial, int differenceCount,
                         List<Difference> differences,
                         int missingInCenterCount, int missingInLocalCount, int statusMismatchCount,
                         int amountMismatchCount, long inconsistentCenterBuckets,
                         long inconsistentAgentBuckets) {

        /** 是否已经对平（无差异）。 */
        public boolean balanced() {
            return differenceCount == 0 && counts.localUnresolved() == 0 && counts.centerUnresolved() == 0
                    && inconsistentCenterBuckets == 0 && inconsistentAgentBuckets == 0
                    && financialIssueCount() == 0;
        }

        /** 账务核对异常总数，参与整体对平判定。 */
        public long financialIssueCount() {
            return financial.reservationMismatchCount() + financial.oneSidedSettlementCount()
                    + financial.settlementLedgerMismatchCount() + financial.refundMismatchCount()
                    + financial.cancelMismatchCount() + financial.settlementOrderLedgerMismatchCount()
                    + financial.reservationLedgerMismatchCount()
                    + financial.refundLedgerMismatchCount() + financial.cancelLedgerMismatchCount()
                    + financial.machineLedgerMismatchCount() + financial.settlementOutcomeMismatchCount();
        }
    }

    private record LedgerOrder(long id, Long localAgentId, Long localBetId, String status,
                               BigDecimal amount, BigDecimal centerFunded, BigDecimal machineFunded,
                               String settleResult, BigDecimal settleAmount,
                               String localSettlementStatus, BigDecimal localStake, BigDecimal localNetProfit) {
    }

    private record CenterLedgerKey(String bucket, String entryType, String direction) {
    }

    private record MachineLedgerKey(Long machineAgentId, String entryType, String direction) {
    }

    private record LedgerTotals(long count, BigDecimal amount) {
        LedgerTotals plus(LedgerTotals other) {
            return new LedgerTotals(count + other.count(), amount.add(other.amount()));
        }
    }

    private record OrderLedgerChecks(long reservationMismatches, long settlementMismatches,
                                     long refundMismatches, long cancelMismatches,
                                     long machineMismatches, long settlementOutcomeMismatches) {
    }
}
