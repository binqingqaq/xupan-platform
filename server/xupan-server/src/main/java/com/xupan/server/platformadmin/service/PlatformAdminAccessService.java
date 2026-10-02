package com.xupan.server.platformadmin.service;

import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Resolves data scope for the BY220 admin sub-account login. */
@Service
public class PlatformAdminAccessService {

    private final JdbcTemplate jdbc;

    public PlatformAdminAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<Long> subAccountGroupId(long operator) {
        if (operator <= 0) return Optional.empty();
        List<Long> rows = jdbc.query("""
                SELECT id FROM agent_group
                 WHERE sys_user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                """, (rs, rowNum) -> rs.getLong("id"), operator);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Transactional(readOnly = true)
    public Long scopedGroupId(long operator, Long requestedGroupId) {
        return subAccountGroupId(operator).orElse(requestedGroupId);
    }

    @Transactional(readOnly = true)
    public void requireMachineAccess(long operator, long machineId) {
        Optional<Long> groupId = subAccountGroupId(operator);
        if (groupId.isEmpty()) return;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent
                 WHERE id = ? AND group_id = ? AND deleted_at IS NULL
                """, Long.class, machineId, groupId.get());
        if (count == null || count == 0) {
            throw BusinessException.forbidden("MACHINE_SCOPE_FORBIDDEN", "不能操作其他子账号的机器");
        }
    }
}
