package com.xupan.server.game.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class GameIssueEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public GameIssueEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean appendOnce(String issueNumber, String eventType, String message, Instant createdAt) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO game_issue_event (issue_number, event_type, message, created_at)
                    VALUES (?, ?, ?, ?)
                    """, issueNumber, eventType, message, java.sql.Timestamp.from(createdAt)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public List<EventRecord> findByIssue(String issueNumber) {
        return jdbcTemplate.query("""
                SELECT id, issue_number, event_type, message, created_at
                  FROM game_issue_event
                 WHERE issue_number = ?
                 ORDER BY id
                """, (rs, rowNum) -> new EventRecord(rs.getLong("id"), rs.getString("issue_number"),
                rs.getString("event_type"), rs.getString("message"), rs.getTimestamp("created_at").toInstant()),
                issueNumber);
    }

    public record EventRecord(long id, String issueNumber, String eventType, String message, Instant createdAt) {
    }
}
