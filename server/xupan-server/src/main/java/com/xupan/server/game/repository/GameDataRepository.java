package com.xupan.server.game.repository;

import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.service.SettlementResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class GameDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public GameDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveIssue(String issueNumber, String status, List<Integer> numbers) {
        if (numbers != null && numbers.size() != 8) {
            throw new IllegalArgumentException("期号结果必须包含 8 个号码");
        }
        List<Integer> values = numbers == null ? new ArrayList<>(Collections.nCopies(8, null)) : numbers;
        int updated = jdbcTemplate.update("""
                UPDATE game_issue
                   SET status = ?, number_1 = ?, number_2 = ?, number_3 = ?, number_4 = ?,
                       number_5 = ?, number_6 = ?, number_7 = ?, number_8 = ?,
                       phase = CASE WHEN ? = 'OPEN' THEN 'BETTING' ELSE 'SETTLED' END,
                       updated_at = CURRENT_TIMESTAMP,
                       closed_at = CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE closed_at END,
                       settled_at = CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE settled_at END
                 WHERE issue_number = ?
                """, status, values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4), values.get(5), values.get(6), values.get(7), status, status, status, issueNumber);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO game_issue
                        (issue_number, status, number_1, number_2, number_3, number_4,
                         number_5, number_6, number_7, number_8, phase, opened_at, issue_started_at,
                         closed_at, settled_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            CASE WHEN ? = 'OPEN' THEN 'BETTING' ELSE 'SETTLED' END,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                            CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                            CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                    """, issueNumber, status, values.get(0), values.get(1), values.get(2), values.get(3),
                    values.get(4), values.get(5), values.get(6), values.get(7), status, status, status);
        }
    }

    public void saveBettingIssue(String issueNumber, Instant startedAt) {
        Instant bettingEndsAt = startedAt.plusSeconds(180);
        Instant drawEndsAt = startedAt.plusSeconds(300);
        jdbcTemplate.update("""
                INSERT INTO game_issue
                    (issue_number, status, phase, opened_at, issue_started_at,
                     betting_ends_at, draw_ends_at)
                VALUES (?, 'OPEN', 'BETTING', ?, ?, ?, ?)
                """, issueNumber, timestamp(startedAt), timestamp(startedAt),
                timestamp(bettingEndsAt), timestamp(drawEndsAt));
    }

    public void initializeSchedule(String issueNumber, Instant startedAt) {
        jdbcTemplate.update("""
                UPDATE game_issue
                   SET phase = COALESCE(phase, 'BETTING'),
                       issue_started_at = COALESCE(issue_started_at, ?),
                       betting_ends_at = COALESCE(betting_ends_at, ?),
                       draw_ends_at = COALESCE(draw_ends_at, ?),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE issue_number = ?
                """, timestamp(startedAt), timestamp(startedAt.plusSeconds(180)),
                timestamp(startedAt.plusSeconds(300)), issueNumber);
    }

    public boolean transitionPhase(String issueNumber, String expectedPhase, String nextPhase) {
        return jdbcTemplate.update("""
                UPDATE game_issue
                   SET phase = ?, status = CASE WHEN ? = 'BETTING' THEN 'OPEN' ELSE 'CLOSED' END,
                       closed_at = CASE WHEN ? IN ('DRAWING', 'SETTLED') THEN COALESCE(closed_at, CURRENT_TIMESTAMP) ELSE closed_at END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE issue_number = ? AND phase = ?
                """, nextPhase, nextPhase, nextPhase, issueNumber, expectedPhase) == 1;
    }

    public boolean saveFinalResult(String issueNumber, List<Integer> numbers, Instant settledAt) {
        if (numbers == null || numbers.size() != 8) {
            throw new IllegalArgumentException("期号结果必须包含 8 个号码");
        }
        return jdbcTemplate.update("""
                UPDATE game_issue
                   SET phase = 'SETTLED', status = 'CLOSED',
                       number_1 = ?, number_2 = ?, number_3 = ?, number_4 = ?,
                       number_5 = ?, number_6 = ?, number_7 = ?, number_8 = ?,
                       closed_at = COALESCE(closed_at, ?), settled_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE issue_number = ? AND phase = 'DRAWING'
                """, numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3),
                numbers.get(4), numbers.get(5), numbers.get(6), numbers.get(7),
                timestamp(settledAt), timestamp(settledAt), issueNumber) == 1;
    }

    public Optional<IssueRecord> findCurrentIssue() {
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE phase IN ('BETTING', 'DRAWING')
                 ORDER BY id DESC
                 LIMIT 1
                """);
    }

    public Optional<IssueRecord> findLatestIssue() {
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 ORDER BY id DESC
                 LIMIT 1
                """);
    }

    public void saveOdds(PlayType playType, BigDecimal odds) {
        int updated = jdbcTemplate.update("""
                UPDATE game_odds
                   SET odds = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE play_type = ?
                """, odds, playType.name());
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO game_odds (play_type, odds) VALUES (?, ?)", playType.name(), odds);
        }
    }

    public Optional<BigDecimal> findOdds(PlayType playType) {
        return jdbcTemplate.query("SELECT odds FROM game_odds WHERE play_type = ? AND enabled = TRUE",
                rs -> rs.next() ? Optional.of(rs.getBigDecimal(1)) : Optional.empty(), playType.name());
    }

    public long saveBetWithOddsSnapshot(String betCode, String issueNumber, int ballNumber,
                                        PlayType playType, List<Integer> parameters,
                                        BigDecimal stake, BigDecimal odds) {
        return saveBetWithOddsSnapshot(1L, betCode, issueNumber, ballNumber, playType,
                parameters, stake, odds);
    }

    public long saveBetWithOddsSnapshot(long userId, String betCode, String issueNumber, int ballNumber,
                                        PlayType playType, List<Integer> parameters,
                                        BigDecimal stake, BigDecimal odds) {
        String parameterText = parameters == null ? "" : parameters.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO game_bet
                        (user_id, bet_code, issue_number, ball_number, play_type, parameters_text,
                         stake, odds_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setLong(1, userId);
            statement.setString(2, betCode);
            statement.setString(3, issueNumber);
            statement.setInt(4, ballNumber);
            statement.setString(5, playType.name());
            statement.setString(6, parameterText);
            statement.setBigDecimal(7, stake);
            statement.setBigDecimal(8, odds);
            return statement;
        });
        Long id = jdbcTemplate.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
        if (id == null) {
            throw new IllegalStateException("保存注单后未找到主键");
        }
        return id;
    }

    public boolean settleBetOnce(long betId, SettlementResult settlement) {
        return jdbcTemplate.update("""
                UPDATE game_bet
                   SET settlement_status = ?, net_profit = ?, explanation = ?, settled_at = ?
                 WHERE id = ? AND settlement_status = 'PENDING' AND settled_at IS NULL
                """, settlement.status().name(), settlement.netProfit(), settlement.explanation(),
                Timestamp.from(Instant.now()), betId) == 1;
    }

    public List<BetRecord> findBetsByIssue(String issueNumber) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE issue_number = ?
                 ORDER BY id
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation")), issueNumber);
    }

    public Optional<BetRecord> findBetByCode(String betCode) {
        return findBetsByCode(betCode).stream().findFirst();
    }

    private List<BetRecord> findBetsByCode(String betCode) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE bet_code = ?
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation")), betCode);
    }

    private Optional<IssueRecord> findIssue(String sql) {
        return jdbcTemplate.query(sql, rs -> rs.next()
                ? Optional.of(new IssueRecord(rs.getString("issue_number"), rs.getString("status"),
                rs.getString("phase"), numbers(rs), instant(rs, "issue_started_at"),
                instant(rs, "betting_ends_at"), instant(rs, "draw_ends_at"), instant(rs, "settled_at")))
                : Optional.empty());
    }

    private static List<Integer> parseParameters(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(Integer::valueOf)
                .toList();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record IssueRecord(String issueNumber, String status, String phase, List<Integer> numbers,
                              Instant startedAt, Instant bettingEndsAt, Instant drawEndsAt, Instant settledAt) {
    }

    public record BetRecord(long id, long userId, String betCode, String issueNumber, int ballNumber,
                            PlayType playType, List<Integer> parameters, BigDecimal stake,
                            BigDecimal odds, SettlementStatus settlementStatus,
                            BigDecimal netProfit, String explanation) {
    }

    private static List<Integer> numbers(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        List<Integer> numbers = new ArrayList<>(8);
        for (int index = 1; index <= 8; index++) {
            numbers.add(resultSet.getObject("number_" + index, Integer.class));
        }
        return numbers;
    }
}
