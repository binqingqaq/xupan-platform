package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Replaces the retired avatar presets and randomly reassigns current players to the 96 local HBY019 images. */
public class V110__替换为HBY019九十六张玩家头像 extends BaseJavaMigration {

    private static final int AVATAR_COUNT = 96;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<Long> users = usersToReassign(connection);
        if (users.size() > AVATAR_COUNT) {
            throw new IllegalStateException("现有玩家/托及旧头像分配数超过96，无法在保持头像不重复的情况下迁移");
        }

        List<String> avatarKeys = new ArrayList<>(AVATAR_COUNT);
        for (int index = 1; index <= AVATAR_COUNT; index++) {
            avatarKeys.add("preset-%02d.jpg".formatted(index));
        }
        Collections.shuffle(avatarKeys, RANDOM);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM avatar_preset");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO avatar_preset (avatar_key, display_order) VALUES (?, ?)")) {
            for (int index = 1; index <= AVATAR_COUNT; index++) {
                insert.setString(1, "preset-%02d.jpg".formatted(index));
                insert.setInt(2, index);
                insert.addBatch();
            }
            insert.executeBatch();
        }

        try (PreparedStatement updateUser = connection.prepareStatement(
                     "UPDATE sys_user SET avatar_key = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?");
             PreparedStatement assignPreset = connection.prepareStatement("""
                     UPDATE avatar_preset
                        SET assigned_user_id = ?, assigned_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                      WHERE avatar_key = ? AND assigned_user_id IS NULL
                     """)) {
            for (int index = 0; index < users.size(); index++) {
                long userId = users.get(index);
                String avatarKey = avatarKeys.get(index);
                updateUser.setString(1, avatarKey);
                updateUser.setLong(2, userId);
                updateUser.addBatch();
                assignPreset.setLong(1, userId);
                assignPreset.setString(2, avatarKey);
                assignPreset.addBatch();
            }
            updateUser.executeBatch();
            int[] assigned = assignPreset.executeBatch();
            for (int count : assigned) {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException("玩家头像预设分配失败");
                }
            }
        }
    }

    private static List<Long> usersToReassign(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT id FROM sys_user WHERE avatar_key LIKE 'preset-%'
                     UNION
                     SELECT u.id
                       FROM sys_user u
                       JOIN demo_user_account a ON a.sys_user_id = u.id
                      WHERE a.player_kind IN ('NORMAL', 'BOT') AND a.status <> 'DELETED'
                     UNION
                     SELECT assigned_user_id FROM avatar_preset WHERE assigned_user_id IS NOT NULL
                     ORDER BY 1
                     """)) {
            List<Long> users = new ArrayList<>();
            while (rows.next()) users.add(rows.getLong(1));
            return users;
        }
    }
}
