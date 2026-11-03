package com.xupan.server.displayobserver;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class DisplayObserverBindingRepository {

    private final JdbcTemplate jdbc;

    public DisplayObserverBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BindingRow> findSingleton() {
        return jdbc.query("""
                SELECT id, agent_id, viewer_user_id, agent_id_snapshot, viewer_user_id_snapshot,
                       group_id_snapshot, machine_code_snapshot, machine_username_snapshot,
                       machine_display_name_snapshot, group_display_name_snapshot,
                       viewer_member_code_snapshot, viewer_display_name_snapshot,
                       public_id, enabled, version,
                       created_by, created_at, updated_by, updated_at
                  FROM display_observer_binding
                 WHERE id = 1
                """, (rs, rowNum) -> new BindingRow(
                rs.getLong("id"), rs.getObject("agent_id", Long.class), rs.getObject("viewer_user_id", Long.class),
                rs.getLong("agent_id_snapshot"), rs.getLong("viewer_user_id_snapshot"),
                rs.getLong("group_id_snapshot"), rs.getString("machine_code_snapshot"),
                rs.getString("machine_username_snapshot"), rs.getString("machine_display_name_snapshot"),
                rs.getString("group_display_name_snapshot"), rs.getString("viewer_member_code_snapshot"),
                rs.getString("viewer_display_name_snapshot"), rs.getString("public_id"),
                rs.getBoolean("enabled"), rs.getLong("version"),
                rs.getObject("created_by", Long.class), instant(rs.getTimestamp("created_at")),
                rs.getObject("updated_by", Long.class), instant(rs.getTimestamp("updated_at"))))
                .stream().findFirst();
    }

    public Optional<BindingRow> findSingletonForUpdate() {
        return jdbc.query("""
                SELECT id, agent_id, viewer_user_id, agent_id_snapshot, viewer_user_id_snapshot,
                       group_id_snapshot, machine_code_snapshot, machine_username_snapshot,
                       machine_display_name_snapshot, group_display_name_snapshot,
                       viewer_member_code_snapshot, viewer_display_name_snapshot,
                       public_id, enabled, version,
                       created_by, created_at, updated_by, updated_at
                  FROM display_observer_binding
                 WHERE id = 1
                   FOR UPDATE
                """, (rs, rowNum) -> new BindingRow(
                rs.getLong("id"), rs.getObject("agent_id", Long.class), rs.getObject("viewer_user_id", Long.class),
                rs.getLong("agent_id_snapshot"), rs.getLong("viewer_user_id_snapshot"),
                rs.getLong("group_id_snapshot"), rs.getString("machine_code_snapshot"),
                rs.getString("machine_username_snapshot"), rs.getString("machine_display_name_snapshot"),
                rs.getString("group_display_name_snapshot"), rs.getString("viewer_member_code_snapshot"),
                rs.getString("viewer_display_name_snapshot"), rs.getString("public_id"),
                rs.getBoolean("enabled"), rs.getLong("version"),
                rs.getObject("created_by", Long.class), instant(rs.getTimestamp("created_at")),
                rs.getObject("updated_by", Long.class), instant(rs.getTimestamp("updated_at"))))
                .stream().findFirst();
    }

    public Optional<BindingDetails> findDetails() {
        return jdbc.query("""
                SELECT b.agent_id, b.viewer_user_id, b.agent_id_snapshot, b.viewer_user_id_snapshot,
                       b.group_id_snapshot, b.machine_code_snapshot, b.machine_username_snapshot,
                       b.machine_display_name_snapshot, b.group_display_name_snapshot,
                       b.viewer_member_code_snapshot, b.viewer_display_name_snapshot,
                       b.public_id, b.enabled, b.version,
                       a.status machine_status, a.deleted_at machine_deleted_at, a.expires_at machine_expires_at,
                       a.system_owned, a.group_id, a.account_user_id, a.agent_code machine_code,
                       au.username machine_username, a.display_name machine_display_name,
                       g.display_name group_display_name,
                       g.status group_status, g.deleted_at group_deleted_at, g.expires_at group_expires_at,
                       gu.status group_user_status,
                       vu.display_name viewer_display_name, vu.status viewer_status,
                       d.player_kind, d.status viewer_account_status, d.agent_id viewer_agent_id,
                       bmode.run_mode, bmode.enabled behavior_enabled,
                       EXISTS (SELECT 1 FROM agent_game ag WHERE ag.agent_id = a.id AND ag.game_code = 'AU8') au8_enabled
                  FROM display_observer_binding b
                  LEFT JOIN agent a ON a.id = b.agent_id
                  LEFT JOIN sys_user au ON au.id = a.account_user_id
                  LEFT JOIN agent_group g ON g.id = a.group_id
                  LEFT JOIN sys_user gu ON gu.id = g.sys_user_id
                LEFT JOIN demo_user_account d ON d.sys_user_id = b.viewer_user_id
                  LEFT JOIN sys_user vu ON vu.id = b.viewer_user_id
                  LEFT JOIN test_player_behavior bmode ON bmode.account_id = d.id
                 WHERE b.id = 1
                """, (rs, rowNum) -> new BindingDetails(
                rs.getObject("agent_id", Long.class), rs.getObject("viewer_user_id", Long.class),
                rs.getLong("agent_id_snapshot"), rs.getLong("viewer_user_id_snapshot"),
                rs.getLong("group_id_snapshot"), rs.getObject("group_id", Long.class),
                rs.getString("machine_code_snapshot"), rs.getString("machine_username_snapshot"),
                rs.getString("machine_display_name_snapshot"), rs.getString("group_display_name_snapshot"),
                rs.getString("viewer_member_code_snapshot"), rs.getString("viewer_display_name_snapshot"),
                rs.getString("public_id"), rs.getBoolean("enabled"), rs.getLong("version"),
                rs.getString("machine_status"),
                rs.getTimestamp("machine_deleted_at") != null, instant(rs.getTimestamp("machine_expires_at")),
                rs.getBoolean("system_owned"), rs.getObject("account_user_id", Long.class),
                rs.getString("machine_code"),
                rs.getString("machine_username"),
                rs.getString("machine_display_name"), rs.getString("group_display_name"),
                rs.getString("group_status"), instant(rs.getTimestamp("group_expires_at")),
                rs.getTimestamp("group_deleted_at") != null,
                rs.getString("group_user_status"), rs.getString("viewer_display_name"),
                rs.getString("viewer_status"), rs.getString("player_kind"),
                rs.getString("viewer_account_status"), rs.getObject("viewer_agent_id", Long.class),
                rs.getString("run_mode"), rs.getObject("behavior_enabled", Boolean.class),
                rs.getBoolean("au8_enabled"))).stream().findFirst();
    }

    public Optional<BindingRow> findByPublicId(String publicId) {
        return jdbc.query("""
                SELECT id, agent_id, viewer_user_id, agent_id_snapshot, viewer_user_id_snapshot,
                       group_id_snapshot, machine_code_snapshot, machine_username_snapshot,
                       machine_display_name_snapshot, group_display_name_snapshot,
                       viewer_member_code_snapshot, viewer_display_name_snapshot,
                       public_id, enabled, version,
                       created_by, created_at, updated_by, updated_at
                  FROM display_observer_binding
                 WHERE id = 1 AND public_id = ? AND enabled = TRUE
                """, (rs, rowNum) -> new BindingRow(
                rs.getLong("id"), rs.getObject("agent_id", Long.class), rs.getObject("viewer_user_id", Long.class),
                rs.getLong("agent_id_snapshot"), rs.getLong("viewer_user_id_snapshot"),
                rs.getLong("group_id_snapshot"), rs.getString("machine_code_snapshot"),
                rs.getString("machine_username_snapshot"), rs.getString("machine_display_name_snapshot"),
                rs.getString("group_display_name_snapshot"), rs.getString("viewer_member_code_snapshot"),
                rs.getString("viewer_display_name_snapshot"), rs.getString("public_id"),
                rs.getBoolean("enabled"), rs.getLong("version"),
                rs.getObject("created_by", Long.class), instant(rs.getTimestamp("created_at")),
                rs.getObject("updated_by", Long.class), instant(rs.getTimestamp("updated_at"))), publicId)
                .stream().findFirst();
    }

    public Optional<MachineCandidate> findMachine(long agentId) {
        return jdbc.query("""
                SELECT a.id, au.username, a.display_name, g.display_name group_display_name
                  FROM agent a
                  JOIN agent_group g ON g.id = a.group_id
                  JOIN sys_user au ON au.id = a.account_user_id
                 WHERE a.id = ?
                """, (rs, rowNum) -> new MachineCandidate(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("group_display_name")), agentId)
                .stream().findFirst();
    }

    public List<MachineCandidate> findEligibleMachines() {
        return jdbc.query("""
                SELECT a.id, au.username, a.display_name, g.display_name group_display_name
                  FROM agent a
                  JOIN agent_group g ON g.id = a.group_id
                  JOIN sys_user au ON au.id = a.account_user_id
                  JOIN sys_user gu ON gu.id = g.sys_user_id
                 WHERE a.system_owned = FALSE AND a.deleted_at IS NULL AND a.status = 'ACTIVE'
                   AND a.group_id IS NOT NULL AND g.deleted_at IS NULL AND g.status = 'ACTIVE'
                   AND gu.status = 'ACTIVE' AND au.status = 'ACTIVE'
                   AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP(6))
                   AND (a.expires_at IS NULL OR a.expires_at > CURRENT_TIMESTAMP(6))
                   AND EXISTS (SELECT 1 FROM agent_game ag WHERE ag.agent_id = a.id AND ag.game_code = 'AU8')
                 ORDER BY g.display_name, au.username, a.id
                """, (rs, rowNum) -> new MachineCandidate(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("group_display_name")));
    }

    public boolean isEligibleMachine(long agentId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM agent a
                  JOIN agent_group g ON g.id = a.group_id
                  JOIN sys_user au ON au.id = a.account_user_id
                  JOIN sys_user gu ON gu.id = g.sys_user_id
                 WHERE a.id = ? AND a.system_owned = FALSE AND a.deleted_at IS NULL AND a.status = 'ACTIVE'
                   AND a.group_id IS NOT NULL AND g.deleted_at IS NULL AND g.status = 'ACTIVE'
                   AND gu.status = 'ACTIVE' AND au.status = 'ACTIVE'
                   AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP(6))
                   AND (a.expires_at IS NULL OR a.expires_at > CURRENT_TIMESTAMP(6))
                   AND EXISTS (SELECT 1 FROM agent_game ag WHERE ag.agent_id = a.id AND ag.game_code = 'AU8')
                """, Long.class, agentId);
        return count != null && count == 1;
    }

    public List<ViewerCandidate> findEligibleBots(long agentId) {
        return jdbc.query("""
                SELECT u.id user_id, u.display_name, d.member_code
                  FROM demo_user_account d
                  JOIN sys_user u ON u.id = d.sys_user_id
                  JOIN test_player_behavior b ON b.account_id = d.id
                 WHERE d.agent_id = ? AND d.player_kind = 'BOT' AND d.status = 'ACTIVE'
                   AND u.status = 'ACTIVE' AND b.run_mode = 'MANUAL' AND b.enabled = FALSE
                 ORDER BY u.display_name, u.id
                """, (rs, rowNum) -> new ViewerCandidate(
                rs.getLong("user_id"), rs.getString("display_name"), rs.getString("member_code")), agentId);
    }

    public boolean isValidBinding(long agentId, long viewerUserId) {
        return isValidBinding(agentId, viewerUserId, null);
    }

    public boolean isValidBinding(Long agentId, Long viewerUserId, Long groupIdSnapshot) {
        if (agentId == null || viewerUserId == null) return false;
        String groupConstraint = groupIdSnapshot == null ? "" : " AND a.group_id = ?";
        List<Object> arguments = new ArrayList<>(List.of(viewerUserId, agentId));
        if (groupIdSnapshot != null) arguments.add(groupIdSnapshot);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM agent a
                  JOIN agent_group g ON g.id = a.group_id
                  JOIN sys_user machine_user ON machine_user.id = a.account_user_id
                  JOIN sys_user group_user ON group_user.id = g.sys_user_id
                  JOIN agent_game ag ON ag.agent_id = a.id AND ag.game_code = 'AU8'
                  JOIN demo_user_account d ON d.agent_id = a.id AND d.sys_user_id = ?
                  JOIN sys_user viewer_user ON viewer_user.id = d.sys_user_id
                  JOIN test_player_behavior b ON b.account_id = d.id
                 WHERE a.id = ?
                   AND a.system_owned = FALSE AND a.deleted_at IS NULL AND a.status = 'ACTIVE'
                   AND a.group_id IS NOT NULL AND g.deleted_at IS NULL AND g.status = 'ACTIVE'
                   AND group_user.status = 'ACTIVE' AND machine_user.status = 'ACTIVE'
                   AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP(6))
                   AND (a.expires_at IS NULL OR a.expires_at > CURRENT_TIMESTAMP(6))
                   AND d.player_kind = 'BOT' AND d.status = 'ACTIVE' AND viewer_user.status = 'ACTIVE'
                   AND b.run_mode = 'MANUAL' AND b.enabled = FALSE
                """ + groupConstraint, Long.class, arguments.toArray());
        return count != null && count == 1;
    }

    public Optional<TargetSnapshot> lockEligibleTarget(long agentId, long viewerUserId) {
        return jdbc.query("""
                SELECT a.id agent_id, d.sys_user_id viewer_user_id, a.group_id, a.agent_code,
                       machine_user.username machine_username, a.display_name machine_display_name,
                       g.display_name group_display_name, d.member_code, viewer_user.display_name viewer_display_name
                  FROM agent a
                  JOIN agent_group g ON g.id = a.group_id
                  JOIN sys_user machine_user ON machine_user.id = a.account_user_id
                  JOIN sys_user group_user ON group_user.id = g.sys_user_id
                  JOIN agent_game ag ON ag.agent_id = a.id AND ag.game_code = 'AU8'
                  JOIN demo_user_account d ON d.agent_id = a.id AND d.sys_user_id = ?
                  JOIN sys_user viewer_user ON viewer_user.id = d.sys_user_id
                  JOIN test_player_behavior b ON b.account_id = d.id
                 WHERE a.id = ?
                   AND a.system_owned = FALSE AND a.deleted_at IS NULL AND a.status = 'ACTIVE'
                   AND a.group_id IS NOT NULL AND g.deleted_at IS NULL AND g.status = 'ACTIVE'
                   AND group_user.status = 'ACTIVE' AND machine_user.status = 'ACTIVE'
                   AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP(6))
                   AND (a.expires_at IS NULL OR a.expires_at > CURRENT_TIMESTAMP(6))
                   AND d.player_kind = 'BOT' AND d.status = 'ACTIVE' AND viewer_user.status = 'ACTIVE'
                   AND b.run_mode = 'MANUAL' AND b.enabled = FALSE
                   FOR UPDATE
                """, (rs, rowNum) -> new TargetSnapshot(
                rs.getLong("agent_id"), rs.getLong("viewer_user_id"), rs.getLong("group_id"),
                rs.getString("agent_code"), rs.getString("machine_username"), rs.getString("machine_display_name"),
                rs.getString("group_display_name"), rs.getString("member_code"),
                rs.getString("viewer_display_name")), viewerUserId, agentId).stream().findFirst();
    }

    public boolean lockViewerBehavior(long viewerUserId) {
        List<Long> rows = jdbc.query("""
                SELECT b.id
                  FROM test_player_behavior b
                  JOIN demo_user_account d ON d.id = b.account_id
                 WHERE d.sys_user_id = ? AND d.player_kind = 'BOT'
                   FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), viewerUserId);
        return !rows.isEmpty();
    }

    public boolean isBoundViewer(long viewerUserId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM display_observer_binding WHERE viewer_user_id = ?
                """, Long.class, viewerUserId);
        return count != null && count > 0;
    }

    public void insert(TargetSnapshot target, String publicId, long operatorUserId) {
        jdbc.update("""
                INSERT INTO display_observer_binding
                    (id, agent_id, viewer_user_id, agent_id_snapshot, viewer_user_id_snapshot, group_id_snapshot,
                     machine_code_snapshot, machine_username_snapshot, machine_display_name_snapshot,
                     group_display_name_snapshot, viewer_member_code_snapshot, viewer_display_name_snapshot,
                     public_id, enabled, version,
                     created_by, created_at, updated_by, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, 1, ?, CURRENT_TIMESTAMP(6), ?, CURRENT_TIMESTAMP(6))
                """, target.agentId(), target.viewerUserId(), target.agentId(), target.viewerUserId(),
                target.groupId(), target.machineCode(), target.machineUsername(), target.machineDisplayName(),
                target.groupDisplayName(), target.viewerMemberCode(), target.viewerDisplayName(),
                publicId, operatorUserId, operatorUserId);
    }

    public int rebind(long expectedVersion, TargetSnapshot target, String publicId, long operatorUserId) {
        return jdbc.update("""
                UPDATE display_observer_binding
                   SET agent_id = ?, viewer_user_id = ?, agent_id_snapshot = ?, viewer_user_id_snapshot = ?,
                       group_id_snapshot = ?, machine_code_snapshot = ?, machine_username_snapshot = ?,
                       machine_display_name_snapshot = ?, group_display_name_snapshot = ?,
                       viewer_member_code_snapshot = ?, viewer_display_name_snapshot = ?,
                       public_id = ?, enabled = FALSE,
                       version = version + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = 1 AND version = ? AND enabled = FALSE
                """, target.agentId(), target.viewerUserId(), target.agentId(), target.viewerUserId(),
                target.groupId(), target.machineCode(), target.machineUsername(), target.machineDisplayName(),
                target.groupDisplayName(), target.viewerMemberCode(), target.viewerDisplayName(),
                publicId, operatorUserId, expectedVersion);
    }

    public int update(long expectedVersion, boolean enabled, String publicId, long operatorUserId) {
        return jdbc.update("""
                UPDATE display_observer_binding
                   SET enabled = ?, public_id = ?, version = version + 1,
                       updated_by = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = 1 AND version = ?
                """, enabled, publicId, operatorUserId, expectedVersion);
    }

    public void audit(String operation, long agentIdSnapshot, long viewerUserIdSnapshot, long operatorUserId,
                      long bindingVersion, String reason) {
        jdbc.update("""
                INSERT INTO display_observer_binding_audit
                    (operation, agent_id_snapshot, viewer_user_id_snapshot, operator_user_id,
                     binding_version, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, operation, agentIdSnapshot, viewerUserIdSnapshot, operatorUserId, bindingVersion, reason);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record BindingRow(long id, Long agentId, Long viewerUserId,
                             long agentIdSnapshot, long viewerUserIdSnapshot, long groupIdSnapshot,
                             String machineCodeSnapshot, String machineUsernameSnapshot,
                             String machineDisplayNameSnapshot, String groupDisplayNameSnapshot,
                             String viewerMemberCodeSnapshot, String viewerDisplayNameSnapshot,
                             String publicId, boolean enabled, long version, Long createdBy, Instant createdAt,
                             Long updatedBy, Instant updatedAt) {
    }

    public record TargetSnapshot(long agentId, long viewerUserId, long groupId, String machineCode,
                                 String machineUsername, String machineDisplayName, String groupDisplayName,
                                 String viewerMemberCode, String viewerDisplayName) {
    }

    public record BindingDetails(Long agentId, Long viewerUserId,
                                 long agentIdSnapshot, long viewerUserIdSnapshot, long groupIdSnapshot,
                                 Long groupId, String machineCodeSnapshot, String machineUsernameSnapshot,
                                 String machineDisplayNameSnapshot, String groupDisplayNameSnapshot,
                                 String viewerMemberCodeSnapshot, String viewerDisplayNameSnapshot,
                                 String publicId, boolean enabled, long version,
                                 String machineStatus, boolean machineDeleted, Instant machineExpiresAt,
                                 boolean systemOwned, Long accountUserId, String machineCode,
                                 String machineUsername, String machineDisplayName, String groupDisplayName,
                                 String groupStatus, Instant groupExpiresAt, boolean groupDeleted, String groupUserStatus,
                                 String viewerDisplayName, String viewerStatus, String playerKind,
                                 String viewerAccountStatus, Long viewerAgentId,
                                 String runMode, Boolean behaviorEnabled, boolean au8Enabled) {
    }

    public record MachineCandidate(long id, String username, String displayName, String groupDisplayName) {
    }

    public record ViewerCandidate(long userId, String displayName, String memberCode) {
    }
}
