package com.xupan.server.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class ChatMuteRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatMuteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isMuted(long roomId, long userId, Instant now) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM chat_user_mute
                 WHERE room_id = ? AND user_id = ?
                   AND start_at <= ?
                   AND (end_at IS NULL OR end_at > ?)
                   AND revoked_at IS NULL
                """, Integer.class, roomId, userId, Timestamp.from(now), Timestamp.from(now));
        return count != null && count > 0;
    }
}
