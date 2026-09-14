package com.xupan.server.robot.repository;

import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.robot.domain.RobotEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class RobotTemplateRepository {

    private static final String COLUMNS = """
            SELECT id, robot_id, event_type, template_code, template_text, enabled, version,
                   created_at, updated_at
              FROM chat_robot_template
            """;

    private final JdbcTemplate jdbcTemplate;

    public RobotTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ChatRobotTemplate> findActive(long robotId, RobotEventType eventType) {
        validateRobotId(robotId);
        RobotEventType checkedEventType = requiredEventType(eventType);
        return jdbcTemplate.query(COLUMNS + """
                 WHERE robot_id = ? AND event_type = ? AND enabled = TRUE
                 ORDER BY version DESC, id DESC
                 LIMIT 1
                """, this::mapTemplate, robotId, checkedEventType.name()).stream().findFirst();
    }

    public List<ChatRobotTemplate> findAll(long robotId) {
        validateRobotId(robotId);
        return jdbcTemplate.query(COLUMNS + """
                 WHERE robot_id = ?
                 ORDER BY event_type, version DESC, id DESC
                """, this::mapTemplate, robotId);
    }

    public Optional<ChatRobotTemplate> findLatestVersionForUpdate(long robotId,
                                                                    RobotEventType eventType) {
        requireTransaction("findLatestVersionForUpdate");
        validateRobotId(robotId);
        RobotEventType checkedEventType = requiredEventType(eventType);
        return jdbcTemplate.query(COLUMNS + """
                 WHERE robot_id = ? AND event_type = ?
                 ORDER BY version DESC, id DESC
                 LIMIT 1
                 FOR UPDATE
                """, this::mapTemplate, robotId, checkedEventType.name()).stream().findFirst();
    }

    public long insertVersion(long robotId, RobotEventType eventType, String templateCode,
                              String templateText, boolean enabled, int version) {
        validateRobotId(robotId);
        RobotEventType checkedEventType = requiredEventType(eventType);
        String code = requiredText(templateCode, "templateCode", 32);
        String text = requiredText(templateText, "templateText", 1000);
        if (version < 1) {
            throw new IllegalArgumentException("ROBOT_TEMPLATE_VERSION_INVALID: version 必须为正数");
        }
        jdbcTemplate.update("""
                INSERT INTO chat_robot_template
                    (robot_id, event_type, template_code, template_text, enabled, version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, robotId, checkedEventType.name(), code, text, enabled, version);
        Long id = jdbcTemplate.queryForObject("""
                SELECT id
                  FROM chat_robot_template
                 WHERE robot_id = ? AND event_type = ? AND template_code = ? AND version = ?
                """, Long.class, robotId, checkedEventType.name(), code, version);
        if (id == null) {
            throw new IllegalStateException("创建机器人模板后未取得模板 ID");
        }
        return id;
    }

    public int disableVersions(long robotId, RobotEventType eventType) {
        validateRobotId(robotId);
        RobotEventType checkedEventType = requiredEventType(eventType);
        return jdbcTemplate.update("""
                UPDATE chat_robot_template
                   SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
                 WHERE robot_id = ? AND event_type = ? AND enabled = TRUE
                """, robotId, checkedEventType.name());
    }

    private ChatRobotTemplate mapTemplate(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ChatRobotTemplate(rs.getLong("id"), rs.getLong("robot_id"),
                eventTypeValue(rs.getString("event_type")),
                rs.getString("template_code"), rs.getString("template_text"),
                rs.getBoolean("enabled"), rs.getInt("version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
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

    private static void validateRobotId(long robotId) {
        if (robotId <= 0) {
            throw new IllegalArgumentException("ROBOT_ID_INVALID: robotId 必须为正数");
        }
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("ROBOT_TEMPLATE_TEXT_INVALID: " + field
                    + " 不能为空且长度不能超过 " + maxLength);
        }
        return value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }
}
