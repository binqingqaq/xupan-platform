package com.xupan.server.robot.repository;

import com.xupan.server.robot.domain.ChatRobotDispatch;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.domain.RobotEventType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class RobotDispatchRepository {

    public static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String COLUMNS = """
            SELECT id, game_event_id, robot_id, issue_number, event_type, status, attempt_count,
                   next_attempt_at, locked_until, message_id, last_error, created_at, updated_at,
                   published_at
              FROM chat_robot_dispatch
            """;
    private static final String SUPPORTED_EVENT_TYPES = """
            ('ISSUE_STARTED', 'BETTING_WARNING', 'BETTING_CLOSED', 'DRAW_RESULT')
            """;

    private final JdbcTemplate jdbcTemplate;

    public RobotDispatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UnscheduledGameEvent> findUnscheduledEvents() {
        return findUnscheduledEvents(DEFAULT_BATCH_SIZE);
    }

    public List<UnscheduledGameEvent> findUnscheduledEvents(int limit) {
        validateLimit(limit);
        return jdbcTemplate.query("""
                SELECT e.id, e.issue_number, e.event_type, e.message, e.created_at
                  FROM game_issue_event e
                  LEFT JOIN chat_robot_dispatch d ON d.game_event_id = e.id
                 WHERE d.id IS NULL
                   AND e.event_type IN """ + SUPPORTED_EVENT_TYPES + """
                 ORDER BY e.id
                 LIMIT ?
                """, (rs, rowNum) -> new UnscheduledGameEvent(rs.getLong("id"),
                rs.getString("issue_number"), rs.getString("event_type"), rs.getString("message"),
                instant(rs.getTimestamp("created_at"))), limit);
    }

    public Optional<ChatRobotDispatch> findById(long dispatchId) {
        return jdbcTemplate.query(COLUMNS + " WHERE id = ?", this::mapDispatch, dispatchId)
                .stream().findFirst();
    }

    public Optional<ChatRobotDispatch> findByGameEventId(long gameEventId) {
        return jdbcTemplate.query(COLUMNS + " WHERE game_event_id = ?", this::mapDispatch,
                gameEventId).stream().findFirst();
    }

    public Optional<UnscheduledGameEvent> findGameEventById(long gameEventId) {
        return jdbcTemplate.query("""
                SELECT id, issue_number, event_type, message, created_at
                  FROM game_issue_event
                 WHERE id = ?
                """, (rs, rowNum) -> new UnscheduledGameEvent(rs.getLong("id"),
                rs.getString("issue_number"), rs.getString("event_type"),
                rs.getString("message"), instant(rs.getTimestamp("created_at"))), gameEventId)
                .stream().findFirst();
    }

    public List<ChatRobotDispatch> findDue(Instant now) {
        return findDue(DEFAULT_BATCH_SIZE, now);
    }

    public List<ChatRobotDispatch> findDue(int limit, Instant now) {
        validateLimit(limit);
        Instant checkedNow = requiredInstant(now, "now");
        Timestamp timestamp = Timestamp.from(checkedNow);
        return jdbcTemplate.query(COLUMNS + """
                 WHERE (
                         status IN ('PENDING', 'FAILED')
                         OR (status = 'PROCESSING' AND locked_until <= ?)
                       )
                   AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                 ORDER BY id
                 LIMIT ?
                """, this::mapDispatch, timestamp, timestamp, limit);
    }

    public Optional<ChatRobotDispatch> findByIdForUpdate(long dispatchId) {
        requireTransaction("findByIdForUpdate");
        return jdbcTemplate.query(COLUMNS + " WHERE id = ? FOR UPDATE", this::mapDispatch,
                dispatchId).stream().findFirst();
    }

    public ChatRobotDispatch insertPendingIfAbsent(long gameEventId, long robotId,
                                                    String issueNumber, RobotEventType eventType,
                                                    Instant createdAt) {
        requireTransaction("insertPendingIfAbsent");
        if (gameEventId <= 0 || robotId <= 0) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_ID_INVALID: 来源 ID 必须为正数");
        }
        String issue = requiredText(issueNumber, "issueNumber", 64);
        RobotEventType checkedEventType = requiredEventType(eventType);
        Instant timestamp = requiredInstant(createdAt, "createdAt");
        try {
            jdbcTemplate.update("""
                    INSERT INTO chat_robot_dispatch
                        (game_event_id, robot_id, issue_number, event_type, status,
                         attempt_count, next_attempt_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                    """, gameEventId, robotId, issue, checkedEventType.name(),
                    Timestamp.from(timestamp), Timestamp.from(timestamp), Timestamp.from(timestamp));
        } catch (DuplicateKeyException duplicate) {
            return findByGameEventId(gameEventId).orElseThrow(() ->
                    new IllegalStateException("机器人投递幂等冲突后未找到已有任务", duplicate));
        }
        return findByGameEventId(gameEventId).orElseThrow(() ->
                new IllegalStateException("创建机器人投递任务后未找到任务"));
    }

    public int markProcessing(long dispatchId, Instant lockedUntil, Instant updatedAt) {
        requireTransaction("markProcessing");
        Instant lock = requiredInstant(lockedUntil, "lockedUntil");
        Instant updated = requiredInstant(updatedAt, "updatedAt");
        return jdbcTemplate.update("""
                UPDATE chat_robot_dispatch
                   SET status = 'PROCESSING', locked_until = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'FAILED')
                """, Timestamp.from(lock), Timestamp.from(updated), dispatchId);
    }

    public int resetExpiredProcessing(long dispatchId, Instant now) {
        requireTransaction("resetExpiredProcessing");
        Instant checkedNow = requiredInstant(now, "now");
        return jdbcTemplate.update("""
                UPDATE chat_robot_dispatch
                   SET status = 'PENDING', locked_until = NULL, next_attempt_at = ?,
                       updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND locked_until <= ?
                """, Timestamp.from(checkedNow), Timestamp.from(checkedNow), dispatchId,
                Timestamp.from(checkedNow));
    }

    public int markPublished(long dispatchId, long messageId, Instant publishedAt) {
        requireTransaction("markPublished");
        if (dispatchId <= 0 || messageId <= 0) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_ID_INVALID: 投递和消息 ID 必须为正数");
        }
        Instant published = requiredInstant(publishedAt, "publishedAt");
        return jdbcTemplate.update("""
                UPDATE chat_robot_dispatch
                   SET status = 'PUBLISHED', message_id = ?, locked_until = NULL,
                       published_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING'
                """, messageId, Timestamp.from(published), Timestamp.from(published), dispatchId);
    }

    public int markSkipped(long dispatchId, String reason, Instant updatedAt) {
        requireTransaction("markSkipped");
        String error = errorSummary(reason);
        Instant updated = requiredInstant(updatedAt, "updatedAt");
        return jdbcTemplate.update("""
                UPDATE chat_robot_dispatch
                   SET status = 'SKIPPED', locked_until = NULL, last_error = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'PROCESSING', 'FAILED')
                """, error, Timestamp.from(updated), dispatchId);
    }

    public int markFailed(long dispatchId, int attemptCount, Instant nextAttemptAt,
                          String error, Instant updatedAt) {
        requireTransaction("markFailed");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_ATTEMPT_INVALID: attemptCount 必须为正数");
        }
        Instant next = requiredInstant(nextAttemptAt, "nextAttemptAt");
        Instant updated = requiredInstant(updatedAt, "updatedAt");
        return jdbcTemplate.update("""
                UPDATE chat_robot_dispatch
                   SET status = 'FAILED', attempt_count = ?, next_attempt_at = ?,
                       locked_until = NULL, last_error = ?, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING'
                """, attemptCount, Timestamp.from(next), errorSummary(error),
                Timestamp.from(updated), dispatchId);
    }

    public List<ChatRobotDispatch> findPage(String issueNumber, RobotEventType eventType,
                                            RobotDispatchStatus status, Instant from, Instant to,
                                            int page, int pageSize) {
        validatePage(page, pageSize);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(COLUMNS).append(" WHERE 1 = 1");
        appendFilters(sql, args, issueNumber, eventType, status, from, to);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((long) (page - 1) * pageSize);
        return jdbcTemplate.query(sql.toString(), this::mapDispatch, args.toArray());
    }

    public long count(String issueNumber, RobotEventType eventType, RobotDispatchStatus status,
                      Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM chat_robot_dispatch WHERE 1 = 1");
        appendFilters(sql, args, issueNumber, eventType, status, from, to);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public int resetFailedForRetry(long dispatchId, Instant nextAttemptAt) {
        requireTransaction("resetFailedForRetry");
        Instant next = requiredInstant(nextAttemptAt, "nextAttemptAt");
        return jdbcTemplate.update("""
                UPDATE chat_robot_dispatch
                   SET status = 'PENDING', next_attempt_at = ?, locked_until = NULL,
                       last_error = NULL, updated_at = ?
                 WHERE id = ? AND status = 'FAILED'
                """, Timestamp.from(next), Timestamp.from(next), dispatchId);
    }

    public long countByRobotIdAndStatus(long robotId, RobotDispatchStatus status) {
        if (robotId <= 0 || status == null) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_FILTER_INVALID: 机器人或状态无效");
        }
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM chat_robot_dispatch
                 WHERE robot_id = ? AND status = ?
                """, Long.class, robotId, status.name());
        return count == null ? 0L : count;
    }

    public long countByStatus(RobotDispatchStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_FILTER_INVALID: 状态无效");
        }
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM chat_robot_dispatch
                 WHERE status = ?
                """, Long.class, status.name());
        return count == null ? 0L : count;
    }

    private ChatRobotDispatch mapDispatch(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ChatRobotDispatch(rs.getLong("id"), rs.getLong("game_event_id"),
                rs.getLong("robot_id"), rs.getString("issue_number"),
                eventTypeValue(rs.getString("event_type")),
                RobotDispatchStatus.fromDatabaseValue(rs.getString("status")),
                rs.getInt("attempt_count"), instant(rs.getTimestamp("next_attempt_at")),
                instant(rs.getTimestamp("locked_until")), nullableLong(rs, "message_id"),
                rs.getString("last_error"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("published_at")));
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_TEXT_INVALID: " + field
                    + " 不能为空且长度不能超过 " + maxLength);
        }
        return value;
    }

    private static void appendFilters(StringBuilder sql, List<Object> args,
                                      String issueNumber, RobotEventType eventType,
                                      RobotDispatchStatus status, Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_FILTER_INVALID: 时间范围无效");
        }
        if (issueNumber != null && !issueNumber.isBlank()) {
            if (issueNumber.length() > 64) {
                throw new IllegalArgumentException("ROBOT_DISPATCH_TEXT_INVALID: issueNumber 过长");
            }
            sql.append(" AND issue_number = ?");
            args.add(issueNumber.trim());
        }
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            args.add(requiredEventType(eventType).name());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            args.add(Timestamp.from(to));
        }
    }

    private static String errorSummary(String value) {
        String normalized = value == null || value.isBlank() ? "未提供错误摘要" : value.trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private static RobotEventType requiredEventType(RobotEventType eventType) {
        if (eventType == null) {
            throw new IllegalArgumentException("ROBOT_EVENT_TYPE_INVALID: eventType 不能为空");
        }
        return eventType;
    }

    private static String eventTypeValue(String value) {
        return RobotEventType.fromDatabaseValue(value).name();
    }

    private static Instant requiredInstant(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("ROBOT_TIME_INVALID: " + field + " 不能为空");
        }
        return value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_LIMIT_INVALID: limit 必须在 1 到 100 之间");
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("ROBOT_DISPATCH_PAGE_INVALID: 分页参数无效");
        }
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }

    public record UnscheduledGameEvent(long id, String issueNumber, String eventType,
                                      String message, Instant createdAt) {
    }
}
