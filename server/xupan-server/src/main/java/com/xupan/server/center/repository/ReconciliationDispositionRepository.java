package com.xupan.server.center.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 对账差异处置仓储（Flyway V68 {@code center_reconciliation_disposition}）。
 *
 * <p>对应规划 C-2 ②"对账差异的处置动作"与 C-4 ③"长期差异的保留与清理策略"。</p>
 *
 * <p><b>处置 ≠ 修复</b>：规划明确差异**不自动修复**，先人工确认。因此这里只记录
 * "这笔差异被判断为什么、依据是什么"，绝不去改 {@code center_order} 或账本。
 * 自动修复会在真相未明时改写账本事实，属于不可接受的做法。</p>
 */
@Repository
public class ReconciliationDispositionRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationDispositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String differenceType, long betId, String resolution, Long centerAgentId,
                       String reason, Instant expiresAt, String idempotencyKey, Long operatorUserId) {
        jdbc.update("""
                INSERT INTO center_reconciliation_disposition
                    (difference_type, bet_id, resolution, center_agent_id, reason, expires_at,
                     idempotency_key, operator_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, differenceType, betId, resolution, centerAgentId, reason,
                expiresAt == null ? null : Timestamp.from(expiresAt), idempotencyKey, operatorUserId);
        Long id = jdbc.queryForObject(
                "SELECT id FROM center_reconciliation_disposition WHERE idempotency_key = ?",
                Long.class, idempotencyKey);
        if (id == null) {
            throw new IllegalStateException("写入对账差异处置后未找到记录");
        }
        return id;
    }

    public Optional<Row> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(SELECT_COLUMNS + " WHERE idempotency_key = ?",
                (rs, rowNum) -> row(rs), idempotencyKey).stream().findFirst();
    }

    /** 按主键读取一条处置记录（撤销时用于读取当前状态）。 */
    public Optional<Row> findHistoryById(long id) {
        return jdbc.query(SELECT_COLUMNS + " WHERE id = ?",
                (rs, rowNum) -> row(rs), id).stream().findFirst();
    }

    /** 撤销一条处置（不物理删除，只标记撤销——保留判断历史）。 */
    public int revoke(long id, Long revokedBy) {
        return jdbc.update("""
                UPDATE center_reconciliation_disposition
                   SET revoked_at = CURRENT_TIMESTAMP, revoked_by = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, revokedBy, id);
    }

    /**
     * 当前**生效**的处置集合（未撤销、未过期）。
     *
     * <p>"生效"的定义就是这一条 SQL：撤销过的、以及过了 {@code expires_at} 的都不算——
     * 这让"标一次已知就永远不再看"成为不可能。</p>
     */
    public List<Row> findEffective(int limit) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE revoked_at IS NULL
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                 ORDER BY id DESC LIMIT ?
                """, (rs, rowNum) -> row(rs), limit);
    }

    /**
     * 判断某一笔差异当前是否已被有效处置。
     *
     * <p>对账时用：已处置的差异仍会出现在结果里（事实不能被隐藏），
     * 但会带上 {@code disposed=true}，让运营一眼看出"这条我看过了"。</p>
     */
    public boolean isEffectivelyDisposed(String differenceType, long betId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_reconciliation_disposition
                 WHERE difference_type = ? AND bet_id = ?
                   AND revoked_at IS NULL
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """, Long.class, differenceType, betId);
        return count != null && count > 0;
    }

    /** 某笔注单的全部处置历史（含已撤销、已过期——审计要看得全）。 */
    public List<Row> findHistory(String differenceType, long betId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE difference_type = ? AND bet_id = ?
                 ORDER BY id
                """, (rs, rowNum) -> row(rs), differenceType, betId);
    }

    /**
     * 清理策略的实际口径：统计已失效（撤销或过期）的处置记录数量。
     *
     * <p><b>刻意不提供删除方法</b>：处置记录是"差异为什么被认可"的凭据，
     * 按账本原则 3 只追加不删除。这个方法只用于运维观察表增长，供人工判断是否需要归档。</p>
     */
    public long countExpired() {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_reconciliation_disposition
                 WHERE revoked_at IS NOT NULL
                    OR (expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP)
                """, Long.class);
        return total == null ? 0 : total;
    }

    public long countEffective() {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM center_reconciliation_disposition
                 WHERE revoked_at IS NULL
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """, Long.class);
        return total == null ? 0 : total;
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, difference_type, bet_id, resolution, center_agent_id, reason, expires_at,
                   revoked_at, revoked_by, idempotency_key, operator_user_id, created_at
              FROM center_reconciliation_disposition
            """;

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("difference_type"), rs.getLong("bet_id"),
                rs.getString("resolution"), rs.getObject("center_agent_id", Long.class),
                rs.getString("reason"), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("revoked_at")), rs.getObject("revoked_by", Long.class),
                rs.getString("idempotency_key"), rs.getObject("operator_user_id", Long.class),
                instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 一条对账差异处置记录。 */
    public record Row(long id, String differenceType, long betId, String resolution, Long centerAgentId,
                      String reason, Instant expiresAt, Instant revokedAt, Long revokedBy,
                      String idempotencyKey, Long operatorUserId, Instant createdAt) {

        /** 当前是否生效（未撤销且未过期）。 */
        public boolean effective() {
            if (revokedAt != null) {
                return false;
            }
            return expiresAt == null || expiresAt.isAfter(Instant.now());
        }
    }
}
