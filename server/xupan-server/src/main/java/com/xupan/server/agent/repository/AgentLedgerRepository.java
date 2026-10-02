package com.xupan.server.agent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 代理层账本流水仓储（Flyway V60 {@code agent_ledger}）。
 *
 * <p>对应账本原则 2「余额变化必须同时写流水」与原则 3「追加保留」：
 * 一次余额变动一行，只插入不更新不删除；同时记录变动前后余额，使账本可独立验算。</p>
 *
 * <p>{@code agent_id} 与 {@code group_id} 恰好一个非空：机器流水与子账号流水共用一张表。</p>
 */
@Repository
public class AgentLedgerRepository {

    private final JdbcTemplate jdbc;

    public AgentLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁定并读取机器当前积分。
     *
     * <p>用 {@code FOR UPDATE} 把"读余额 → 算差额 → 写余额 → 写流水"放进同一把行锁，
     * 避免并发下两个请求各自基于同一个旧余额写流水，导致账本与余额不一致。</p>
     */
    public Optional<BigDecimal> lockAgentScore(long agentId) {
        return jdbc.query("""
                SELECT score FROM agent WHERE id = ? AND deleted_at IS NULL FOR UPDATE
                """, (rs, rowNum) -> rs.getBigDecimal("score"), agentId).stream().findFirst();
    }

    /** 锁定并读取子账号当前积分。 */
    public Optional<BigDecimal> lockGroupScore(long groupId) {
        return jdbc.query("""
                SELECT score FROM agent_group WHERE id = ? AND deleted_at IS NULL FOR UPDATE
                """, (rs, rowNum) -> rs.getBigDecimal("score"), groupId).stream().findFirst();
    }

    public int updateAgentScore(long agentId, BigDecimal score) {
        return jdbc.update("""
                UPDATE agent SET score = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL
                """, score, agentId);
    }

    public int updateGroupScore(long groupId, BigDecimal score) {
        return jdbc.update("""
                UPDATE agent_group SET score = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL
                """, score, groupId);
    }

    /** 追加一行流水。{@code idempotencyKey} 唯一，重复提交会走 {@link #findByIdempotencyKey} 的重放分支。 */
    public long insertEntry(Long agentId, Long groupId, String entryType, String direction, BigDecimal amount,
                            BigDecimal balanceBefore, BigDecimal balanceAfter, String reason,
                            String referenceType, String referenceId, String idempotencyKey,
                            Long operatorUserId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO agent_ledger
                    (agent_id, group_id, entry_type, direction, amount, balance_before, balance_after,
                     reason, reference_type, reference_id, idempotency_key, operator_user_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, agentId, groupId, entryType, direction, amount, balanceBefore, balanceAfter,
                reason, referenceType, referenceId, idempotencyKey, operatorUserId,
                createdAt == null ? null : Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT MAX(id) FROM agent_ledger WHERE idempotency_key = ?",
                Long.class, idempotencyKey);
    }

    public Optional<LedgerRow> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
                SELECT id, agent_id, group_id, entry_type, direction, amount, balance_before, balance_after,
                       reason, reference_type, reference_id, idempotency_key, operator_user_id, created_at
                  FROM agent_ledger WHERE idempotency_key = ?
                """, (rs, rowNum) -> row(rs), idempotencyKey).stream().findFirst();
    }

    /** 机器流水分页（按追加顺序倒序，最新在前）。 */
    public List<LedgerRow> findAgentEntries(long agentId, int page, int pageSize) {
        return jdbc.query("""
                SELECT id, agent_id, group_id, entry_type, direction, amount, balance_before, balance_after,
                       reason, reference_type, reference_id, idempotency_key, operator_user_id, created_at
                  FROM agent_ledger WHERE agent_id = ?
                 ORDER BY id DESC LIMIT ? OFFSET ?
                """, (rs, rowNum) -> row(rs), agentId, pageSize, (long) (page - 1) * pageSize);
    }

    public long countAgentEntries(long agentId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ?", Long.class, agentId);
        return total == null ? 0 : total;
    }

    /** 子账号流水分页。 */
    public List<LedgerRow> findGroupEntries(long groupId, int page, int pageSize) {
        return jdbc.query("""
                SELECT id, agent_id, group_id, entry_type, direction, amount, balance_before, balance_after,
                       reason, reference_type, reference_id, idempotency_key, operator_user_id, created_at
                  FROM agent_ledger WHERE group_id = ?
                 ORDER BY id DESC LIMIT ? OFFSET ?
                """, (rs, rowNum) -> row(rs), groupId, pageSize, (long) (page - 1) * pageSize);
    }

    public long countGroupEntries(long groupId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_ledger WHERE group_id = ?", Long.class, groupId);
        return total == null ? 0 : total;
    }

    /**
     * 账本验算：机器流水里"变动后余额"的最新一条应等于 {@code agent.score}。
     *
     * <p>供对账使用（账本原则：账必须能自己验算）。没有流水时返回 {@code true}
     * （余额为 0 且无流水是一致的）。</p>
     */
    public boolean agentLedgerMatchesBalance(long agentId) {
        BigDecimal latest = jdbc.query("""
                SELECT balance_after FROM agent_ledger WHERE agent_id = ? ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> rs.getBigDecimal("balance_after"), agentId).stream().findFirst().orElse(null);
        BigDecimal score = jdbc.queryForObject(
                "SELECT score FROM agent WHERE id = ?", BigDecimal.class, agentId);
        if (latest == null) {
            return score == null || score.signum() == 0;
        }
        return score != null && latest.compareTo(score) == 0;
    }

    private static LedgerRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long agentId = rs.getObject("agent_id", Long.class);
        Long groupId = rs.getObject("group_id", Long.class);
        Long operator = rs.getObject("operator_user_id", Long.class);
        Timestamp created = rs.getTimestamp("created_at");
        return new LedgerRow(rs.getLong("id"), agentId, groupId, rs.getString("entry_type"),
                rs.getString("direction"), rs.getBigDecimal("amount"), rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"), rs.getString("reason"), rs.getString("reference_type"),
                rs.getString("reference_id"), rs.getString("idempotency_key"), operator,
                created == null ? null : created.toInstant());
    }

    /** 一行代理账本流水。 */
    public record LedgerRow(long id, Long agentId, Long groupId, String entryType, String direction,
                            BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
                            String reason, String referenceType, String referenceId, String idempotencyKey,
                            Long operatorUserId, Instant createdAt) {
    }
}
