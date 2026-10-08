package com.xupan.server.center.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 中心上游代理账户仓储（Flyway V61 {@code center_agent} / {@code center_agent_ledger}）。
 *
 * <p>额度三层是**三个不同事实**（账本原则 1），分列存放、分别加锁：
 * {@code AVAILABLE} 可用额度、{@code FROZEN} 冻结额度、{@code UPSTREAM_UNSETTLED} 上游未结算。</p>
 *
 * <p>Token 只以哈希形式落库（原则 9）：库内存 {@code token_hash}，接口只在创建时返回一次原文。</p>
 */
@Repository
public class CenterAgentRepository {

    private final JdbcTemplate jdbc;

    public CenterAgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String agentCode, String agentName, String upstreamAccount, String tokenHash,
                       String tokenPrefix, BigDecimal creditLimit, Long createdBy) {
        jdbc.update("""
                INSERT INTO center_agent
                    (agent_code, agent_name, upstream_account, token_hash, token_prefix, status,
                     credit_limit, created_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, agentCode, agentName, upstreamAccount, tokenHash, tokenPrefix,
                creditLimit == null ? BigDecimal.ZERO.setScale(2) : creditLimit, createdBy);
        Long id = jdbc.queryForObject("SELECT id FROM center_agent WHERE agent_code = ?", Long.class, agentCode);
        if (id == null) {
            throw new IllegalStateException("写入中心代理后未找到记录");
        }
        return id;
    }

    public Optional<AgentRow> findById(long id) {
        return jdbc.query("""
                SELECT id, agent_code, agent_name, upstream_account, token_prefix, status,
                       credit_limit, available_balance, frozen_balance, upstream_unsettled, created_at, updated_at,
                       max_single_amount, max_issue_exposure, last_reject_code, last_reject_at, reject_count
                  FROM center_agent WHERE id = ?
                """, (rs, rowNum) -> agentRow(rs), id).stream().findFirst();
    }

    public Optional<AgentRow> findByTokenHash(String tokenHash) {
        return jdbc.query("""
                SELECT id, agent_code, agent_name, upstream_account, token_prefix, status,
                       credit_limit, available_balance, frozen_balance, upstream_unsettled, created_at, updated_at,
                       max_single_amount, max_issue_exposure, last_reject_code, last_reject_at, reject_count
                  FROM center_agent WHERE token_hash = ?
                """, (rs, rowNum) -> agentRow(rs), tokenHash).stream().findFirst();
    }

    public Optional<AgentRow> findByUpstreamAccount(String upstreamAccount) {        return jdbc.query("""
                SELECT id, agent_code, agent_name, upstream_account, token_prefix, status,
                       credit_limit, available_balance, frozen_balance, upstream_unsettled, created_at, updated_at,
                       max_single_amount, max_issue_exposure, last_reject_code, last_reject_at, reject_count
                  FROM center_agent WHERE upstream_account = ?
                """, (rs, rowNum) -> agentRow(rs), upstreamAccount).stream().findFirst();
    }

    public List<AgentRow> findAll() {        return jdbc.query("""
                SELECT id, agent_code, agent_name, upstream_account, token_prefix, status,
                       credit_limit, available_balance, frozen_balance, upstream_unsettled, created_at, updated_at,
                       max_single_amount, max_issue_exposure, last_reject_code, last_reject_at, reject_count
                  FROM center_agent ORDER BY id
                """, (rs, rowNum) -> agentRow(rs));
    }

    /** 该代理的 token 哈希（常量时间比较用；不向上暴露给接口层）。 */
    public String findTokenHash(long centerAgentId) {
        return jdbc.query("SELECT token_hash FROM center_agent WHERE id = ?",
                (rs, rowNum) -> rs.getString("token_hash"), centerAgentId).stream().findFirst().orElse(null);
    }

    public int updateStatus(long id, String status) {
        return jdbc.update("""
                UPDATE center_agent SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, status, id);
    }

    /**
     * 锁定中心代理的一行（额度三层共用这一把行锁）。
     *
     * <p>"读三层 → 算差额 → 写三层 → 写流水"必须在同一把锁内完成，否则并发下两个请求
     * 会各自基于旧值写流水，导致账本与额度不一致。</p>
     */
    public Optional<AgentRow> lockById(long id) {
        return jdbc.query("""
                SELECT id, agent_code, agent_name, upstream_account, token_prefix, status,
                       credit_limit, available_balance, frozen_balance, upstream_unsettled, created_at, updated_at,
                       max_single_amount, max_issue_exposure, last_reject_code, last_reject_at, reject_count
                  FROM center_agent WHERE id = ? FOR UPDATE
                """, (rs, rowNum) -> agentRow(rs), id).stream().findFirst();
    }

    /** 写入额度三层中的一层（其它层保持不动）。 */
    public int updateBucket(long id, String bucket, BigDecimal value) {
        String column = switch (bucket) {
            case "AVAILABLE" -> "available_balance";
            case "FROZEN" -> "frozen_balance";
            case "UPSTREAM_UNSETTLED" -> "upstream_unsettled";
            case "CREDIT_LIMIT" -> "credit_limit";
            default -> throw new IllegalArgumentException("未知额度层：" + bucket);
        };
        return jdbc.update("UPDATE center_agent SET " + column
                + " = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", value, id);
    }

    /** 设置代理级风控上限；{@code null} 表示回退到全局默认。 */
    public int updateRiskLimits(long id, BigDecimal maxSingleAmount, BigDecimal maxIssueExposure) {
        return jdbc.update("""
                UPDATE center_agent
                   SET max_single_amount = ?, max_issue_exposure = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, maxSingleAmount, maxIssueExposure, id);
    }

    /**
     * 记录一次风控命中：追加风控记录行 + 更新代理上的"最近拒绝"摘要与计数。
     *
     * <p>两件事必须一起做：明细表用于按规则聚合与时间序列分析，摘要列用于管理端列表
     * 直接展示而不用逐单回查。</p>
     */
    public void recordRiskHit(long centerAgentId, String ruleCode, String ruleCategory,
                              BigDecimal observedValue, BigDecimal thresholdValue,
                              String gameCode, String issueNumber, String agentOrderNo, String detail) {
        jdbc.update("""
                INSERT INTO center_risk_hit
                    (center_agent_id, rule_code, rule_category, observed_value, threshold_value,
                     game_code, issue_number, agent_order_no, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, centerAgentId, ruleCode, ruleCategory, observedValue, thresholdValue,
                gameCode, issueNumber, agentOrderNo, detail);
        jdbc.update("""
                UPDATE center_agent
                   SET last_reject_code = ?, last_reject_at = CURRENT_TIMESTAMP,
                       reject_count = reject_count + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, ruleCode, centerAgentId);
    }

    /** 某个代理在时间窗口内的命中次数（异常行为识别的基础查询）。 */
    public long countRiskHitsSince(long centerAgentId, Instant since) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_risk_hit
                 WHERE center_agent_id = ? AND created_at >= ?
                """, Long.class, centerAgentId, Timestamp.from(since));
        return total == null ? 0 : total;
    }

    /** 某代理在时间窗口内、指定规则的命中次数。 */
    public long countRiskHitsSince(long centerAgentId, String ruleCode, Instant since) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_risk_hit
                 WHERE center_agent_id = ? AND rule_code = ? AND created_at >= ?
                """, Long.class, centerAgentId, ruleCode, Timestamp.from(since));
        return total == null ? 0 : total;
    }

    /** 某代理最近的风控命中记录（管理端展示）。 */
    public List<RiskHitRow> findRecentRiskHits(long centerAgentId, int limit) {
        return jdbc.query("""
                SELECT id, center_agent_id, rule_code, rule_category, observed_value, threshold_value,
                       game_code, issue_number, agent_order_no, detail, created_at
                  FROM center_risk_hit
                 WHERE center_agent_id = ?
                 ORDER BY id DESC LIMIT ?
                """, (rs, rowNum) -> new RiskHitRow(rs.getLong("id"), rs.getLong("center_agent_id"),
                rs.getString("rule_code"), rs.getString("rule_category"),
                rs.getBigDecimal("observed_value"), rs.getBigDecimal("threshold_value"),
                rs.getString("game_code"), rs.getString("issue_number"), rs.getString("agent_order_no"),
                rs.getString("detail"), instant(rs.getTimestamp("created_at"))), centerAgentId, limit);
    }

    /** 全量风控命中按规则聚合（管理端"风险"页用）。 */
    public List<RiskRuleCount> countRiskHitsByRule(Instant since) {
        return jdbc.query("""
                SELECT rule_code, rule_category, COUNT(*) AS hit_count,
                       COUNT(DISTINCT center_agent_id) AS agent_count
                  FROM center_risk_hit
                 WHERE created_at >= ?
                 GROUP BY rule_code, rule_category
                 ORDER BY hit_count DESC, rule_code
                """, (rs, rowNum) -> new RiskRuleCount(rs.getString("rule_code"),
                rs.getString("rule_category"), rs.getLong("hit_count"), rs.getLong("agent_count")),
                Timestamp.from(since));
    }

    public long insertLedgerEntry(long centerAgentId, String bucket, String entryType, String direction,
                                  BigDecimal amount, BigDecimal before, BigDecimal after, String reason,
                                  String referenceType, String referenceId, String idempotencyKey,
                                  Long operatorUserId) {
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                     reason, reference_type, reference_id, idempotency_key, operator_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, centerAgentId, bucket, entryType, direction, amount, before, after, reason,
                referenceType, referenceId, idempotencyKey, operatorUserId);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM center_agent_ledger WHERE idempotency_key = ?", Long.class, idempotencyKey);
    }

    public Optional<LedgerRow> findLedgerByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
                SELECT id, center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                       reason, reference_type, reference_id, idempotency_key, operator_user_id, created_at
                  FROM center_agent_ledger WHERE idempotency_key = ?
                """, (rs, rowNum) -> ledgerRow(rs), idempotencyKey).stream().findFirst();
    }

    public List<LedgerRow> findLedgerEntries(long centerAgentId, int page, int pageSize) {
        return jdbc.query("""
                SELECT id, center_agent_id, bucket, entry_type, direction, amount, balance_before, balance_after,
                       reason, reference_type, reference_id, idempotency_key, operator_user_id, created_at
                  FROM center_agent_ledger WHERE center_agent_id = ?
                 ORDER BY id DESC LIMIT ? OFFSET ?
                """, (rs, rowNum) -> ledgerRow(rs), centerAgentId, pageSize, (long) (page - 1) * pageSize);
    }

    public long countLedgerEntries(long centerAgentId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?", Long.class, centerAgentId);
        return total == null ? 0 : total;
    }

    /**
     * 账本验算：每一层"最新一条流水的变动后余额"都应等于该层当前值。
     *
     * @return 不一致的层数；0 表示账本与额度一致
     */
    public long countInconsistentBuckets(long centerAgentId) {
        Long bad = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT l.bucket, l.balance_after,
                           CASE l.bucket
                               WHEN 'AVAILABLE' THEN a.available_balance
                               WHEN 'FROZEN' THEN a.frozen_balance
                               WHEN 'UPSTREAM_UNSETTLED' THEN a.upstream_unsettled
                               WHEN 'CREDIT_LIMIT' THEN a.credit_limit
                           END AS current_value
                      FROM center_agent_ledger l
                      JOIN center_agent a ON a.id = l.center_agent_id
                     WHERE l.center_agent_id = ?
                       AND l.id IN (SELECT MAX(id) FROM center_agent_ledger
                                     WHERE center_agent_id = ? GROUP BY bucket)
                ) t
                 WHERE t.balance_after <> t.current_value
                """, Long.class, centerAgentId, centerAgentId);
        return bad == null ? 0 : bad;
    }

    private static AgentRow agentRow(ResultSet rs) throws SQLException {
        return new AgentRow(rs.getLong("id"), rs.getString("agent_code"), rs.getString("agent_name"),
                rs.getString("upstream_account"), rs.getString("token_prefix"), rs.getString("status"),
                rs.getBigDecimal("credit_limit"), rs.getBigDecimal("available_balance"),
                rs.getBigDecimal("frozen_balance"), rs.getBigDecimal("upstream_unsettled"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                rs.getBigDecimal("max_single_amount"), rs.getBigDecimal("max_issue_exposure"),
                rs.getString("last_reject_code"), instant(rs.getTimestamp("last_reject_at")),
                rs.getLong("reject_count"));
    }

    private static LedgerRow ledgerRow(ResultSet rs) throws SQLException {
        return new LedgerRow(rs.getLong("id"), rs.getLong("center_agent_id"), rs.getString("bucket"),
                rs.getString("entry_type"), rs.getString("direction"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_before"), rs.getBigDecimal("balance_after"), rs.getString("reason"),
                rs.getString("reference_type"), rs.getString("reference_id"), rs.getString("idempotency_key"),
                rs.getObject("operator_user_id", Long.class), instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 一个中心代理身份（不含 token 原文，只含前缀与哈希存在性）。 */
    public record AgentRow(long id, String agentCode, String agentName, String upstreamAccount,
                           String tokenPrefix, String status, BigDecimal creditLimit,
                           BigDecimal availableBalance, BigDecimal frozenBalance,
                           BigDecimal upstreamUnsettled, Instant createdAt, Instant updatedAt,
                           BigDecimal maxSingleAmount, BigDecimal maxIssueExposure,
                           String lastRejectCode, Instant lastRejectAt, long rejectCount) {
    }

    /** 一行中心账本流水。 */
    public record LedgerRow(long id, long centerAgentId, String bucket, String entryType, String direction,
                            BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter, String reason,
                            String referenceType, String referenceId, String idempotencyKey,
                            Long operatorUserId, Instant createdAt) {
    }

    /** 一次风控命中。 */
    public record RiskHitRow(long id, long centerAgentId, String ruleCode, String ruleCategory,
                             BigDecimal observedValue, BigDecimal thresholdValue, String gameCode,
                             String issueNumber, String agentOrderNo, String detail, Instant createdAt) {
    }

    /** 按规则聚合的风控命中统计。 */
    public record RiskRuleCount(String ruleCode, String ruleCategory, long hitCount, long agentCount) {
    }
}
