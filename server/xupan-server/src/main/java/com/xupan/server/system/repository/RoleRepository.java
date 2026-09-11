package com.xupan.server.system.repository;

import com.xupan.server.system.domain.RoleOption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Repository
public class RoleRepository {

    private final JdbcTemplate jdbcTemplate;

    public RoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RoleOption> findActiveRoles() {
        return jdbcTemplate.query("""
                SELECT id, role_code, display_name, status
                  FROM sys_role
                 WHERE status = 'ACTIVE'
                 ORDER BY id
                """, this::mapRole);
    }

    public List<RoleOption> findActiveByCodes(Collection<String> roleCodes) {
        List<String> codes = normalizeCodes(roleCodes);
        if (codes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, role_code, display_name, status
                  FROM sys_role
                 WHERE status = 'ACTIVE' AND role_code IN (%s)
                 ORDER BY id
                """.formatted(placeholders), this::mapRole, codes.toArray());
    }

    public List<String> findRoleCodesByUserId(long userId) {
        return jdbcTemplate.queryForList("""
                SELECT r.role_code
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id = ur.role_id
                 WHERE ur.user_id = ?
                 ORDER BY r.role_code
                """, String.class, userId);
    }

    public boolean userHasRole(long userId, String roleCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? AND r.role_code = ? AND r.status = 'ACTIVE'
                """, Integer.class, userId, roleCode);
        return count != null && count > 0;
    }

    /** Locks all current active administrators before a last-admin decision. */
    public List<Long> findActiveAdminUserIdsForUpdate() {
        requireTransaction("findActiveAdminUserIdsForUpdate");
        return jdbcTemplate.queryForList("""
                SELECT u.id
                  FROM sys_user u
                  JOIN sys_user_role ur ON ur.user_id = u.id
                  JOIN sys_role r ON r.id = ur.role_id
                 WHERE r.role_code = 'ADMIN'
                   AND r.status = 'ACTIVE'
                   AND u.status <> 'DELETED'
                 ORDER BY u.id
                 FOR UPDATE
                """, Long.class);
    }

    @Transactional
    public void replaceUserRoles(long userId, Collection<RoleOption> roles) {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        if (roles == null || roles.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                roles.stream().map(role -> new Object[]{userId, role.id()}).toList());
    }

    private RoleOption mapRole(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RoleOption(rs.getLong("id"), rs.getString("role_code"),
                rs.getString("display_name"), rs.getString("status"));
    }

    private static List<String> normalizeCodes(Collection<String> roleCodes) {
        if (roleCodes == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String code : roleCodes) {
            if (code != null && !code.isBlank()) {
                result.add(code.trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }
}
