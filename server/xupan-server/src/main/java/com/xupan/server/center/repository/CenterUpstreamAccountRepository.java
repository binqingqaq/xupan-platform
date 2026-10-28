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

/** Persistence for center upstream credentials and each machine's current login session. */
@Repository
public class CenterUpstreamAccountRepository {

    private static final String ACCOUNT_SELECT = """
            SELECT c.id, c.agent_group_id, g.display_name AS group_name, c.agent_code AS username,
                   c.status, c.expires_at, c.available_balance, c.frozen_balance,
                   COALESCE((SELECT SUM(o.amount) FROM center_order o
                              WHERE o.center_agent_id = c.id AND o.status = 'ACCEPTED'), 0) AS open_stake,
                   (SELECT MAX(s.authenticated_at) FROM center_machine_session s
                     WHERE s.center_agent_id = c.id) AS last_login_at,
                   CASE WHEN c.status = 'ACTIVE' AND c.expires_at > CURRENT_TIMESTAMP
                              AND g.status = 'ACTIVE' AND g.deleted_at IS NULL
                        THEN (SELECT COUNT(*) FROM center_machine_session s
                               JOIN agent m ON m.id = s.machine_agent_id
                              WHERE s.center_agent_id = c.id AND s.revoked_at IS NULL
                                AND s.expires_at > CURRENT_TIMESTAMP AND m.status = 'ACTIVE'
                                AND m.deleted_at IS NULL AND m.system_owned = FALSE
                                AND m.group_id = c.agent_group_id)
                        ELSE 0 END AS online_machine_count,
                   CASE WHEN c.status = 'ACTIVE' AND c.expires_at > CURRENT_TIMESTAMP
                              AND g.status = 'ACTIVE' AND g.deleted_at IS NULL
                        THEN (SELECT COUNT(*) FROM center_machine_session s
                               JOIN agent m ON m.id = s.machine_agent_id
                              WHERE s.center_agent_id = c.id AND s.revoked_at IS NULL
                                AND s.expires_at > CURRENT_TIMESTAMP AND m.status = 'ACTIVE'
                                AND m.deleted_at IS NULL AND m.system_owned = FALSE
                                AND m.group_id = c.agent_group_id) > 0
                        ELSE FALSE END AS online
              FROM center_agent c
              LEFT JOIN agent_group g ON g.id = c.agent_group_id
            """;

    private final JdbcTemplate jdbc;

    public CenterUpstreamAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AccountRow> findAllAccounts() {
        return jdbc.query(ACCOUNT_SELECT + " WHERE c.agent_group_id IS NOT NULL ORDER BY c.id", this::accountRow);
    }

    public Optional<AccountRow> findAccount(long accountId) {
        return jdbc.query(ACCOUNT_SELECT + " WHERE c.id = ? AND c.agent_group_id IS NOT NULL",
                this::accountRow, accountId)
                .stream().findFirst();
    }

    public List<MachineSessionRow> findMachineSessions(long accountId) {
        return jdbc.query("""
                SELECT m.id AS machine_id, m.agent_code AS machine_code, m.display_name AS machine_name,
                       m.status AS machine_status, m.deleted_at AS machine_deleted_at,
                       g.status AS group_status, g.deleted_at AS group_deleted_at,
                       s.authenticated_at, s.expires_at AS session_expires_at,
                       s.revoked_at, s.revoke_reason
                  FROM center_agent c
                  JOIN agent m ON m.group_id = c.agent_group_id AND m.system_owned = FALSE
                  JOIN agent_group g ON g.id = c.agent_group_id
                  LEFT JOIN center_machine_session s
                    ON s.machine_agent_id = m.id AND s.center_agent_id = c.id
                 WHERE c.id = ?
                 ORDER BY m.id
                """, (rs, rowNum) -> new MachineSessionRow(rs.getLong("machine_id"),
                rs.getString("machine_code"), rs.getString("machine_name"), rs.getString("machine_status"),
                instant(rs.getTimestamp("machine_deleted_at")), rs.getString("group_status"),
                instant(rs.getTimestamp("group_deleted_at")), instant(rs.getTimestamp("authenticated_at")),
                instant(rs.getTimestamp("session_expires_at")), instant(rs.getTimestamp("revoked_at")),
                rs.getString("revoke_reason")), accountId);
    }

