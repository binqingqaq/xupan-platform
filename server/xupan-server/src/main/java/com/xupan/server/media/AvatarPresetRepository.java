package com.xupan.server.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AvatarPresetRepository {

    private final JdbcTemplate jdbcTemplate;

    public AvatarPresetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PresetRow> findAll() {
        return jdbcTemplate.query("""
                SELECT p.avatar_key, p.display_order, p.assigned_user_id, p.assigned_at,
                       u.display_name AS assigned_display_name
                  FROM avatar_preset p
                  LEFT JOIN sys_user u ON u.id = p.assigned_user_id
                 ORDER BY p.display_order
                """, this::map);
    }

    public Optional<PresetRow> findByKey(String avatarKey) {
        return jdbcTemplate.query("""
                SELECT p.avatar_key, p.display_order, p.assigned_user_id, p.assigned_at,
                       u.display_name AS assigned_display_name
                  FROM avatar_preset p
                  LEFT JOIN sys_user u ON u.id = p.assigned_user_id
                 WHERE p.avatar_key = ?
                """, this::map, avatarKey).stream().findFirst();
    }

    public List<String> findAvailableKeys() {
        return jdbcTemplate.queryForList("""
                SELECT avatar_key
                  FROM avatar_preset
                 WHERE assigned_user_id IS NULL
                 ORDER BY display_order
                """, String.class);
    }

    public int assignIfAvailable(String avatarKey, long userId) {
        return jdbcTemplate.update("""
                UPDATE avatar_preset
                   SET assigned_user_id = ?, assigned_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE avatar_key = ? AND assigned_user_id IS NULL
                """, userId, avatarKey);
    }

    public int releaseIfAssignedTo(long userId, String avatarKey) {
        return jdbcTemplate.update("""
                UPDATE avatar_preset
                   SET assigned_user_id = NULL, assigned_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE assigned_user_id = ? AND avatar_key = ?
                """, userId, avatarKey);
    }

    public int releaseByUserId(long userId) {
        return jdbcTemplate.update("""
                UPDATE avatar_preset
                   SET assigned_user_id = NULL, assigned_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE assigned_user_id = ?
                """, userId);
    }

    private PresetRow map(ResultSet resultSet, int rowNum) throws SQLException {
        long assignedUserId = resultSet.getLong("assigned_user_id");
        Long ownerId = resultSet.wasNull() ? null : assignedUserId;
        java.sql.Timestamp assignedAt = resultSet.getTimestamp("assigned_at");
        return new PresetRow(
                resultSet.getString("avatar_key"),
                resultSet.getInt("display_order"),
                ownerId,
                assignedAt == null ? null : assignedAt.toInstant(),
                resultSet.getString("assigned_display_name"));
    }

    public record PresetRow(String avatarKey, int displayOrder, Long assignedUserId,
                            Instant assignedAt, String assignedDisplayName) {
    }
}
