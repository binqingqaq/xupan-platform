package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class PlatformOnlinePlayerService {

    private static final String READ_PERMISSION = "ONLINE_PLAYER_READ";
    private static final String DISCONNECT_PERMISSION = "ONLINE_PLAYER_DISCONNECT";
    private static final String MESSAGE_PERMISSION = "ONLINE_PLAYER_MESSAGE";
    private static final int ONLINE_WINDOW_SECONDS = 30;
    private static final int UNREAD_NOTICE_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public PlatformOnlinePlayerService(JdbcTemplate jdbc, PermissionService permissionService,
                                       SessionRepository sessionRepository,
                                       UserRepository userRepository) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<OnlineRow> list(long operator) {
        requirePermission(operator, READ_PERMISSION);
        Instant since = Instant.now().minusSeconds(ONLINE_WINDOW_SECONDS);
        return jdbc.query("""
                SELECT x.user_id, x.username, x.display_name, x.account_id, x.balance,
                       x.player_kind, x.member_code, x.machine_id, x.machine_name,
                       x.machine_score, x.sub_account, x.last_seen_at
                  FROM (
                        SELECT s.user_id, s.last_seen_at, u.username, u.display_name,
                               a.id AS account_id, a.balance, a.player_kind, a.member_code,
                               m.id AS machine_id, m.display_name AS machine_name,
                               m.score AS machine_score, g.username AS sub_account,
                               ROW_NUMBER() OVER (
                                   PARTITION BY s.user_id
                                   ORDER BY s.last_seen_at DESC, s.id DESC
                               ) AS row_no
                          FROM auth_session s
                          JOIN sys_user u ON u.id = s.user_id
                          LEFT JOIN demo_user_account a ON a.sys_user_id = u.id
                               AND a.status <> 'DELETED'
                          LEFT JOIN agent m ON m.account_user_id = u.id
                          LEFT JOIN agent_group g ON g.id = m.group_id
                         WHERE s.revoked_at IS NULL
                           AND s.access_expires_at > CURRENT_TIMESTAMP
                           AND s.last_seen_at >= ?
                           AND u.status <> 'DELETED'
                  ) x
                 WHERE x.row_no = 1
                 ORDER BY x.last_seen_at DESC, x.user_id
                """, (rs, rowNum) -> {
                    String playerKind = rs.getString("player_kind");
                    Long accountId = rs.getObject("account_id", Long.class);
                    Long machineId = rs.getObject("machine_id", Long.class);
                    String userType = accountId != null
                            ? ("BOT".equals(playerKind) ? "托" : "玩家")
                            : (machineId != null ? "机器人" : "子账号");
                    BigDecimal score = accountId != null
                            ? money(rs.getBigDecimal("balance"))
                            : machineId != null ? money(rs.getBigDecimal("machine_score")) : BigDecimal.ZERO.setScale(2);
                    return new OnlineRow(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            userType,
                            score,
                            rs.getString("sub_account"),
                            rs.getString("machine_name"),
                            true,
                            null,
                            null,
                            instant(rs.getTimestamp("last_seen_at")));
                }, Timestamp.from(since));
    }

    @Transactional
    public int disconnect(long targetUserId, long operator) {
        requirePermission(operator, DISCONNECT_PERMISSION);
        requireUser(targetUserId);
        Instant now = Instant.now();
        int count = sessionRepository.revokeAllActiveByUserId(targetUserId, now);
        audit(operator, DISCONNECT_PERMISSION, "POST",
                "/api/admin/online-players/" + targetUserId + "/disconnect", targetUserId,
                "disconnectedSessions=" + count);
        return count;
    }

    @Transactional
    public AdminNoticeView sendMessage(long targetUserId, String title, String content,
                                       String idempotencyKey, long operator) {
        requirePermission(operator, MESSAGE_PERMISSION);
        requireUser(targetUserId);
        String safeTitle = required(title, "消息标题不能为空", 128);
        String safeContent = required(content, "消息内容不能为空", 2000);
        String key = required(idempotencyKey, "消息幂等键不能为空", 128);
        AdminNoticeView replay = findNoticeByKey(operator, key);
        if (replay != null) {
            if (replay.recipientUserId() != targetUserId
                    || !replay.title().equals(safeTitle)
                    || !replay.content().equals(safeContent)) {
                throw BusinessException.conflict("ONLINE_PLAYER_MESSAGE_IDEMPOTENCY_CONFLICT", "消息请求标识已被其他请求使用");
            }
            return replay;
        }
        try {
            jdbc.update("""
                    INSERT INTO player_admin_notice
                        (recipient_user_id, sender_user_id, title, content, idempotency_key)
                    VALUES (?, ?, ?, ?, ?)
                    """, targetUserId, operator, safeTitle, safeContent, key);
        } catch (DuplicateKeyException duplicate) {
            AdminNoticeView concurrent = findNoticeByKey(operator, key);
            if (concurrent == null) throw duplicate;
            return concurrent;
        }
        AdminNoticeView notice = findNoticeByKey(operator, key);
        audit(operator, MESSAGE_PERMISSION, "POST",
                "/api/admin/online-players/" + targetUserId + "/messages", targetUserId,
                "noticeId=" + notice.id());
        return notice;
    }

    @Transactional
    public List<AdminNoticeView> listUnreadNotices(long recipientUserId) {
        List<AdminNoticeView> rows = jdbc.query("""
                SELECT n.id, n.recipient_user_id, n.sender_user_id, u.display_name AS sender_name,
                       n.title, n.content, n.created_at
                  FROM player_admin_notice n
                  JOIN sys_user u ON u.id = n.sender_user_id
                 WHERE n.recipient_user_id = ? AND n.read_at IS NULL
                 ORDER BY n.id
                 LIMIT ?
                """, (rs, rowNum) -> new AdminNoticeView(
                rs.getLong("id"), rs.getLong("recipient_user_id"), rs.getLong("sender_user_id"),
                rs.getString("sender_name"), rs.getString("title"), rs.getString("content"),
                instant(rs.getTimestamp("created_at"))), recipientUserId, UNREAD_NOTICE_LIMIT);
        if (!rows.isEmpty()) {
            Instant now = Instant.now();
            for (AdminNoticeView row : rows) {
                jdbc.update("UPDATE player_admin_notice SET read_at = ? WHERE id = ? AND read_at IS NULL",
                        Timestamp.from(now), row.id());
            }
        }
        return rows;
    }

    private AdminNoticeView findNoticeByKey(long senderUserId, String key) {
        List<AdminNoticeView> rows = jdbc.query("""
                SELECT n.id, n.recipient_user_id, n.sender_user_id, u.display_name AS sender_name,
                       n.title, n.content, n.created_at
                  FROM player_admin_notice n
                  JOIN sys_user u ON u.id = n.sender_user_id
                 WHERE n.sender_user_id = ? AND n.idempotency_key = ?
                """, (rs, rowNum) -> new AdminNoticeView(
                rs.getLong("id"), rs.getLong("recipient_user_id"), rs.getLong("sender_user_id"),
                rs.getString("sender_name"), rs.getString("title"), rs.getString("content"),
                instant(rs.getTimestamp("created_at"))), senderUserId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireUser(long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("ONLINE_PLAYER_NOT_FOUND", "用户不存在"));
    }

    private void requirePermission(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("ONLINE_PLAYER_FORBIDDEN", "没有在线玩家操作权限");
        }
    }

    private void audit(long operator, String permission, String method, String path,
                       long resourceId, String summary) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, permission, method, path, Long.toString(resourceId), summary);
    }

    private static String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw BusinessException.badRequest("ONLINE_PLAYER_REQUEST_INVALID", message);
        }
        return value.trim();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record OnlineRow(long userId, String username, String displayName, String userType,
                            BigDecimal score, String subAccount, String robot, boolean online,
                            String ip, String city, Instant loginTime) {
    }

    public record AdminNoticeView(long id, long recipientUserId, long senderUserId,
                                  String senderName, String title, String content,
                                  Instant createdAt) {
    }
}