    public Optional<AccountCredential> lockCredentialsByUsername(String username) {
        return jdbc.query("""
                SELECT c.id, c.agent_group_id, c.agent_code AS username, c.status, c.password_hash,
                       c.expires_at, c.failed_login_count, c.locked_until,
                       g.status AS group_status, g.deleted_at AS group_deleted_at
                  FROM center_agent c
                  LEFT JOIN agent_group g ON g.id = c.agent_group_id
                 WHERE c.agent_code = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new AccountCredential(rs.getLong("id"),
                nullableLong(rs, "agent_group_id"), rs.getString("username"), rs.getString("status"),
                rs.getString("password_hash"), instant(rs.getTimestamp("expires_at")),
                rs.getInt("failed_login_count"), instant(rs.getTimestamp("locked_until")),
                rs.getString("group_status"), instant(rs.getTimestamp("group_deleted_at"))), username)
                .stream().findFirst();
    }

    public Optional<GroupRow> lockGroup(long groupId) {
        return jdbc.query("""
                SELECT id, display_name, status, deleted_at
                  FROM agent_group WHERE id = ? FOR UPDATE
                """, (rs, rowNum) -> new GroupRow(rs.getLong("id"), rs.getString("display_name"),
                rs.getString("status"), instant(rs.getTimestamp("deleted_at"))), groupId).stream().findFirst();
    }

    public boolean accountExistsForGroup(long groupId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM center_agent WHERE agent_group_id = ?)",
                Boolean.class, groupId));
    }

    public boolean usernameExists(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM center_agent WHERE agent_code = ?)", Boolean.class, username));
    }

    public long insertAccount(long groupId, String username, String passwordHash,
                              String tokenHash, String tokenPrefix, BigDecimal initialAvailable,
                              Instant expiresAt, long operatorUserId) {
        jdbc.update("""
                INSERT INTO center_agent
                    (agent_code, agent_name, upstream_account, token_hash, token_prefix, status,
                     credit_limit, available_balance, password_hash, expires_at, agent_group_id, created_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, username, username, username, tokenHash, tokenPrefix,
                BigDecimal.ZERO.setScale(2), initialAvailable, passwordHash,
                Timestamp.from(expiresAt), groupId, operatorUserId);
        Long id = jdbc.queryForObject("SELECT id FROM center_agent WHERE agent_code = ?", Long.class, username);
        if (id == null) {
            throw new IllegalStateException("创建中心账号后未取得 ID");
        }
        return id;
    }

    public void insertOpeningBalance(long accountId, BigDecimal amount, long operatorUserId) {
        jdbc.update("""
                INSERT INTO center_agent_ledger
                    (center_agent_id, bucket, entry_type, direction, amount, balance_before,
                     balance_after, reason, reference_type, reference_id, idempotency_key,
                     operator_user_id)
                VALUES (?, 'AVAILABLE', 'OPENING_BALANCE', 'IN', ?, 0, ?,
                        '创建中心账号初始额度', 'CENTER_AGENT', ?, ?, ?)
                """, accountId, amount, amount, Long.toString(accountId),
                "CENTER-OPENING-" + accountId, operatorUserId);
    }

    public int updateStatus(long accountId, String status) {
        return jdbc.update("""
                UPDATE center_agent SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, status, accountId);
    }

    public Optional<DeletionContext> lockAccountForDeletion(long accountId) {
        return jdbc.query("""
                SELECT id, agent_group_id, agent_code, status, available_balance,
                       frozen_balance, upstream_unsettled
                  FROM center_agent
                 WHERE id = ? AND agent_group_id IS NOT NULL
                 FOR UPDATE
                """, (rs, rowNum) -> new DeletionContext(rs.getLong("id"),
                rs.getLong("agent_group_id"), rs.getString("agent_code"), rs.getString("status"),
                rs.getBigDecimal("available_balance"), rs.getBigDecimal("frozen_balance"),
                rs.getBigDecimal("upstream_unsettled")), accountId).stream().findFirst();
    }

    public long countUnownedCenterAttempts(long groupId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM report_upstream_order r
                  JOIN agent a ON a.id = r.agent_id
                 WHERE r.report_type = 'CENTER' AND r.center_agent_id IS NULL
                   AND a.group_id = ?
                """, Long.class, groupId);
        return count == null ? 0 : count;
    }

    public int markBetsWithOnlyDeletedAccountAttempts(long accountId) {
        return jdbc.update("""
                UPDATE game_bet b
                   SET center_report_deleted_at = COALESCE(center_report_deleted_at, CURRENT_TIMESTAMP)
                 WHERE EXISTS (SELECT 1 FROM report_upstream_order r
                                WHERE r.bet_id = b.id AND r.center_agent_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM report_upstream_order other
                                    WHERE other.bet_id = b.id
                                      AND (other.center_agent_id IS NULL OR other.center_agent_id <> ?))
                """, accountId, accountId);
    }

    public int deleteReportAttempts(long accountId) {
        return jdbc.update("DELETE FROM report_upstream_order WHERE center_agent_id = ?", accountId);
    }

    public int clearMachineCenterConfig(long accountId, long groupId) {
        return jdbc.update("""
                UPDATE agent
                   SET center_agent_id = NULL,
                       report_type = CASE WHEN report_type = 'CENTER' THEN 'NONE' ELSE report_type END,
                       report_auto = FALSE,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE center_agent_id = ? OR (group_id = ? AND report_type = 'CENTER')
                """, accountId, groupId);
    }

    public int deleteMachineSessions(long accountId) {
        return jdbc.update("DELETE FROM center_machine_session WHERE center_agent_id = ?", accountId);
    }

    public int deleteCenterOrderDispositions(long accountId) {
        return jdbc.update("""
                DELETE FROM center_order_disposition
                 WHERE center_order_id IN (SELECT id FROM center_order WHERE center_agent_id = ?)
                """, accountId);
    }

    public int deleteReconciliationDispositions(long accountId) {
        return jdbc.update("DELETE FROM center_reconciliation_disposition WHERE center_agent_id = ?", accountId);
    }

    public int deleteRiskHits(long accountId) {
        return jdbc.update("DELETE FROM center_risk_hit WHERE center_agent_id = ?", accountId);
    }

    public int deleteCenterLedger(long accountId) {
        return jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id = ?", accountId);
    }

    public List<Long> findCenterOrderIds(long accountId) {
        return jdbc.queryForList("SELECT id FROM center_order WHERE center_agent_id = ? ORDER BY id",
                Long.class, accountId);
    }

    public int detachMachineLedgerOrderReference(long centerOrderId, long deletedAccountId) {
        return jdbc.update("""
                UPDATE agent_ledger
                   SET reference_type = 'CENTER_ACCOUNT_DELETED', reference_id = ?
                 WHERE reference_type = 'CENTER_ORDER' AND reference_id = ?
                   AND entry_type IN ('CENTER_RISK_RESERVE', 'CENTER_RISK_RELEASE')
                """, Long.toString(deletedAccountId), Long.toString(centerOrderId));
    }

    public int deleteCenterOrders(long accountId) {
        return jdbc.update("DELETE FROM center_order WHERE center_agent_id = ?", accountId);
    }

    public int deleteAccount(long accountId) {
        return jdbc.update("DELETE FROM center_agent WHERE id = ?", accountId);
    }

    public int updateExpiry(long accountId, Instant expiresAt) {
        return jdbc.update("""
                UPDATE center_agent SET expires_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, Timestamp.from(expiresAt), accountId);
    }

    public int updatePassword(long accountId, String passwordHash) {
        return jdbc.update("""
                UPDATE center_agent
                   SET password_hash = ?, failed_login_count = 0, locked_until = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, passwordHash, accountId);
    }

    public int updateLoginFailures(long accountId, int failedCount, Instant lockedUntil) {
        return jdbc.update("""
                UPDATE center_agent
                   SET failed_login_count = ?, locked_until = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, failedCount, timestamp(lockedUntil), accountId);
    }

    public Optional<MachineRow> lockMachineByAccountUserId(long authenticatedUserId) {
        return jdbc.query("""
                SELECT m.id, m.group_id, m.status, m.deleted_at, m.system_owned,
                       g.status AS group_status, g.deleted_at AS group_deleted_at, g.display_name AS group_name
                  FROM agent m
                  LEFT JOIN agent_group g ON g.id = m.group_id
                 WHERE m.account_user_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new MachineRow(rs.getLong("id"), nullableLong(rs, "group_id"),
                rs.getString("status"), instant(rs.getTimestamp("deleted_at")), rs.getBoolean("system_owned"),
                rs.getString("group_status"), instant(rs.getTimestamp("group_deleted_at")),
                rs.getString("group_name")), authenticatedUserId).stream().findFirst();
    }

    public void resetExpiredLoginLock(long accountId) {
        jdbc.update("""
                UPDATE center_agent SET failed_login_count = 0, locked_until = NULL,
                       updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, accountId);
    }

    public void recordLoginFailure(long accountId, int failedCount, Instant lockedUntil) {
        updateLoginFailures(accountId, failedCount, lockedUntil);
    }

    public void resetLoginFailures(long accountId) {
        jdbc.update("""
                UPDATE center_agent SET failed_login_count = 0, locked_until = NULL,
                       updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, accountId);
    }

    public int updateCurrentSession(long accountId, long machineAgentId, Instant authenticatedAt,
                                    Instant expiresAt) {
        return jdbc.update("""
                UPDATE center_machine_session
                   SET center_agent_id = ?, authenticated_at = ?, expires_at = ?, revoked_at = NULL,
                       revoke_reason = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE machine_agent_id = ?
                """, accountId, Timestamp.from(authenticatedAt), Timestamp.from(expiresAt), machineAgentId);
    }

    public void insertCurrentSession(long accountId, long machineAgentId, Instant authenticatedAt,
                                     Instant expiresAt) {
        jdbc.update("""
                INSERT INTO center_machine_session
                    (center_agent_id, machine_agent_id, authenticated_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, accountId, machineAgentId, Timestamp.from(authenticatedAt), Timestamp.from(expiresAt));
    }

    public int revokeMachineSession(long machineAgentId, Instant revokedAt, String reason) {
        return jdbc.update("""
                UPDATE center_machine_session
                   SET revoked_at = ?, revoke_reason = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE machine_agent_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), reason, machineAgentId);
    }

