package com.xupan.server.robot.repository;

import com.xupan.server.chat.domain.ChatMessageVisibility;
import com.xupan.server.robot.domain.RobotNoticeIntent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class RobotNoticeIntentRepository {

    private static final String COLUMNS = """
            SELECT id, event_code, source_type, source_id, game_code, issue_number,
                   visibility, agent_id, content, payload_json, idempotency_key,
                   status, attempt_count, next_attempt_at, locked_until, message_id,
                   last_error, created_at, updated_at
              FROM robot_notice_intent
            """;

    private final JdbcTemplate jdbc;

    public RobotNoticeIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean enqueue(String eventCode, String sourceType, String sourceId,
                           String gameCode, String issueNumber, ChatMessageVisibility visibility,
                           Long agentId, String content, String payloadJson, String idempotencyKey,
                           Instant now) {
        try {
            return jdbc.update("""
                    INSERT INTO robot_notice_intent
                        (event_code, source_type, source_id, game_code, issue_number, visibility,
                         agent_id, content, payload_json, idempotency_key, status, attempt_count,
                         next_attempt_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                    """, eventCode, sourceType, sourceId, gameCode, issueNumber, visibility.name(),
                    agentId, content, payloadJson, idempotencyKey,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return findByKey(idempotencyKey).map(existing -> {
                if (!existing.eventCode().equals(eventCode)
                        || !existing.sourceType().equals(sourceType)
                        || !existing.sourceId().equals(sourceId)
                        || !existing.gameCode().equals(gameCode)
                        || !existing.issueNumber().equals(issueNumber)
                        || existing.visibility() != visibility
                        || !java.util.Objects.equals(existing.agentId(), agentId)
                        || !existing.content().equals(content)
                        || !existing.payloadJson().equals(payloadJson)) {
                    throw new IllegalStateException("机器人通知幂等键对应的业务快照不一致", duplicate);
                }
                return false;
            }).orElseThrow(() -> new IllegalStateException("机器人通知幂等冲突后未找到原任务", duplicate));
        }
    }

    public List<Long> findDueIds(Instant now, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM robot_notice_intent
                 WHERE ((status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?)
                    OR (status = 'PROCESSING' AND locked_until <= ?))
                 ORDER BY next_attempt_at, id LIMIT ?
                """, Long.class, Timestamp.from(now), Timestamp.from(now), limit);
    }

    public Optional<RobotNoticeIntent> findForUpdate(long id) {
        return jdbc.query(COLUMNS + " WHERE id = ? FOR UPDATE", this::map, id)
                .stream().findFirst();
    }

    public boolean machineExists(Long agentId) {
        return agentId != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM agent WHERE id = ?)", Boolean.class, agentId));
    }

    public boolean existsByKey(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM robot_notice_intent WHERE idempotency_key = ?)",
                Boolean.class, key));
    }

    public boolean markProcessing(long id, Instant lockedUntil, Instant now) {
        return jdbc.update("""
                UPDATE robot_notice_intent
                   SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                       locked_until = ?, updated_at = ?
                 WHERE id = ? AND ((status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?)
                    OR (status = 'PROCESSING' AND locked_until <= ?))
                """, Timestamp.from(lockedUntil), Timestamp.from(now), id,
                Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    public int markPublished(long id, long messageId, Instant now) {
        return jdbc.update("""
                UPDATE robot_notice_intent
                   SET status = 'PUBLISHED', message_id = ?, locked_until = NULL,
                       last_error = NULL, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING'
                """, messageId, Timestamp.from(now), id);
    }

    public int markSkipped(long id, String reason, Instant now) {
        return jdbc.update("""
                UPDATE robot_notice_intent
                   SET status = 'SKIPPED', locked_until = NULL, last_error = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'FAILED', 'PROCESSING')
                """, safeError(reason), Timestamp.from(now), id);
    }

    public int markFailed(long id, Instant nextAttemptAt, String error, Instant now) {
        return jdbc.update("""
                UPDATE robot_notice_intent
                   SET status = 'FAILED', attempt_count = attempt_count + 1,
                       next_attempt_at = ?, locked_until = NULL,
                       last_error = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'FAILED', 'PROCESSING')
                """, Timestamp.from(nextAttemptAt), safeError(error), Timestamp.from(now), id);
    }

    private Optional<RobotNoticeIntent> findByKey(String key) {
        return jdbc.query(COLUMNS + " WHERE idempotency_key = ?", this::map, key).stream().findFirst();
    }

    private RobotNoticeIntent map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RobotNoticeIntent(rs.getLong("id"), rs.getString("event_code"),
                rs.getString("source_type"), rs.getString("source_id"), rs.getString("game_code"),
                rs.getString("issue_number"), ChatMessageVisibility.valueOf(rs.getString("visibility")),
                nullableLong(rs, "agent_id"), rs.getString("content"), rs.getString("payload_json"),
                rs.getString("idempotency_key"), rs.getString("status"), rs.getInt("attempt_count"),
                instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("locked_until")),
                nullableLong(rs, "message_id"), rs.getString("last_error"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }
}
