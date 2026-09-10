package com.xupan.server.auth.repository;

import com.xupan.server.auth.domain.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class UserRepository {

    private static final String USER_COLUMNS = """
            id, username, display_name, avatar_key, password_hash, status,
            failed_login_count, locked_until, security_version, last_login_at, last_login_ip
            """;

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return queryOne("SELECT " + USER_COLUMNS + " FROM sys_user WHERE username = ?", username);
    }

    public Optional<UserAccount> findById(long userId) {
        return queryOne("SELECT " + USER_COLUMNS + " FROM sys_user WHERE id = ?", userId);
    }

    /**
     * Loads a user row with a database lock. This method must only be called from an
     * already active outer transaction; use {@link #executeInLockedUserTransaction(long, Function)}
     * when the caller does not own the transaction boundary.
     */
    public Optional<UserAccount> findByIdForUpdate(long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("findByIdForUpdate 必须在外层事务中调用");
        }
        return queryOne("SELECT " + USER_COLUMNS + " FROM sys_user WHERE id = ? FOR UPDATE", userId);
    }

    /** Runs the user validation/update workflow while the row lock remains held. */
    @Transactional
    public <T> T executeInLockedUserTransaction(long userId, Function<UserAccount, T> workflow) {
        UserAccount user = findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        return workflow.apply(user);
    }

    @Transactional
    public long insert(String username, String displayName, String passwordHash, String status) {
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, display_name, password_hash, status) VALUES (?, ?, ?, ?)",
                username, displayName, passwordHash, status);
        Long key = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        if (key == null) {
            throw new IllegalStateException("创建用户后未取得用户 ID");
        }
        return key;
    }

    @Transactional
    public int recordLoginFailure(long userId, Instant lockedUntil) {
        Timestamp lockTimestamp = timestamp(lockedUntil);
        return jdbcTemplate.update("""
                UPDATE sys_user
                   SET failed_login_count = failed_login_count + 1,
                       locked_until = ?,
                       status = CASE WHEN ? IS NULL THEN 'ACTIVE' ELSE 'LOCKED' END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                   AND status NOT IN ('DISABLED', 'DELETED')
                """, lockTimestamp, lockTimestamp, userId);
    }

    @Transactional
    public int clearLoginFailures(long userId, String loginIp, Instant loginAt) {
        return jdbcTemplate.update("""
                UPDATE sys_user
                   SET failed_login_count = 0,
                       locked_until = NULL,
                       status = 'ACTIVE',
                       last_login_at = ?,
                       last_login_ip = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                   AND status NOT IN ('DISABLED', 'DELETED')
                """, timestamp(loginAt), loginIp, userId);
    }

    @Transactional
    public int incrementSecurityVersion(long userId) {
        return jdbcTemplate.update("""
                UPDATE sys_user
                   SET security_version = security_version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, userId);
    }

    public boolean existsAnyUser() {
        Boolean exists = jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM sys_user)", Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public int updateStatus(long userId, String status) {
        return jdbcTemplate.update("UPDATE sys_user SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, userId);
    }

    private Optional<UserAccount> queryOne(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new UserAccount(
                        rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("avatar_key"), rs.getString("password_hash"), rs.getString("status"),
                        rs.getInt("failed_login_count"), instant(rs.getTimestamp("locked_until")),
                        rs.getLong("security_version"), instant(rs.getTimestamp("last_login_at")),
                        rs.getString("last_login_ip")), args).stream().findFirst();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
