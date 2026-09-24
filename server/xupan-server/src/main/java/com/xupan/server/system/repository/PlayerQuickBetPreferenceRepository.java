package com.xupan.server.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class PlayerQuickBetPreferenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlayerQuickBetPreferenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<List<BigDecimal>> findByUserId(long userId) {
        return jdbcTemplate.query("""
                SELECT amount_1, amount_2, amount_3, amount_4, amount_5
                  FROM player_quick_bet_preference
                 WHERE user_id = ?
                """, (rs, rowNum) -> List.of(
                rs.getBigDecimal("amount_1"),
                rs.getBigDecimal("amount_2"),
                rs.getBigDecimal("amount_3"),
                rs.getBigDecimal("amount_4"),
                rs.getBigDecimal("amount_5")
        ), userId).stream().findFirst();
    }

    public void upsert(long userId, List<BigDecimal> amounts) {
        jdbcTemplate.update("""
                INSERT INTO player_quick_bet_preference
                    (user_id, amount_1, amount_2, amount_3, amount_4, amount_5, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    amount_1 = VALUES(amount_1),
                    amount_2 = VALUES(amount_2),
                    amount_3 = VALUES(amount_3),
                    amount_4 = VALUES(amount_4),
                    amount_5 = VALUES(amount_5),
                    updated_at = CURRENT_TIMESTAMP
                """, userId, amounts.get(0), amounts.get(1), amounts.get(2),
                amounts.get(3), amounts.get(4));
    }
}
