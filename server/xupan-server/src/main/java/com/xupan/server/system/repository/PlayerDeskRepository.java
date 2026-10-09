package com.xupan.server.system.repository;

import com.xupan.server.system.service.PlayerDeskScopeService.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PlayerDeskRepository {
    private final JdbcTemplate jdbc;

    public PlayerDeskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全局汇总；调用方必须先解析 {@code Scope}，禁止再暴露“默认全局”的无范围重载。 */
    public Summary summary(String kind, String status, String keyword, boolean includeDeleted, Scope scope) {
        String where = where(kind, status, keyword, includeDeleted, scope);
        Object[] args = args(kind, status, keyword, includeDeleted, scope);
        return jdbc.queryForObject("SELECT COALESCE(SUM(a.balance),0) total_points, "
                        + "COALESCE(SUM(CASE WHEN a.player_kind='NORMAL' THEN 1 ELSE 0 END),0) normal_count, "
                        + "COALESCE(SUM(CASE WHEN a.player_kind='BOT' THEN 1 ELSE 0 END),0) bot_count "
                        + from() + where,
                (rs, rowNum) -> new Summary(rs.getBigDecimal("total_points"), rs.getLong("normal_count"), rs.getLong("bot_count")), args);
    }

    public List<PlayerRow> findPage(String kind, String status, String keyword, int page, int pageSize,
                                    boolean includeDeleted, Scope scope) {
        String where = where(kind, status, keyword, includeDeleted, scope);
        String sql = "SELECT u.id user_id, u.username, u.internal_code, u.display_name, u.avatar_key, u.status user_status, "
                + "u.created_at, u.last_login_at, u.user_type, a.id account_id, a.user_code, a.member_code, a.display_name account_display_name, "
                + "u.auth_mode, "
                + "a.balance, a.status account_status, a.player_kind, b.enabled behavior_enabled, "
                + "(SELECT MAX(x.updated_at) FROM test_player_action x WHERE x.user_id=u.id) last_action_at "
                + from() + where + " ORDER BY a.balance DESC, a.id DESC LIMIT ? OFFSET ?";
        Object[] base = args(kind, status, keyword, includeDeleted, scope);
        Object[] all = java.util.Arrays.copyOf(base, base.length + 2);
        all[base.length] = pageSize;
        all[base.length + 1] = (page - 1L) * pageSize;
        return jdbc.query(sql, this::map, all);
    }

    public long count(String kind, String status, String keyword, boolean includeDeleted, Scope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) " + from() + where(kind, status, keyword, includeDeleted, scope), Long.class,
                args(kind, status, keyword, includeDeleted, scope));
    }

    /**
     * 内部路径（机器人调度、{@code /agent} 兼容接口）使用的全局读取。
     * 运营后台（{@code /console}）必须调用带 {@link Scope} 的重载，禁止用它绕过机器隔离。
     */
    public Optional<PlayerRow> findByUserId(long userId) {
        return findByUserId(userId, false, Scope.Global.INSTANCE);
    }

    public Optional<PlayerRow> findByUserId(long userId, boolean includeDeleted, Scope scope) {
        java.util.ArrayList<Object> values = new java.util.ArrayList<>(scope.agentIdParameters());
        values.add(userId);
        return jdbc.query("SELECT u.id user_id, u.username, u.internal_code, u.display_name, u.avatar_key, u.status user_status, "
                        + "u.created_at, u.last_login_at, u.user_type, a.id account_id, a.user_code, a.member_code, a.display_name account_display_name, "
                        + "u.auth_mode, "
                        + "a.balance, a.status account_status, a.player_kind, b.enabled behavior_enabled, "
                        + "(SELECT MAX(x.updated_at) FROM test_player_action x WHERE x.user_id=u.id) last_action_at "
                        + from() + scope.agentIdCondition() + " AND u.id=?" + (includeDeleted ? "" : " AND u.status <> 'DELETED' AND a.status <> 'DELETED'"),
                this::map, values.toArray()).stream().findFirst();
    }

    public Optional<Behavior> findBehavior(long userId) {
        return jdbc.query("SELECT b.*, COALESCE(ag.chase_enabled,FALSE) machine_chase_enabled "
                        + "FROM test_player_behavior b JOIN demo_user_account a ON a.id=b.account_id "
                        + "LEFT JOIN agent ag ON ag.id=a.agent_id WHERE a.sys_user_id=?",
                this::mapBehavior, userId).stream().findFirst();
    }

    public Behavior ensureBehavior(long accountId) {
        try {
            jdbc.update("INSERT INTO test_player_behavior(account_id) VALUES (?)", accountId);
        } catch (DuplicateKeyException ignored) {
            // The unique account_id makes creation idempotent.
        }
        return jdbc.queryForObject("SELECT b.*, COALESCE(ag.chase_enabled,FALSE) machine_chase_enabled "
                        + "FROM test_player_behavior b JOIN demo_user_account a ON a.id=b.account_id "
                        + "LEFT JOIN agent ag ON ag.id=a.agent_id WHERE b.account_id=?",
                this::mapBehavior, accountId);
    }

    public Behavior updateBehavior(long userId, String mode, BigDecimal min, BigDecimal max,
                                   String stakeRangeCode, boolean chaseEnabled, boolean feidanEnabled) {
        PlayerRow row = findByUserId(userId).orElseThrow();
        ensureBehavior(row.accountId());
        jdbc.update("UPDATE test_player_behavior SET run_mode=?, enabled=?, stake_min=?, stake_max=?, "
                        + "stake_range_code=?, player_chase_enabled=?, player_feidan_enabled=?, "
                        + "version=version+1, updated_at=CURRENT_TIMESTAMP(6) WHERE account_id=?",
                mode, "AUTOMATIC".equals(mode), min, max, stakeRangeCode, chaseEnabled, feidanEnabled, row.accountId());
        return ensureBehavior(row.accountId());
    }

    private Behavior mapBehavior(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Behavior(rs.getLong("id"), rs.getLong("account_id"), rs.getString("run_mode"),
                rs.getBoolean("enabled"), rs.getBigDecimal("stake_min"), rs.getBigDecimal("stake_max"),
                instant(rs.getTimestamp("next_run_at")), rs.getString("last_issue_number"),
                rs.getString("last_error_code"), rs.getString("last_error_message"), rs.getLong("version"),
                instant(rs.getTimestamp("updated_at")), rs.getString("stake_range_code"),
                rs.getBoolean("player_chase_enabled"), rs.getBoolean("player_feidan_enabled"),
                rs.getBoolean("machine_chase_enabled"));
    }

    public int updateStatus(long userId, String status) {
        return jdbc.update("UPDATE demo_user_account SET status=?, updated_at=CURRENT_TIMESTAMP WHERE sys_user_id=?", status, userId);
    }

    public int softDelete(long userId) {
        return jdbc.update("UPDATE demo_user_account SET status='DELETED', updated_at=CURRENT_TIMESTAMP WHERE sys_user_id=?", userId);
    }

    public int disableBehavior(long userId) {
        return jdbc.update("UPDATE test_player_behavior SET enabled=FALSE, version=version+1, updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE account_id=(SELECT id FROM demo_user_account WHERE sys_user_id=?)", userId);
    }

    public List<ActionRow> actions(long userId, int limit) {
        return jdbc.query("SELECT * FROM test_player_action WHERE user_id=? ORDER BY id DESC LIMIT ?", (rs, n) -> new ActionRow(
                rs.getLong("id"), rs.getString("issue_number"), rs.getInt("action_no"), rs.getString("action_type"),
                rs.getString("source_text"), rs.getString("status"), rs.getInt("attempts"), rs.getString("error_code"),
                rs.getString("error_message"), (Long) rs.getObject("message_id"), (Long) rs.getObject("bet_id"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))), userId, Math.min(Math.max(limit, 1), 100));
    }

    private String from() {
        return " FROM sys_user u JOIN demo_user_account a ON a.sys_user_id=u.id "
                + "LEFT JOIN test_player_behavior b ON b.account_id=a.id WHERE a.identity_type IN ('REAL','TEST') "
                + "AND a.player_kind IN ('NORMAL','BOT')";
    }

    private String where(String kind, String status, String keyword, boolean includeDeleted, Scope scope) {
        StringBuilder sql = new StringBuilder(scope.agentIdCondition());
        if (!includeDeleted) sql.append(" AND u.status <> 'DELETED' AND a.status <> 'DELETED'");
        if (kind != null && !kind.isBlank()) sql.append(" AND a.player_kind=?");
        if (status != null && !status.isBlank()) sql.append(" AND u.status=? AND a.status=?");
        if (keyword != null && !keyword.isBlank()) sql.append(" AND (u.username LIKE ? OR u.internal_code LIKE ? OR a.member_code LIKE ? OR u.display_name LIKE ? OR a.user_code LIKE ?)");
        return sql.toString();
    }

    private Object[] args(String kind, String status, String keyword, boolean includeDeleted, Scope scope) {
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(scope.agentIdParameters());
        if (kind != null && !kind.isBlank()) args.add(kind);
        if (status != null && !status.isBlank()) { args.add(status); args.add(status); }
        if (keyword != null && !keyword.isBlank()) {
            String value = "%" + keyword.trim() + "%";
            args.add(value); args.add(value); args.add(value); args.add(value); args.add(value);
        }
        return args.toArray();
    }

    private PlayerRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PlayerRow(rs.getLong("user_id"), rs.getLong("account_id"), rs.getString("username"),
                rs.getString("internal_code"), rs.getString("user_code"), rs.getString("member_code"),
                rs.getString("display_name"), rs.getString("avatar_key"), rs.getString("auth_mode"),
                rs.getString("user_status"), rs.getString("account_status"), rs.getString("player_kind"),
                rs.getString("user_type"), rs.getBigDecimal("balance"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_login_at")), rs.getBoolean("behavior_enabled"), instant(rs.getTimestamp("last_action_at")));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record Summary(BigDecimal totalPoints, long normalCount, long botCount) {}
    public record PlayerRow(long userId, long accountId, String username, String internalCode, String userCode,
                            String memberCode, String displayName, String avatarKey, String authMode,
                            String userStatus, String accountStatus, String playerKind, String userType,
                            BigDecimal balance, Instant createdAt, Instant lastLoginAt, boolean behaviorEnabled, Instant lastActionAt) {}
    public record Behavior(long id, long accountId, String mode, boolean enabled, BigDecimal stakeMin,
                           BigDecimal stakeMax, Instant nextRunAt, String lastIssueNumber,
                           String lastErrorCode, String lastErrorMessage, long version, Instant updatedAt,
                           String stakeRangeCode, boolean chaseEnabled, boolean feidanEnabled,
                           boolean machineChaseEnabled) {}
    public record ActionRow(long id, String issueNumber, int actionNo, String actionType, String sourceText, String status,
                            int attempts, String errorCode, String errorMessage, Long messageId, Long betId,
                            Instant createdAt, Instant updatedAt) {}
}
