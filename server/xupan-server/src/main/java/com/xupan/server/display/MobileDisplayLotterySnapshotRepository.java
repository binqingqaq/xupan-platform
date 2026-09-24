package com.xupan.server.display;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Repository
public class MobileDisplayLotterySnapshotRepository {

    public static final String SOURCE_CODE = "REFERENCE_168";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MobileDisplayLotterySnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveAll(List<MobileDisplayHomeResponse.LotteryCard> cards, Instant fetchedAt) {
        for (MobileDisplayHomeResponse.LotteryCard card : cards) {
            String cardJson = objectMapper.writeValueAsString(card);
            jdbcTemplate.update("""
                    INSERT INTO mobile_display_lottery_snapshot
                        (source_code, source_lot_code, issue_no, next_issue_no, lot_name,
                         next_draw_at, card_json, payload_hash, fetched_at, updated_at)
                    VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE
                        next_issue_no = VALUES(next_issue_no),
                        lot_name = VALUES(lot_name),
                        next_draw_at = VALUES(next_draw_at),
                        card_json = VALUES(card_json),
                        payload_hash = VALUES(payload_hash),
                        fetched_at = VALUES(fetched_at),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    SOURCE_CODE,
                    card.lotCode(),
                    normalizeIssue(card.issue()),
                    card.name(),
                    timestamp(card.nextDrawAt()),
                    cardJson,
                    sha256(cardJson),
                    timestamp(fetchedAt));
        }
    }

    public List<String> findLatestCardJson() {
        return jdbcTemplate.query("""
                SELECT card_json
                  FROM (
                        SELECT source_lot_code, card_json, id, fetched_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY source_lot_code
                                   ORDER BY fetched_at DESC, id DESC
                               ) AS row_number
                          FROM mobile_display_lottery_snapshot
                         WHERE source_code = ?
                       ) latest
                 WHERE row_number = 1
                 ORDER BY source_lot_code
                """, (rs, rowNum) -> rs.getString("card_json"), SOURCE_CODE);
    }

    public Optional<Instant> findLatestFetchedAt() {
        return jdbcTemplate.query("""
                SELECT MAX(fetched_at) AS fetched_at
                  FROM mobile_display_lottery_snapshot
                 WHERE source_code = ?
                """, rs -> rs.next() ? Optional.ofNullable(instant(rs.getTimestamp("fetched_at"))) : Optional.empty(),
                SOURCE_CODE);
    }

    private static String normalizeIssue(String issue) {
        return issue == null || issue.isBlank() ? "UNKNOWN" : issue.trim();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("MOBILE_DISPLAY_HASH_FAILED", exception);
        }
    }
}
