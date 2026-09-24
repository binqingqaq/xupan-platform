package com.xupan.server.game.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class GameBettingConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public GameBettingConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ConfigRecord> find() {
        return jdbcTemplate.query("""
                SELECT display_odds, special_rebate,
                       special_limit, issue_total_limit, positive_limit, angle_limit,
                       strict_limit, tong_limit, car_limit, odd_even_limit,
                       big_small_limit, fan_limit, add_limit,
                       player_max_stake, player_min_stake
                  FROM game_betting_config
                 WHERE id = 1
                """, (rs, rowNum) -> new ConfigRecord(
                rs.getInt("display_odds"), rs.getInt("special_rebate"),
                rs.getInt("special_limit"), rs.getInt("issue_total_limit"),
                rs.getInt("positive_limit"), rs.getInt("angle_limit"),
                rs.getInt("strict_limit"), rs.getInt("tong_limit"),
                rs.getInt("car_limit"), rs.getInt("odd_even_limit"),
                rs.getInt("big_small_limit"), rs.getInt("fan_limit"),
                rs.getInt("add_limit"), rs.getInt("player_max_stake"),
                rs.getInt("player_min_stake")
        )).stream().findFirst();
    }

    public int updateDisplay(int displayOdds, int specialRebate) {
        return jdbcTemplate.update("""
                UPDATE game_betting_config
                   SET display_odds = ?, special_rebate = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = 1
                """, displayOdds, specialRebate);
    }

    public int updateLimits(ConfigRecord config) {
        return jdbcTemplate.update("""
                UPDATE game_betting_config
                   SET special_limit = ?,
                       issue_total_limit = ?,
                       positive_limit = ?,
                       angle_limit = ?,
                       strict_limit = ?,
                       tong_limit = ?,
                       car_limit = ?,
                       odd_even_limit = ?,
                       big_small_limit = ?,
                       fan_limit = ?,
                       add_limit = ?,
                       player_max_stake = ?,
                       player_min_stake = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = 1
                """, config.specialLimit(), config.issueTotalLimit(),
                config.positiveLimit(), config.angleLimit(), config.strictLimit(),
                config.tongLimit(), config.carLimit(), config.oddEvenLimit(),
                config.bigSmallLimit(), config.fanLimit(), config.addLimit(),
                config.playerMaxStake(), config.playerMinStake());
    }

    public record ConfigRecord(
            int displayOdds,
            int specialRebate,
            int specialLimit,
            int issueTotalLimit,
            int positiveLimit,
            int angleLimit,
            int strictLimit,
            int tongLimit,
            int carLimit,
            int oddEvenLimit,
            int bigSmallLimit,
            int fanLimit,
            int addLimit,
            int playerMaxStake,
            int playerMinStake
    ) {
        public ConfigRecord withDisplay(int nextDisplayOdds, int nextSpecialRebate) {
            return new ConfigRecord(nextDisplayOdds, nextSpecialRebate,
                    specialLimit, issueTotalLimit, positiveLimit, angleLimit,
                    strictLimit, tongLimit, carLimit, oddEvenLimit,
                    bigSmallLimit, fanLimit, addLimit, playerMaxStake, playerMinStake);
        }

        public ConfigRecord withLimits(int nextSpecialLimit, int nextIssueTotalLimit,
                                       int nextPositiveLimit, int nextAngleLimit,
                                       int nextStrictLimit, int nextTongLimit,
                                       int nextCarLimit, int nextOddEvenLimit,
                                       int nextBigSmallLimit, int nextFanLimit,
                                       int nextAddLimit, int nextPlayerMaxStake,
                                       int nextPlayerMinStake) {
            return new ConfigRecord(displayOdds, specialRebate,
                    nextSpecialLimit, nextIssueTotalLimit, nextPositiveLimit,
                    nextAngleLimit, nextStrictLimit, nextTongLimit, nextCarLimit,
                    nextOddEvenLimit, nextBigSmallLimit, nextFanLimit, nextAddLimit,
                    nextPlayerMaxStake, nextPlayerMinStake);
        }
    }
}
