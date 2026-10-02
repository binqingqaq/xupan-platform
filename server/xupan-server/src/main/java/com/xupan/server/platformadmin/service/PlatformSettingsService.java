package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PlatformSettingsService {

    private static final String READ_PERMISSION = "PLATFORM_SETTINGS_READ";
    private static final String WRITE_PERMISSION = "PLATFORM_SETTINGS_WRITE";
    private static final String DELETE_ALL_ACCOUNTS_CONFIRM = "DELETE_ALL_ACCOUNTS";
    private static final String CLEAR_DATA_CONFIRM = "CONFIRM_CLEAR";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;

    public PlatformSettingsService(JdbcTemplate jdbc, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public SettingsView get(long operator) {
        requirePermission(operator, READ_PERMISSION);
        return requireSettings();
    }

    @Transactional(readOnly = true)
    public PublicSettingsView publicSettings() {
        SettingsView settings = requireSettings();
        return new PublicSettingsView(settings.siteTitle(), settings.announcement(), settings.chatWarning(),
                settings.headerEnabled(), settings.statusBarEnabled(), settings.keyboardMode(), settings.version());
    }

    @Transactional
    public SettingsView update(SettingsInput input, long expectedVersion, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        SettingsView current = requireSettings();
        if (expectedVersion != current.version()) {
            throw BusinessException.conflict("PLATFORM_SETTINGS_CONCURRENT_UPDATE", "设置已被其他操作修改，请刷新后重试");
        }
        String title = required(input.siteTitle(), "群聊标题不能为空", 128);
        String announcement = optional(input.announcement(), 4000, "公告不能超过 4000 个字符");
        String domainLinks = optional(input.domainLinks(), 4000, "域名链接不能超过 4000 个字符");
        String warning = optional(input.chatWarning(), 2000, "群聊警告不能超过 2000 个字符");
        String information = optional(input.information(), 4000, "信息不能超过 4000 个字符");
        int updated = jdbc.update("""
                UPDATE platform_setting
                   SET site_title = ?, announcement = ?, domain_links = ?, chat_warning = ?,
                       information = ?, header_enabled = ?, status_bar_enabled = ?,
                       keyboard_mode = ?, version = version + 1, updated_by = ?, updated_at = ?
                 WHERE id = 1 AND version = ?
                """, title, announcement, domainLinks, warning, information,
                input.headerEnabled(), input.statusBarEnabled(), input.keyboardMode(),
                operator, Timestamp.from(Instant.now()), expectedVersion);
        if (updated != 1) {
            throw BusinessException.conflict("PLATFORM_SETTINGS_CONCURRENT_UPDATE", "设置已被其他操作修改，请刷新后重试");
        }
        jdbc.update("""
                UPDATE chat_room SET display_name = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE room_code = 'main'
                """, title);
        audit(operator, title);
        return requireSettings();
    }

    @Transactional(readOnly = true)
    public DeleteAllAccountsResult previewDeleteAllAccounts(String confirm, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        requireDeleteAllAccountsConfirm(confirm);
        DeleteAllAccountsCounts counts = deleteAllAccountsCounts();
        return new DeleteAllAccountsResult(true, counts, "预检完成");
    }

    @Transactional
    public DeleteAllAccountsResult deleteAllAccounts(String confirm, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        requireDeleteAllAccountsConfirm(confirm);
        DeleteAllAccountsCounts counts = deleteAllAccountsCounts();
        Instant now = Instant.now();
        Timestamp revokedAt = Timestamp.from(now);

        jdbc.update("""
                UPDATE agent_group
                   SET status='DISABLED', deleted_at=?, deleted_by=?, updated_at=?
                 WHERE deleted_at IS NULL
                """, revokedAt, operator, revokedAt);
        jdbc.update("""
                UPDATE agent
                   SET status='DISABLED', board_open=FALSE, deleted_at=?, deleted_by=?, updated_at=?
                 WHERE system_owned=FALSE AND deleted_at IS NULL
                """, revokedAt, operator, revokedAt);
        jdbc.update("""
                UPDATE demo_user_account
                   SET status='DELETED', updated_at=?
                 WHERE status <> 'DELETED'
                """, revokedAt);
        jdbc.update("""
                UPDATE sys_user
                   SET status='DELETED', failed_login_count=0, locked_until=NULL,
                       security_version=security_version + 1, updated_at=?
                 WHERE id <> ?
                   AND id NOT IN (
                       SELECT account_user_id FROM agent
                        WHERE system_owned=TRUE AND account_user_id IS NOT NULL
                   )
                """, revokedAt, operator);
        jdbc.update("""
                UPDATE auth_session
                   SET revoked_at=?, last_seen_at=COALESCE(last_seen_at, ?)
                 WHERE revoked_at IS NULL
                   AND user_id <> ?
                   AND user_id NOT IN (
                       SELECT account_user_id FROM agent
                        WHERE system_owned=TRUE AND account_user_id IS NOT NULL
                   )
                """, revokedAt, revokedAt, operator);
        auditDeleteAllAccounts(operator, counts);
        return new DeleteAllAccountsResult(false, counts,
                "已软删除子账号 " + counts.admins() + " 个，机器 " + counts.robots()
                        + " 个，玩家/托 " + counts.players() + " 个");
    }

    @Transactional(readOnly = true)
    public ClearDataResult previewClearData(String time, String confirm, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        requireClearDataConfirm(confirm);
        Instant cutoff = parseCutoff(time);
        ClearDataCounts counts = clearDataCounts(cutoff);
        return new ClearDataResult(true, cutoff, counts, counts.total(), "预检完成");
    }

    @Transactional
    public ClearDataResult clearData(String time, String confirm, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        requireClearDataConfirm(confirm);
        Instant cutoff = parseCutoff(time);
        Timestamp timestamp = Timestamp.from(cutoff);
        ClearDataCounts counts = clearDataCounts(cutoff);
        long deleted = 0;

        deleted += jdbc.update("""
                DELETE FROM test_player_action
                 WHERE created_at < ?
                    OR bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                """, timestamp, timestamp);
        deleted += jdbc.update("""
                DELETE FROM chat_robot_dispatch
                 WHERE created_at < ?
                    OR game_event_id IN (
                        SELECT e.id
                          FROM game_issue_event e
                         WHERE e.created_at < ?
                            OR e.issue_number IN (
                                SELECT i.issue_number FROM game_issue i
                                 WHERE COALESCE(i.opened_at, i.created_at) < ?
                            )
                    )
                    OR message_id IN (SELECT id FROM chat_message WHERE created_at < ?)
                """, timestamp, timestamp, timestamp, timestamp);
        deleted += jdbc.update("""
                DELETE FROM chat_outbox
                 WHERE created_at < ?
                    OR message_id IN (SELECT id FROM chat_message WHERE created_at < ?)
                """, timestamp, timestamp);
        deleted += jdbc.update("""
                DELETE FROM game_bet_edit_record
                 WHERE created_at < ?
                    OR bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                    OR ledger_id IN (
                        SELECT id FROM demo_balance_ledger
                         WHERE created_at < ?
                            OR related_bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                    )
                """, timestamp, timestamp, timestamp, timestamp);
        deleted += jdbc.update("""
                DELETE FROM demo_balance_ledger
                 WHERE created_at < ?
                    OR related_bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                """, timestamp, timestamp);
        deleted += jdbc.update("DELETE FROM game_bet WHERE created_at < ?", timestamp);
        deleted += jdbc.update("DELETE FROM player_point_request WHERE requested_at < ?", timestamp);
        deleted += jdbc.update("DELETE FROM player_admin_notice WHERE created_at < ?", timestamp);
        deleted += jdbc.update("DELETE FROM sys_login_log WHERE created_at < ?", timestamp);
        deleted += jdbc.update("DELETE FROM chat_message WHERE created_at < ?", timestamp);
        deleted += jdbc.update("""
                DELETE FROM game_issue_event
                 WHERE created_at < ?
                    OR issue_number IN (
                        SELECT issue_number FROM game_issue
                         WHERE COALESCE(opened_at, created_at) < ?
                    )
                """, timestamp, timestamp);
        deleted += jdbc.update("DELETE FROM game_issue WHERE COALESCE(opened_at, created_at) < ?", timestamp);

        auditClearData(operator, cutoff, counts, deleted);
        return new ClearDataResult(false, cutoff, counts, deleted,
                "已清理 " + cutoff + " 之前的数据，共 " + deleted + " 条");
    }

    private DeleteAllAccountsCounts deleteAllAccountsCounts() {
        return new DeleteAllAccountsCounts(
                count("SELECT COUNT(*) FROM agent_group WHERE deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM agent WHERE system_owned=FALSE AND deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM demo_user_account WHERE status <> 'DELETED'"),
                count("""
                        SELECT COUNT(*) FROM agent_group
                         WHERE deleted_at IS NULL AND report_username IS NOT NULL
                        """));
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private ClearDataCounts clearDataCounts(Instant cutoff) {
        Timestamp timestamp = Timestamp.from(cutoff);
        return new ClearDataCounts(
                count("SELECT COUNT(*) FROM game_bet WHERE created_at < ?", timestamp),
                count("""
                        SELECT COUNT(*) FROM test_player_action
                         WHERE created_at < ?
                            OR bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                        """, timestamp, timestamp),
                count("SELECT COUNT(*) FROM player_point_request WHERE requested_at < ?", timestamp),
                count("""
                        SELECT COUNT(*) FROM demo_balance_ledger
                         WHERE created_at < ?
                            OR related_bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                        """, timestamp, timestamp),
                count("SELECT COUNT(*) FROM game_issue WHERE COALESCE(opened_at, created_at) < ?", timestamp),
                count("""
                        SELECT COUNT(*) FROM game_issue_event
                         WHERE created_at < ?
                            OR issue_number IN (
                                SELECT issue_number FROM game_issue
                                 WHERE COALESCE(opened_at, created_at) < ?
                            )
                        """, timestamp, timestamp),
                count("""
                        SELECT COUNT(*) FROM game_bet_edit_record
                         WHERE created_at < ?
                            OR bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                            OR ledger_id IN (
                                SELECT id FROM demo_balance_ledger
                                 WHERE created_at < ?
                                    OR related_bet_id IN (SELECT id FROM game_bet WHERE created_at < ?)
                            )
                        """, timestamp, timestamp, timestamp, timestamp),
                count("SELECT COUNT(*) FROM player_admin_notice WHERE created_at < ?", timestamp),
                count("SELECT COUNT(*) FROM sys_login_log WHERE created_at < ?", timestamp),
                count("SELECT COUNT(*) FROM chat_message WHERE created_at < ?", timestamp),
                count("""
                        SELECT COUNT(*) FROM chat_outbox
                         WHERE created_at < ?
                            OR message_id IN (SELECT id FROM chat_message WHERE created_at < ?)
                        """, timestamp, timestamp),
                count("""
                        SELECT COUNT(*) FROM chat_robot_dispatch
                         WHERE created_at < ?
                            OR game_event_id IN (
                                SELECT e.id
                                  FROM game_issue_event e
                                 WHERE e.created_at < ?
                                    OR e.issue_number IN (
                                        SELECT i.issue_number FROM game_issue i
                                         WHERE COALESCE(i.opened_at, i.created_at) < ?
                                    )
                            )
                            OR message_id IN (SELECT id FROM chat_message WHERE created_at < ?)
                        """, timestamp, timestamp, timestamp, timestamp));
    }

    private static void requireDeleteAllAccountsConfirm(String confirm) {
        if (!DELETE_ALL_ACCOUNTS_CONFIRM.equals(confirm)) {
            throw BusinessException.badRequest("DELETE_ALL_ACCOUNTS_CONFIRM_REQUIRED",
                    "缺少全量账号删除确认参数");
        }
    }

    private static void requireClearDataConfirm(String confirm) {
        if (!CLEAR_DATA_CONFIRM.equals(confirm)) {
            throw BusinessException.badRequest("PLATFORM_CLEAR_DATA_CONFIRM_REQUIRED",
                    "缺少清空数据确认参数");
        }
    }

    private static Instant parseCutoff(String value) {
        String trimmed = required(value, "请选择有效的清理时间", 64);
        try {
            return Instant.parse(trimmed);
        } catch (RuntimeException ignored) {
            // Continue with local-date formats accepted by BY220 laydate and HTML datetime-local.
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(BUSINESS_ZONE).toInstant();
        } catch (RuntimeException ignored) {
            // Continue with a date-only value.
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(BUSINESS_ZONE).toInstant();
        } catch (RuntimeException ignored) {
            // Continue with a date-only value.
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(BUSINESS_ZONE).toInstant();
        } catch (RuntimeException exception) {
            throw BusinessException.badRequest("PLATFORM_CLEAR_DATA_TIME_INVALID", "清理时间格式不正确");
        }
    }

    private void auditDeleteAllAccounts(long operator, DeleteAllAccountsCounts counts) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'POST', '/api/admin/settings/delete-all-accounts', '1',
                        'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, WRITE_PERMISSION, "admins=" + counts.admins() + ",robots=" + counts.robots()
                + ",players=" + counts.players() + ",flyers=" + counts.flyers());
    }

    private void auditClearData(long operator, Instant cutoff, ClearDataCounts counts, long deleted) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'POST', '/api/admin/settings/clear-data', '1',
                        'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, WRITE_PERMISSION, "time=" + cutoff + ",previewTotal=" + counts.total()
                + ",deleted=" + deleted);
    }
    private SettingsView requireSettings() {
        List<SettingsView> rows = jdbc.query("""
                SELECT id, site_title, announcement, domain_links, chat_warning, information,
                       header_enabled, status_bar_enabled, keyboard_mode, version, updated_by, updated_at
                  FROM platform_setting
                 WHERE id = 1
                """, (rs, rowNum) -> new SettingsView(
                rs.getLong("id"), rs.getString("site_title"), rs.getString("announcement"),
                rs.getString("domain_links"), rs.getString("chat_warning"), rs.getString("information"),
                rs.getBoolean("header_enabled"), rs.getBoolean("status_bar_enabled"),
                rs.getBoolean("keyboard_mode"), rs.getLong("version"),
                rs.getObject("updated_by", Long.class), instant(rs.getTimestamp("updated_at"))));
        if (rows.isEmpty()) {
            throw new IllegalStateException("平台设置不存在");
        }
        return rows.get(0);
    }

    private void audit(long operator, String title) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'PUT', '/api/admin/settings', '1', 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, WRITE_PERMISSION, "siteTitle=" + title);
    }

    private void requirePermission(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("PLATFORM_SETTINGS_FORBIDDEN", "没有平台设置权限");
        }
    }

    private static String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw BusinessException.badRequest("PLATFORM_SETTINGS_INVALID", message);
        }
        return value.trim();
    }

    private static String optional(String value, int maxLength, String message) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw BusinessException.badRequest("PLATFORM_SETTINGS_INVALID", message);
        }
        return trimmed;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record SettingsInput(String siteTitle, String announcement, String domainLinks,
                                String chatWarning, String information, boolean headerEnabled,
                                boolean statusBarEnabled, boolean keyboardMode) {
    }

    public record SettingsView(long id, String siteTitle, String announcement, String domainLinks,
                               String chatWarning, String information, boolean headerEnabled,
                               boolean statusBarEnabled, boolean keyboardMode, long version,
                               Long updatedBy, Instant updatedAt) {
    }

    public record DeleteAllAccountsResult(boolean preview, DeleteAllAccountsCounts counts, String message) {
    }

    public record DeleteAllAccountsCounts(long admins, long robots, long players, long flyers) {
    }

    public record ClearDataResult(boolean preview, Instant time, ClearDataCounts counts,
                                  long data, String message) {
    }

    public record ClearDataCounts(long orders, long botActions, long pointRequests, long balanceLedger,
                                  long drawIssues, long drawEvents, long orderEdits, long adminNotices,
                                  long loginLogs, long chatMessages, long chatOutbox, long robotDispatches) {
        public long total() {
            return orders + botActions + pointRequests + balanceLedger + drawIssues + drawEvents
                    + orderEdits + adminNotices + loginLogs + chatMessages + chatOutbox + robotDispatches;
        }
    }
    public record PublicSettingsView(String siteTitle, String announcement, String chatWarning,
                                     boolean headerEnabled, boolean statusBarEnabled,
                                     boolean keyboardMode, long version) {
    }
}
