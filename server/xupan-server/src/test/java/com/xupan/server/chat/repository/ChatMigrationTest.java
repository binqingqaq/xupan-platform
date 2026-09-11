package com.xupan.server.chat.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ChatMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void chatMigrationsCreateRequiredTablesAndMainRoom() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(tableCount("chat_room")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room WHERE room_code = 'main'", Integer.class)).isEqualTo(1);
        assertThat(columnCount("chat_message", "payload_json")).isEqualTo(1);
        assertThat(constraintCount("chat_message", "uk_chat_message_room_sequence")).isEqualTo(1);
        assertThat(constraintCount("chat_message", "uk_chat_message_client_key")).isEqualTo(1);
        assertThat(constraintCount("chat_outbox", "uk_chat_outbox_message_event")).isEqualTo(1);
        assertThat(constraintCount("chat_user_mute", "fk_chat_mute_user")).isEqualTo(1);
    }

    @Test
    void mainRoomSeedIsIdempotent() {
        int before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room WHERE room_code = 'main'", Integer.class);
        jdbcTemplate.update("INSERT INTO chat_room (room_code, display_name, status) "
                + "SELECT 'main', '公开大厅', 'OPEN' "
                + "WHERE NOT EXISTS (SELECT 1 FROM chat_room WHERE room_code = 'main')");
        int after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room WHERE room_code = 'main'", Integer.class);
        assertThat(before).isEqualTo(1);
        assertThat(after).isEqualTo(before);
    }

    private int tableCount(String tableName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE UPPER(table_schema) = UPPER(?) AND UPPER(table_name) = UPPER(?)
                """, Integer.class, currentSchema(), tableName);
    }

    private int columnCount(String tableName, String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE UPPER(table_schema) = UPPER(?) AND UPPER(table_name) = UPPER(?)
                   AND UPPER(column_name) = UPPER(?)
                """, Integer.class, currentSchema(), tableName, columnName);
    }

    private int constraintCount(String tableName, String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT constraint_name) FROM information_schema.table_constraints
                 WHERE UPPER(table_schema) = UPPER(?) AND UPPER(table_name) = UPPER(?)
                   AND UPPER(constraint_name) = UPPER(?)
                """, Integer.class, currentSchema(), tableName, constraintName);
    }

    private String currentSchema() {
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            String schema = connection.getSchema();
            return schema == null || schema.isBlank() ? connection.getCatalog() : schema;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("无法读取当前数据库 schema", exception);
        }
    }
}
