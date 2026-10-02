package com.xupan.server.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PermissionService {

    private final JdbcTemplate jdbcTemplate;

    public PermissionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Set<String> findPermissionCodes(long userId) {
        Set<String> codes = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT DISTINCT p.permission_code
                  FROM sys_user u
                  JOIN sys_user_role ur ON ur.user_id = u.id
                  JOIN sys_role r ON r.id = ur.role_id
                  JOIN sys_role_permission rp ON rp.role_id = r.id
                  JOIN sys_permission p ON p.id = rp.permission_id
                 WHERE u.id = ?
                   AND u.status = 'ACTIVE'
                   AND r.status = 'ACTIVE'
                   AND p.status = 'ACTIVE'
                 ORDER BY p.permission_code
                """, String.class, userId));
        List<SubAccountFlags> flags = jdbcTemplate.query("""
                SELECT machine_manage, sub_account_manage
                  FROM agent_group
                 WHERE sys_user_id = ?
                   AND deleted_at IS NULL
                """, (rs, rowNum) -> new SubAccountFlags(
                        rs.getBoolean("machine_manage"), rs.getBoolean("sub_account_manage")), userId);
        if (!flags.isEmpty()) {
            SubAccountFlags access = flags.get(0);
            if (!access.machineManage()) codes.remove("MACHINE_MANAGE");
            if (!access.subAccountManage()) codes.remove("SUB_ACCOUNT_MANAGE");
        }
        return codes;
    }

    public List<String> findRoleCodes(long userId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT r.role_code
                  FROM sys_user u
                  JOIN sys_user_role ur ON ur.user_id = u.id
                  JOIN sys_role r ON r.id = ur.role_id
                 WHERE u.id = ?
                   AND u.status = 'ACTIVE'
                   AND r.status = 'ACTIVE'
                 ORDER BY r.role_code
                """, String.class, userId);
    }

    public Set<GrantedAuthority> toAuthorities(Set<String> permissionCodes) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        if (permissionCodes == null) {
            return authorities;
        }
        permissionCodes.stream().filter(code -> code != null && !code.isBlank()).sorted()
                .map(code -> new SimpleGrantedAuthority("PERM_" + code))
                .forEach(authorities::add);
        return authorities;
    }

    public boolean hasPermission(long userId, String permissionCode) {
        return permissionCode != null && findPermissionCodes(userId).contains(permissionCode);
    }
    private record SubAccountFlags(boolean machineManage, boolean subAccountManage) {}
}
