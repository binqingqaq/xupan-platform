package com.xupan.server.agent.repository;

import com.xupan.server.agent.domain.Agent;
import com.xupan.server.agent.domain.AgentGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentRepository {

    private static final String AGENT_SELECT = """
            SELECT ag.id, ag.agent_code, ag.display_name, ag.score, ag.group_id, ag.account_user_id,
                   ag.system_owned, ag.status, ag.created_by, ag.created_at, ag.updated_at,
                   g.group_code, g.display_name group_display_name, u.username account_username,
                   COALESCE(SUM(CASE WHEN a.player_kind = 'NORMAL' AND a.status <> 'DELETED' THEN 1 ELSE 0 END), 0) normal_count,
                   COALESCE(SUM(CASE WHEN a.player_kind = 'BOT' AND a.status <> 'DELETED' THEN 1 ELSE 0 END), 0) bot_count,
                   COALESCE(SUM(CASE WHEN a.status <> 'DELETED' THEN a.balance ELSE 0 END), 0) total_balance
              FROM agent ag
              LEFT JOIN agent_group g ON g.id = ag.group_id
              LEFT JOIN sys_user u ON u.id = ag.account_user_id
              LEFT JOIN demo_user_account a ON a.agent_id = ag.id
            """;

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long defaultAgentId() {
        Long id = jdbc.queryForObject(
                "SELECT id FROM agent WHERE agent_code = 'PLATFORM_DIRECT' AND system_owned = TRUE AND deleted_at IS NULL",
                Long.class);
        if (id == null) {
            throw new IllegalStateException("平台直属代理不存在");
        }
        return id;
    }

    public List<AgentGroup> findGroups() {
        return jdbc.query("""
                SELECT id, group_code, display_name, status, created_by, created_at, updated_at
                  FROM agent_group
                 WHERE deleted_at IS NULL
                 ORDER BY id
                """, (rs, rowNum) -> new AgentGroup(
                rs.getLong("id"), rs.getString("group_code"), rs.getString("display_name"),
                rs.getString("status"), nullableLong(rs, "created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))));
    }

    public Optional<AgentGroup> findGroup(long groupId) {
        return jdbc.query("""
                SELECT id, group_code, display_name, status, created_by, created_at, updated_at
                  FROM agent_group WHERE id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> new AgentGroup(
                rs.getLong("id"), rs.getString("group_code"), rs.getString("display_name"),
                rs.getString("status"), nullableLong(rs, "created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))),
                groupId).stream().findFirst();
    }

    public long createGroup(String code, String displayName, Long createdBy) {
        jdbc.update("""
                INSERT INTO agent_group (group_code, display_name, status, created_by)
                VALUES (?, ?, 'ACTIVE', ?)
                """, code, displayName, createdBy);
        Long id = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, code);
        if (id == null) throw new IllegalStateException("创建渠道组后未取得 ID");
        return id;
    }

    public int updateGroupStatus(long groupId, String status) {
        return jdbc.update("""
                UPDATE agent_group SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, status, groupId);
    }

    public long countActiveAgentsInGroup(long groupId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent WHERE group_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, Long.class, groupId);
        return count == null ? 0L : count;
    }

    public List<AgentRow> findAgents(String status, String keyword, Long groupId, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(AGENT_SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendAgentFilter(sql, args, status, keyword, groupId);
        sql.append(" GROUP BY ag.id, ag.agent_code, ag.display_name, ag.group_id, ag.account_user_id, ")
                .append("ag.system_owned, ag.status, ag.created_by, ag.created_at, ag.updated_at, ")
                .append("g.group_code, g.display_name, u.username ")
                .append(" ORDER BY ag.system_owned DESC, ag.id LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbc.query(sql.toString(), this::mapAgentRow, args.toArray());
    }

    public long countAgents(String status, String keyword, Long groupId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM agent ag WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendAgentFilter(sql, args, status, keyword, groupId);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public Optional<AgentRow> findAgent(long agentId) {
        return jdbc.query(AGENT_SELECT + " WHERE ag.id = ? AND ag.deleted_at IS NULL "
                        + "GROUP BY ag.id, ag.agent_code, ag.display_name, ag.group_id, ag.account_user_id, "
                        + "ag.system_owned, ag.status, ag.created_by, ag.created_at, ag.updated_at, "
                        + "g.group_code, g.display_name, u.username",
                this::mapAgentRow, agentId).stream().findFirst();
    }

    public Optional<Agent> findAgentEntity(long agentId) {
        return jdbc.query("""
                SELECT id, agent_code, display_name, group_id, account_user_id, system_owned,
                       status, created_by, created_at, updated_at
                  FROM agent WHERE id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> mapAgent(rs), agentId).stream().findFirst();
    }

    public Optional<Agent> findAgentByAccountUserId(long accountUserId) {
        return jdbc.query("""
                SELECT id, agent_code, display_name, group_id, account_user_id, system_owned,
                       status, created_by, created_at, updated_at
                  FROM agent WHERE account_user_id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> mapAgent(rs), accountUserId).stream().findFirst();
    }

    public boolean agentCodeExists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM agent WHERE agent_code = ? AND deleted_at IS NULL)", Boolean.class, code));
    }

    public long createAgent(String code, String displayName, Long groupId, Long accountUserId, Long createdBy) {
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, group_id, account_user_id,
                                   system_owned, status, created_by)
                VALUES (?, ?, ?, ?, FALSE, 'ACTIVE', ?)
                """, code, displayName, groupId, accountUserId, createdBy);
        Long id = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = ?", Long.class, code);
        if (id == null) throw new IllegalStateException("创建代理后未取得 ID");
        return id;
    }

    public int updateAgentStatus(long agentId, String status) {
        return jdbc.update("""
                UPDATE agent SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, status, agentId);
    }

    public List<PlayerAssignmentRow> findPlayerAssignments(String keyword, Long agentId,
                                                           int page, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id user_id, u.display_name, a.member_code, a.player_kind, u.status user_status,
                       a.status account_status, a.balance, ag.id agent_id, ag.agent_code, ag.display_name agent_name,
                       ag.system_owned
                  FROM demo_user_account a
                  JOIN sys_user u ON u.id = a.sys_user_id
                  JOIN agent ag ON ag.id = a.agent_id
                 WHERE a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind IN ('NORMAL', 'BOT')
                """);
        List<Object> args = new ArrayList<>();
        appendPlayerFilter(sql, args, keyword, agentId);
        sql.append(" ORDER BY ag.system_owned DESC, u.id DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbc.query(sql.toString(), this::mapAssignment, args.toArray());
    }

    public long countPlayerAssignments(String keyword, Long agentId) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM demo_user_account a
                  JOIN sys_user u ON u.id = a.sys_user_id
                  JOIN agent ag ON ag.id = a.agent_id
                 WHERE a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind IN ('NORMAL', 'BOT')
                """);
        List<Object> args = new ArrayList<>();
        appendPlayerFilter(sql, args, keyword, agentId);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public Optional<Long> findCurrentAgentId(long userId) {
        return jdbc.queryForList("SELECT agent_id FROM demo_user_account WHERE sys_user_id = ?",
                Long.class, userId).stream().findFirst();
    }

    public Optional<Long> findCurrentAgentIdForUpdate(long userId) {
        return jdbc.query("""
                SELECT agent_id FROM demo_user_account
                 WHERE sys_user_id = ?
                   FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("agent_id"), userId).stream().findFirst();
    }

    public int assignPlayerToAgent(long userId, long agentId) {
        return jdbc.update("""
                UPDATE demo_user_account
                   SET agent_id = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE sys_user_id = ?
                """, agentId, userId);
    }

    public long findBotCountLimit(long agentId) {
        Long limit = jdbc.queryForObject(
                "SELECT bot_count FROM agent WHERE id = ? AND deleted_at IS NULL",
                Long.class, agentId);
        return limit == null ? 0L : limit;
    }

    public BigDecimal findScore(long agentId) {
        return jdbc.queryForObject("SELECT score FROM agent WHERE id = ? AND deleted_at IS NULL",
                BigDecimal.class, agentId);
    }

    public int debitScore(long agentId, BigDecimal amount) {
        return jdbc.update("""
                UPDATE agent
                   SET score = score - ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL AND score >= ?
                """, amount, agentId, amount);
    }

    public int creditScore(long agentId, BigDecimal amount) {
        return jdbc.update("""
                UPDATE agent
                   SET score = score + ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL
                """, amount, agentId);
    }

    public int assignPlatformDirectPlayer(long userId, long targetAgentId) {
        long defaultAgentId = defaultAgentId();
        return jdbc.update("""
                UPDATE demo_user_account
                   SET agent_id = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE sys_user_id = ? AND agent_id = ?
                """, targetAgentId, userId, defaultAgentId);
    }

    public List<AgentPlayerRow> findPlayersByAgent(long agentId, String kind, String keyword,
                                                   int page, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id user_id, u.internal_code, u.display_name, u.status user_status,
                       a.id account_id, a.member_code, a.player_kind, a.balance, a.status account_status,
                       u.last_login_at, u.created_at
                  FROM demo_user_account a
                  JOIN sys_user u ON u.id = a.sys_user_id
                 WHERE a.agent_id = ?
                   AND EXISTS (SELECT 1 FROM agent ag WHERE ag.id = a.agent_id AND ag.deleted_at IS NULL)
                   AND a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind IN ('NORMAL', 'BOT')
                   AND u.status <> 'DELETED' AND a.status <> 'DELETED'
                """);
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND a.player_kind = ?");
            args.add(kind.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim() + "%";
            sql.append(" AND (u.display_name LIKE ? OR u.internal_code LIKE ? OR a.member_code LIKE ?)");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY u.id DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbc.query(sql.toString(), (rs, rowNum) -> new AgentPlayerRow(
                        rs.getLong("user_id"), rs.getLong("account_id"), rs.getString("internal_code"),
                        rs.getString("display_name"), rs.getString("member_code"), rs.getString("player_kind"),
                        rs.getString("user_status"), rs.getString("account_status"),
                        rs.getBigDecimal("balance"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("last_login_at"))),
                args.toArray());
    }

    public Optional<AgentPlayerRow> findPlayerByAgentAndUser(long agentId, long userId) {
        return jdbc.query("""
                SELECT u.id user_id, u.internal_code, u.display_name, u.status user_status,
                       a.id account_id, a.member_code, a.player_kind, a.balance, a.status account_status,
                       u.last_login_at, u.created_at
                  FROM demo_user_account a
                  JOIN sys_user u ON u.id = a.sys_user_id
                 WHERE a.agent_id = ? AND u.id = ?
                   AND a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind IN ('NORMAL', 'BOT')
                   AND u.status <> 'DELETED' AND a.status <> 'DELETED'
                """, (rs, rowNum) -> new AgentPlayerRow(
                        rs.getLong("user_id"), rs.getLong("account_id"), rs.getString("internal_code"),
                        rs.getString("display_name"), rs.getString("member_code"), rs.getString("player_kind"),
                        rs.getString("user_status"), rs.getString("account_status"),
                        rs.getBigDecimal("balance"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("last_login_at"))), agentId, userId).stream().findFirst();
    }

    public long countPlayersByAgent(long agentId, String kind, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM demo_user_account a
                  JOIN sys_user u ON u.id = a.sys_user_id
                 WHERE a.agent_id = ?
                   AND EXISTS (SELECT 1 FROM agent ag WHERE ag.id = a.agent_id AND ag.deleted_at IS NULL)
                   AND a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind IN ('NORMAL', 'BOT')
                   AND u.status <> 'DELETED' AND a.status <> 'DELETED'
                """);
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND a.player_kind = ?");
            args.add(kind.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim() + "%";
            sql.append(" AND (u.display_name LIKE ? OR u.internal_code LIKE ? OR a.member_code LIKE ?)");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    private void appendAgentFilter(StringBuilder sql, List<Object> args, String status,
                                   String keyword, Long groupId) {
        sql.append(" AND ag.deleted_at IS NULL");
        if (status != null && !status.isBlank()) {
            sql.append(" AND ag.status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (groupId != null) {
            sql.append(" AND ag.group_id = ?");
            args.add(groupId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim() + "%";
            sql.append(" AND (ag.agent_code LIKE ? OR ag.display_name LIKE ? OR u.username LIKE ?)");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
    }

    private void appendPlayerFilter(StringBuilder sql, List<Object> args, String keyword, Long agentId) {
        sql.append(" AND ag.deleted_at IS NULL");
        if (agentId != null) {
            sql.append(" AND a.agent_id = ?");
            args.add(agentId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim() + "%";
            sql.append(" AND (u.display_name LIKE ? OR a.member_code LIKE ?)");
            args.add(pattern);
            args.add(pattern);
        }
    }

    private AgentRow mapAgentRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentRow(rs.getLong("id"), rs.getString("agent_code"), rs.getString("display_name"),
                rs.getBigDecimal("score"), nullableLong(rs, "group_id"), rs.getString("group_code"),
                rs.getString("group_display_name"),
                nullableLong(rs, "account_user_id"), rs.getString("account_username"),
                rs.getBoolean("system_owned"), rs.getString("status"),
                rs.getLong("normal_count"), rs.getLong("bot_count"), rs.getBigDecimal("total_balance"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private Agent mapAgent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Agent(rs.getLong("id"), rs.getString("agent_code"), rs.getString("display_name"),
                nullableLong(rs, "group_id"), nullableLong(rs, "account_user_id"),
                rs.getBoolean("system_owned"), rs.getString("status"), nullableLong(rs, "created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private PlayerAssignmentRow mapAssignment(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PlayerAssignmentRow(rs.getLong("user_id"), rs.getString("display_name"),
                rs.getString("member_code"), rs.getString("player_kind"), rs.getString("user_status"),
                rs.getString("account_status"), rs.getBigDecimal("balance"), rs.getLong("agent_id"),
                rs.getString("agent_code"), rs.getString("agent_name"), rs.getBoolean("system_owned"));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record AgentRow(long id, String code, String displayName, BigDecimal score, Long groupId, String groupCode,
                           String groupDisplayName, Long accountUserId, String accountUsername,
                           boolean systemOwned, String status, long normalCount, long botCount,
                           BigDecimal totalBalance, Instant createdAt, Instant updatedAt) {
    }

    public record PlayerAssignmentRow(long userId, String displayName, String memberCode, String playerKind,
                                      String userStatus, String accountStatus, BigDecimal balance,
                                      long agentId, String agentCode, String agentName, boolean systemOwned) {
    }

    public record AgentPlayerRow(long userId, long accountId, String internalCode, String displayName,
                                 String memberCode, String playerKind, String userStatus, String accountStatus,
                                 BigDecimal balance, Instant createdAt, Instant lastLoginAt) {
    }
}
