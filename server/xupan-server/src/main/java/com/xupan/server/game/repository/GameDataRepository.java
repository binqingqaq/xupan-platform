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
                       updated_at = CURRENT_TIMESTAMP,
                       closed_at = CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE closed_at END
                 WHERE issue_number = ?
                """, status, values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4), values.get(5), values.get(6), values.get(7), status, issueNumber);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO game_issue
                        (issue_number, status, number_1, number_2, number_3, number_4,
                         number_5, number_6, number_7, number_8, opened_at, closed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                            CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                    """, issueNumber, status, values.get(0), values.get(1), values.get(2), values.get(3),
                    values.get(4), values.get(5), values.get(6), values.get(7), status);
        }
    }

    public Optional<IssueRecord> findCurrentIssue() {
        return findIssue("""
                SELECT issue_number, status, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8
                  FROM game_issue
                 WHERE status = 'OPEN'
                 ORDER BY id DESC
                 LIMIT 1
                """);
    }

    public Optional<IssueRecord> findLatestIssue() {
        return findIssue("""
                SELECT issue_number, status, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8
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
        String parameterText = parameters == null ? "" : parameters.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO game_bet
                        (bet_code, issue_number, ball_number, play_type, parameters_text,
                         stake, odds_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setString(1, betCode);
            statement.setString(2, issueNumber);
            statement.setInt(3, ballNumber);
            statement.setString(4, playType.name());
            statement.setString(5, parameterText);
            statement.setBigDecimal(6, stake);
            statement.setBigDecimal(7, odds);
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
                SELECT id, bet_code, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE issue_number = ?
                 ORDER BY id
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getString("bet_code"), rs.getString("issue_number"),
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
                SELECT id, bet_code, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE bet_code = ?
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getString("bet_code"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation")), betCode);
    }

    private Optional<IssueRecord> findIssue(String sql) {
        return jdbcTemplate.query(sql, rs -> rs.next()
                ? Optional.of(new IssueRecord(rs.getString("issue_number"), rs.getString("status"), numbers(rs)))
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

    public record IssueRecord(String issueNumber, String status, List<Integer> numbers) {
    }

    public record BetRecord(long id, String betCode, String issueNumber, int ballNumber,
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
