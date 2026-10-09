package com.xupan.server.game.repository;

import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Repository
public class PlayerBetSummaryRepository {

    private final JdbcTemplate jdbc;

    public PlayerBetSummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GameDataRepository.BetDayStatistics findBetDayStatistics(long accountId,
                                                                    Instant fromInclusive,
                                                                    Instant toExclusive) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(stake), 0) AS turnover,
                       COALESCE(SUM(CASE WHEN settlement_status NOT IN ('PENDING', 'CANCELED')
                                         THEN net_profit ELSE 0 END), 0) AS net_profit
                  FROM game_bet
                 WHERE user_id = ? AND settlement_status <> 'CANCELED'
                   AND created_at >= ? AND created_at < ?
                """, (rs, rowNum) -> new GameDataRepository.BetDayStatistics(
                rs.getBigDecimal("turnover"), rs.getBigDecimal("net_profit")),
                accountId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
    }

    public List<GameDataRepository.BetRecord> findSettledBets(long accountId,
                                                               Instant fromInclusive,
                                                               Instant toExclusive,
                                                               int limit) {
        return jdbc.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                       play_type, parameters_text, stake, odds_snapshot, settlement_status,
                       net_profit, explanation, created_at
                  FROM game_bet
                 WHERE user_id = ?
                   AND settlement_status NOT IN ('PENDING', 'CANCELED')
                   AND created_at >= ? AND created_at < ?
                 ORDER BY id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new GameDataRepository.BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation"),
                rs.getTimestamp("created_at").toInstant()),
                accountId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive), limit);
    }

    private static List<Integer> parseParameters(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(Integer::valueOf).toList();
    }
}