    public int revokeAccountSessions(long accountId, Instant revokedAt, String reason) {
        return jdbc.update("""
                UPDATE center_machine_session
                   SET revoked_at = ?, revoke_reason = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE center_agent_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), reason, accountId);
    }

    public Optional<AccountRow> findAccountForMachine(long authenticatedUserId) {
        return jdbc.query("""
                SELECT c.id, c.agent_group_id, g.display_name AS group_name, c.agent_code AS username,
                       c.status, c.expires_at, c.available_balance, c.frozen_balance,
                       COALESCE((SELECT SUM(o.amount) FROM center_order o
                                  WHERE o.center_agent_id = c.id AND o.status = 'ACCEPTED'), 0) AS open_stake,
                       (SELECT MAX(s.authenticated_at) FROM center_machine_session s
                         WHERE s.center_agent_id = c.id) AS last_login_at,
                       CASE WHEN c.status = 'ACTIVE' AND c.expires_at > CURRENT_TIMESTAMP
                                  AND g.status = 'ACTIVE' AND g.deleted_at IS NULL
                            THEN (SELECT COUNT(*) FROM center_machine_session s
                                   JOIN agent m ON m.id = s.machine_agent_id
                                  WHERE s.center_agent_id = c.id AND s.revoked_at IS NULL
                                    AND s.expires_at > CURRENT_TIMESTAMP AND m.status = 'ACTIVE'
                                     AND m.deleted_at IS NULL AND m.system_owned = FALSE
                                     AND m.group_id = c.agent_group_id)
                            ELSE 0 END AS online_machine_count,
                       CASE WHEN c.status = 'ACTIVE' AND c.expires_at > CURRENT_TIMESTAMP
                                  AND g.status = 'ACTIVE' AND g.deleted_at IS NULL
                                  AND m.status = 'ACTIVE' AND m.deleted_at IS NULL AND m.system_owned = FALSE
                                   AND m.group_id = c.agent_group_id
                                  AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP
                            THEN TRUE ELSE FALSE END AS online
                  FROM agent m
                  LEFT JOIN agent_group g ON g.id = m.group_id
                  LEFT JOIN center_agent c ON c.agent_group_id = m.group_id
                  LEFT JOIN center_machine_session s ON s.machine_agent_id = m.id
                                                     AND s.center_agent_id = c.id
                 WHERE m.account_user_id = ? AND m.deleted_at IS NULL AND m.system_owned = FALSE
                   AND c.id IS NOT NULL
                """, this::accountRow, authenticatedUserId).stream().findFirst();
    }

    private AccountRow accountRow(ResultSet rs, int rowNum) throws SQLException {
        return new AccountRow(nullableLong(rs, "id"), nullableLong(rs, "agent_group_id"),
                rs.getString("group_name"), rs.getString("username"), rs.getString("status"),
                instant(rs.getTimestamp("expires_at")), rs.getBigDecimal("available_balance"),
                rs.getBigDecimal("frozen_balance"), rs.getBigDecimal("open_stake"),
                instant(rs.getTimestamp("last_login_at")), rs.getBoolean("online"),
                rs.getInt("online_machine_count"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record AccountRow(Long accountId, Long groupId, String groupName, String username,
                             String status, Instant expiresAt, BigDecimal availableBalance,
                             BigDecimal frozenBalance, BigDecimal openStake, Instant lastLoginAt,
                             boolean online, int onlineMachineCount) {
    }

    public record AccountCredential(long accountId, Long groupId, String username, String status,
                                    String passwordHash, Instant expiresAt, int failedLoginCount,
                                    Instant lockedUntil, String groupStatus, Instant groupDeletedAt) {
    }

    public record GroupRow(long groupId, String displayName, String status, Instant deletedAt) {
    }

    public record DeletionContext(long accountId, long groupId, String username, String status,
                                  BigDecimal availableBalance, BigDecimal frozenBalance,
                                  BigDecimal upstreamUnsettled) {
    }

    public record MachineRow(long machineAgentId, Long groupId, String status, Instant deletedAt,
                             boolean systemOwned, String groupStatus, Instant groupDeletedAt,
                             String groupName) {
    }

    public record MachineSessionRow(long machineId, String machineCode, String machineName,
                                    String machineStatus, Instant machineDeletedAt,
                                    String groupStatus, Instant groupDeletedAt,
                                    Instant authenticatedAt, Instant sessionExpiresAt,
                                    Instant revokedAt, String revokeReason) {
    }
}
