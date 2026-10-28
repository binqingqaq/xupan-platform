package com.xupan.server.report.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Machine-level auto-report switches and center-session state. */
@Repository
public class ReportConfigRepository {

    private final JdbcTemplate jdbc;

    public ReportConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReportConfigRow> findConfig(long agentId) {
        return jdbc.query("""
                SELECT ag.id, ag.agent_code, ag.display_name, ag.report_auto, ag.report_withdraw,
                       (SELECT s.center_agent_id
                          FROM center_machine_session s
                          JOIN center_agent c ON c.id = s.center_agent_id
                          JOIN agent_group g ON g.id = c.agent_group_id
                         WHERE s.machine_agent_id = ag.id AND s.revoked_at IS NULL
                           AND s.expires_at > CURRENT_TIMESTAMP
                           AND c.status = 'ACTIVE' AND c.expires_at > CURRENT_TIMESTAMP
                           AND c.agent_group_id = ag.group_id
                           AND g.status = 'ACTIVE' AND g.deleted_at IS NULL
                         LIMIT 1) AS center_agent_id,
                       CASE WHEN EXISTS (
                           SELECT 1
                             FROM center_machine_session s
                             JOIN center_agent c ON c.id = s.center_agent_id
                             JOIN agent_group g ON g.id = c.agent_group_id
                            WHERE s.machine_agent_id = ag.id AND s.revoked_at IS NULL
                              AND s.expires_at > CURRENT_TIMESTAMP
                              AND c.status = 'ACTIVE' AND c.expires_at > CURRENT_TIMESTAMP
                              AND c.agent_group_id = ag.group_id
                              AND g.status = 'ACTIVE' AND g.deleted_at IS NULL
                       ) THEN TRUE ELSE FALSE END AS report_online
                  FROM agent ag
                 WHERE ag.id = ? AND ag.deleted_at IS NULL AND ag.status = 'ACTIVE'
                   AND ag.system_owned = FALSE
                """, (rs, rowNum) -> new ReportConfigRow(
                rs.getLong("id"), rs.getObject("center_agent_id", Long.class),
                rs.getString("agent_code"), rs.getString("display_name"),
                rs.getBoolean("report_auto"), rs.getBoolean("report_online"),
                rs.getBoolean("report_withdraw")), agentId).stream().findFirst();
    }

    public int updateControlConfig(long agentId, boolean reportAuto, boolean reportWithdraw) {
        return jdbc.update("""
                UPDATE agent
                   SET report_auto = ?, report_withdraw = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL AND status = 'ACTIVE' AND system_owned = FALSE
                """, reportAuto, reportWithdraw, agentId);
    }

    public record ReportConfigRow(long agentId, Long centerAgentId, String agentCode, String agentName,
                                  boolean reportAuto, boolean reportOnline, boolean reportWithdraw) {
    }
}
