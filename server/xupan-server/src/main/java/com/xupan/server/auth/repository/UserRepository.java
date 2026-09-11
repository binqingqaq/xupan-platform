package com.xupan.server.auth.repository;

import com.xupan.server.auth.domain.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class UserRepository {

    private static final String USER_COLUMNS = """
            id, username, display_name, avatar_key, password_hash, status,
            failed_login_count, locked_until, security_version, last_login_at, last_login_ip
            """;
    private static final String ADMIN_USER_COLUMNS = """
            id, username, display_name, status, created_at, last_login_at
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

    public List<UserAccount> findByStatus(String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query("SELECT " + USER_COLUMNS + " FROM sys_user ORDER BY id",
                    this::mapUser);
        }
        return jdbcTemplate.query("SELECT " + USER_COLUMNS + " FROM sys_user WHERE status = ? ORDER BY id",
                this::mapUser, status);
    }

    @Transactional
    public int updateManagedStatus(long userId, String status) {
        return jdbcTemplate.update("""
                UPDATE sys_user
                   SET status = ?,
                       locked_until = NULL,
                       failed_login_count = 0,
                       security_version = security_version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, status, userId);
    }

    @Transactional
    public int updatePasswordHash(long userId, String passwordHash) {
        return jdbcTemplate.update("""
                UPDATE sys_user
                   SET password_hash = ?,
                       failed_login_count = 0,
                       locked_until = NULL,
                       security_version = security_version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, passwordHash, userId);
    }

    public List<UserManagementRow> findManagementPage(String status, String keyword,
                                                        int page, int pageSize) {
        validatePage(page, pageSize);
        validateStatus(status == null || status.isBlank() ? null : status.trim());
        if (keyword != null && keyword.trim().length() > 64) {
            throw new IllegalArgumentException("用户查询关键字长度不能超过 64");
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(ADMIN_USER_COLUMNS)
                .append(" FROM sys_user WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        appendManagementFilter(sql, args, status, keyword);
        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbcTemplate.query(sql.toString(), this::mapManagementRow, args.toArray());
    }

    public long countManagementUsers(String status, String keyword) {
        validateStatus(status == null || status.isBlank() ? null : status.trim());
        if (keyword != null && keyword.trim().length() > 64) {
            throw new IllegalArgumentException("用户查询关键字长度不能超过 64");
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sys_user WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        appendManagementFilter(sql, args, status, keyword);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public Optional<UserManagementRow> findManagementUser(long userId) {
        return jdbcTemplate.query("SELECT " + ADMIN_USER_COLUMNS + " FROM sys_user WHERE id = ?",
                this::mapManagementRow, userId).stream().findFirst();
    }

    public List<String> findRoleCodes(long userId) {
        return jdbcTemplate.queryForList("""
                SELECT r.role_code
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id = ur.role_id
                 WHERE ur.user_id = ?
                 ORDER BY r.role_code
                """, String.class, userId);
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

    @Transactional
    public int deleteRoles(long userId) {
        return jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
    }

    @Transactional
    public int assignRoles(long userId, java.util.Collection<Long> roleIds) {
        int inserted = 0;
        for (Long roleId : roleIds) {
            inserted += jdbcTemplate.update("""
                    INSERT INTO sys_user_role (user_id, role_id)
                    VALUES (?, ?)
                    """, userId, roleId);
        }
        return inserted;
    }

    @Transactional
    public int assignRole(long userId, String roleCode) {
        return jdbcTemplate.update("""
                INSERT INTO sys_user_role (user_id, role_id)
                SELECT ?, r.id
                  FROM sys_role r
                 WHERE r.role_code = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM sys_user_role existing
                        WHERE existing.user_id = ? AND existing.role_id = r.id)
                """, userId, roleCode, userId);
    }

    private Optional<UserAccount> queryOne(String sql, Object... args) {
        return jdbcTemplate.query(sql, this::mapUser, args).stream().findFirst();
    }

    private UserAccount mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserAccount(
                rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("avatar_key"), rs.getString("password_hash"), rs.getString("status"),
                rs.getInt("failed_login_count"), instant(rs.getTimestamp("locked_until")),
                rs.getLong("security_version"), instant(rs.getTimestamp("last_login_at")),
                rs.getString("last_login_ip"));
    }

    private UserManagementRow mapManagementRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new UserManagementRow(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("status"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_login_at")));
    }

    private static void appendManagementFilter(StringBuilder sql, List<Object> args,
                                               String status, String keyword) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR display_name LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("用户查询分页参数无效");
        }
    }

    private static void validateStatus(String status) {
        if (status != null && !List.of("ACTIVE", "DISABLED", "LOCKED", "DELETED").contains(status)) {
            throw new IllegalArgumentException("用户状态无效");
        }
    }

    public record UserManagementRow(long id, String username, String displayName,
                                    String status, Instant createdAt, Instant lastLoginAt) {
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
