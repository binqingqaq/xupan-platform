package com.xupan.server.robot.repository;

import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class RobotRepository {

    private static final String COLUMNS = """
            SELECT id, robot_code, display_name, avatar_key, status, weight, delay_seconds,
                   created_at, updated_at
              FROM chat_robot
            """;

    private final JdbcTemplate jdbcTemplate;

    public RobotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ChatRobot> findAll() {
        return jdbcTemplate.query(COLUMNS + " ORDER BY id", this::mapRobot);
    }

    public Optional<ChatRobot> findById(long robotId) {
        return jdbcTemplate.query(COLUMNS + " WHERE id = ?", this::mapRobot, robotId)
                .stream().findFirst();
    }

    public Optional<ChatRobot> findByCode(String robotCode) {
        return jdbcTemplate.query(COLUMNS + " WHERE robot_code = ?", this::mapRobot, robotCode)
                .stream().findFirst();
    }

    public List<ChatRobot> findEnabledOrderByCode() {
        return jdbcTemplate.query(COLUMNS + " WHERE status = 'ENABLED' ORDER BY robot_code",
                this::mapRobot);
    }

    @Transactional
    public long insert(String robotCode, String displayName, String avatarKey,
                       RobotStatus status, int weight, int delaySeconds) {
        String code = validateCode(robotCode);
        String name = requiredText(displayName, "displayName", 32);
        String avatar = requiredText(avatarKey, "avatarKey", 64);
        RobotStatus checkedStatus = requiredStatus(status);
        validateWeight(weight);
        validateDelay(delaySeconds);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_robot
                        (robot_code, display_name, avatar_key, status, weight, delay_seconds,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, avatar);
            statement.setString(4, checkedStatus.name());
            statement.setInt(5, weight);
            statement.setInt(6, delaySeconds);
            return statement;
        }, keyHolder);
        Number key = generatedRobotId(keyHolder);
        if (key == null) {
            throw new IllegalStateException("创建机器人后未取得机器人 ID");
        }
        return key.longValue();
    }

    private static Number generatedRobotId(KeyHolder keyHolder) {
        if (keyHolder.getKeys() != null) {
            for (var entry : keyHolder.getKeys().entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                    return number;
                }
            }
        }
        return keyHolder.getKey();
    }

    @Transactional
    public int update(long robotId, String displayName, String avatarKey,
                      RobotStatus status, int weight, int delaySeconds) {
        if (robotId <= 0) {
            throw new IllegalArgumentException("ROBOT_ID_INVALID: robotId 必须为正数");
        }
        String name = requiredText(displayName, "displayName", 32);
        String avatar = requiredText(avatarKey, "avatarKey", 64);
        RobotStatus checkedStatus = requiredStatus(status);
        validateWeight(weight);
        validateDelay(delaySeconds);
        return jdbcTemplate.update("""
                UPDATE chat_robot
                   SET display_name = ?, avatar_key = ?, status = ?, weight = ?,
                       delay_seconds = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, name, avatar, checkedStatus.name(), weight, delaySeconds, robotId);
    }

    /** Updates editable fields while preserving the current enabled/disabled state. */
    @Transactional
    public int update(long robotId, String displayName, String avatarKey,
                      int weight, int delaySeconds) {
        if (robotId <= 0) {
            throw new IllegalArgumentException("ROBOT_ID_INVALID: robotId 必须为正数");
        }
        String name = requiredText(displayName, "displayName", 32);
        String avatar = requiredText(avatarKey, "avatarKey", 64);
        validateWeight(weight);
        validateDelay(delaySeconds);
        return jdbcTemplate.update("""
                UPDATE chat_robot
                   SET display_name = ?, avatar_key = ?, weight = ?,
                       delay_seconds = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, name, avatar, weight, delaySeconds, robotId);
    }

    @Transactional
    public int updateStatus(long robotId, RobotStatus status) {
        if (robotId <= 0) {
            throw new IllegalArgumentException("ROBOT_ID_INVALID: robotId 必须为正数");
        }
        return jdbcTemplate.update("""
                UPDATE chat_robot
                   SET status = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, requiredStatus(status).name(), robotId);
    }

    @Transactional
    public int updateAvatarKey(long robotId, String avatarKey) {
        if (robotId <= 0 || avatarKey == null || avatarKey.isBlank() || avatarKey.length() > 255) {
            throw new IllegalArgumentException("机器人头像参数无效");
        }
        return jdbcTemplate.update("""
                UPDATE chat_robot
                   SET avatar_key = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, avatarKey, robotId);
    }

    private ChatRobot mapRobot(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChatRobot(rs.getLong("id"), rs.getString("robot_code"),
                rs.getString("display_name"), rs.getString("avatar_key"),
                RobotStatus.fromDatabaseValue(rs.getString("status")), rs.getInt("weight"),
                rs.getInt("delay_seconds"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private static String validateCode(String value) {
        String code = requiredText(value, "robotCode", 32);
        if (!code.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("ROBOT_CODE_INVALID: robotCode 格式无效");
        }
        return code;
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("ROBOT_" + field.toUpperCase() + "_INVALID: "
                    + field + " 不能为空且长度不能超过 " + maxLength);
        }
        return value;
    }

    private static RobotStatus requiredStatus(RobotStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("ROBOT_STATUS_INVALID: status 不能为空");
        }
        return status;
    }

    private static void validateWeight(int weight) {
        if (weight < 1 || weight > 100) {
            throw new IllegalArgumentException("ROBOT_WEIGHT_INVALID: weight 必须在 1 到 100 之间");
        }
    }

    private static void validateDelay(int delaySeconds) {
        if (delaySeconds < 0 || delaySeconds > 300) {
            throw new IllegalArgumentException(
                    "ROBOT_DELAY_INVALID: delaySeconds 必须在 0 到 300 之间");
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
