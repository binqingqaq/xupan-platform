package com.xupan.server.game.repository;

import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.service.SettlementResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        try {
            jdbcTemplate.update("""
                    INSERT INTO game_issue
                        (issue_number, status, phase, opened_at, issue_started_at,
                         betting_ends_at, draw_ends_at)
                    VALUES (?, 'OPEN', 'BETTING', ?, ?, ?, ?)
                    """, issueNumber, timestamp(startedAt), timestamp(startedAt),
                    timestamp(bettingEndsAt), timestamp(drawEndsAt));
        } catch (DuplicateKeyException duplicate) {
            // A reset request and the scheduled finalizer can create the same next issue concurrently.
            // The existing row is the authoritative state; callers still append events idempotently.
        }
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

    public Optional<IssueRecord> findLatestSettledIssue() {
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE phase = 'SETTLED'
                   AND number_1 IS NOT NULL
                   AND number_2 IS NOT NULL
                   AND number_3 IS NOT NULL
                   AND number_4 IS NOT NULL
                   AND number_5 IS NOT NULL
                   AND number_6 IS NOT NULL
                   AND number_7 IS NOT NULL
                   AND number_8 IS NOT NULL
                 ORDER BY id DESC
                 LIMIT 1
                """);
    }

    public Optional<IssueRecord> findIssueByIssueNumber(String issueNumber) {
        if (issueNumber == null || issueNumber.isBlank()) {
            return Optional.empty();
        }
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE issue_number = ?
                """, issueNumber.trim());
    }

    public List<IssueRecord> findSettledIssues(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("开奖历史查询数量必须在 1 到 100 之间");
        }
        return findIssues("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE phase = 'SETTLED' AND settled_at IS NOT NULL
                   AND number_1 IS NOT NULL AND number_2 IS NOT NULL
                   AND number_3 IS NOT NULL AND number_4 IS NOT NULL
                   AND number_5 IS NOT NULL AND number_6 IS NOT NULL
                   AND number_7 IS NOT NULL AND number_8 IS NOT NULL
                 ORDER BY settled_at DESC, id DESC
                 LIMIT ?
                """, limit);
    }

    /**
     * Reads the current fixed-size settled block from oldest to newest.
     * The complete history remains in game_issue; this only selects the block
     * currently visible in a bounded trend chart.
     */
    public List<IssueRecord> findLatestSettledBlock(int blockSize) {
        if (blockSize < 1 || blockSize > 100) {
            throw new IllegalArgumentException("开奖走势分组数量必须在 1 到 100 之间");
        }
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM game_issue
                 WHERE phase = 'SETTLED' AND settled_at IS NOT NULL
                   AND number_1 IS NOT NULL AND number_2 IS NOT NULL
                   AND number_3 IS NOT NULL AND number_4 IS NOT NULL
                   AND number_5 IS NOT NULL AND number_6 IS NOT NULL
                   AND number_7 IS NOT NULL AND number_8 IS NOT NULL
                """, Long.class);
        if (total == null || total == 0) {
            return List.of();
        }
        int currentBlockSize = (int) (total % blockSize);
        if (currentBlockSize == 0) {
            currentBlockSize = blockSize;
        }
        List<IssueRecord> currentBlock = new ArrayList<>(findSettledIssues(currentBlockSize));
        Collections.reverse(currentBlock);
        return List.copyOf(currentBlock);
    }

    public List<WinnerRecord> findWinningBets(String issueNumber) {
        if (issueNumber == null || issueNumber.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT b.id, a.user_code, a.display_name, b.ball_number, b.play_type,
                       b.stake, b.net_profit
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.issue_number = ? AND b.settlement_status = 'WIN'
                 ORDER BY b.id
                """, (rs, rowNum) -> new WinnerRecord(rs.getLong("id"),
                rs.getString("user_code"), rs.getString("display_name"),
                rs.getInt("ball_number"), rs.getString("play_type"),
                rs.getBigDecimal("stake"), rs.getBigDecimal("net_profit")), issueNumber.trim());
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

    /**
     * Persists a bet against the virtual wallet account selected by the caller.
     * The account id is deliberately mandatory so a game request cannot fall
     * back to the historical DEMO-USER account.
     */
    public long saveBetWithOddsSnapshot(long accountId, String betCode, String idempotencyKey,
                                        String issueNumber, int ballNumber,
                                        PlayType playType, List<Integer> parameters,
                                        BigDecimal stake, BigDecimal odds) {
        requireFirstBall(ballNumber);
        String parameterText = parameters == null ? "" : parameters.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO game_bet
                        (user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type, parameters_text,
                         stake, odds_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setLong(1, accountId);
            statement.setString(2, betCode);
            statement.setString(3, idempotencyKey);
            statement.setString(4, issueNumber);
            statement.setInt(5, ballNumber);
            statement.setString(6, playType.name());
            statement.setString(7, parameterText);
            statement.setBigDecimal(8, stake);
            statement.setBigDecimal(9, odds);
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

    private static void requireFirstBall(int ballNumber) {
        if (ballNumber != 1) {
            throw new IllegalArgumentException("下注无效：当前只支持第1球");
        }
    }

    public List<BetRecord> findBetsByIssue(String issueNumber) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE issue_number = ?
                 ORDER BY id
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation")), issueNumber);
    }

    public List<BetAuditRecord> findBetAuditByIssue(String issueNumber) {
        return jdbcTemplate.query("""
                SELECT b.id, a.display_name, b.play_type, b.parameters_text, b.stake
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.issue_number = ?
                 ORDER BY b.id
                """, (rs, rowNum) -> new BetAuditRecord(
                rs.getLong("id"), rs.getString("display_name"),
                PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake")), issueNumber);
    }

    public List<BetRecord> findBetsByAccountId(long accountId, SettlementStatus status, int limit) {
        if (accountId <= 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("注单查询参数无效");
        }
        String statusClause = status == null ? "" : " AND settlement_status = ?";
        if (status == null) {
            return jdbcTemplate.query("""
                    SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type,
                           parameters_text, stake, odds_snapshot, settlement_status, net_profit, explanation
                      FROM game_bet
                     WHERE user_id = ?
                     ORDER BY id DESC
                     LIMIT ?
                    """, betMapper(), accountId, limit);
        }
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type,
                       parameters_text, stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE user_id = ?
                """ + statusClause + """
                 ORDER BY id DESC
                 LIMIT ?
                """, betMapper(), accountId, status.name(), limit);
    }

    public BetDayStatistics findBetDayStatistics(long accountId, Instant fromInclusive, Instant toExclusive) {
        if (accountId <= 0 || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("注单日统计查询参数无效");
        }
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(stake), 0) AS turnover,
                       COALESCE(SUM(CASE WHEN settlement_status <> 'PENDING' THEN net_profit ELSE 0 END), 0)
                           AS net_profit
                  FROM game_bet
                 WHERE user_id = ? AND created_at >= ? AND created_at < ?
                """, (rs, rowNum) -> new BetDayStatistics(
                rs.getBigDecimal("turnover"), rs.getBigDecimal("net_profit")),
                accountId, timestamp(fromInclusive), timestamp(toExclusive));
    }

    public Optional<BetRecord> findBetByCode(String betCode) {
        return findBetsByCode(betCode).stream().findFirst();
    }

    public Optional<BetRecord> findBetByIdempotencyKey(long accountId, String idempotencyKey) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE user_id = ? AND request_idempotency_key = ?
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation")), accountId, idempotencyKey)
                .stream().findFirst();
    }

    public Optional<BetRecord> findBetByAccountIdAndIdempotencyKey(long accountId, String idempotencyKey) {
        return findBetByIdempotencyKey(accountId, idempotencyKey);
    }

    public BetRecord requireBetRequestMatch(long accountId, String idempotencyKey,
                                            String issueNumber, int ballNumber, PlayType playType,
                                            List<Integer> parameters, BigDecimal stake) {
        BetRecord existing = findBetByIdempotencyKey(accountId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("WALLET_IDEMPOTENCY_NOT_FOUND: 注单不存在"));
        BigDecimal normalizedStake = normalizedStake(stake);
        List<Integer> normalizedParameters = parameters == null ? List.of() : List.copyOf(parameters);
        if (!existing.issueNumber().equals(issueNumber) || existing.ballNumber() != ballNumber
                || existing.playType() != playType || !existing.parameters().equals(normalizedParameters)
                || existing.stake().compareTo(normalizedStake) != 0) {
            throw new IllegalStateException("WALLET_IDEMPOTENCY_CONFLICT: 注单请求参数不一致");
        }
        return existing;
    }

    private List<BetRecord> findBetsByCode(String betCode) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation
                  FROM game_bet
                 WHERE bet_code = ?
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation")), betCode);
    }

    private static org.springframework.jdbc.core.RowMapper<BetRecord> betMapper() {
        return (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation"));
    }

    private Optional<IssueRecord> findIssue(String sql) {
        return jdbcTemplate.query(sql, rs -> rs.next()
                ? Optional.of(new IssueRecord(rs.getString("issue_number"), rs.getString("status"),
                rs.getString("phase"), numbers(rs), instant(rs, "issue_started_at"),
                instant(rs, "betting_ends_at"), instant(rs, "draw_ends_at"), instant(rs, "settled_at")))
                : Optional.empty());
    }

    private Optional<IssueRecord> findIssue(String sql, Object... args) {
        return jdbcTemplate.query(sql, rs -> rs.next()
                ? Optional.of(new IssueRecord(rs.getString("issue_number"), rs.getString("status"),
                rs.getString("phase"), numbers(rs), instant(rs, "issue_started_at"),
                instant(rs, "betting_ends_at"), instant(rs, "draw_ends_at"),
                instant(rs, "settled_at")))
                : Optional.empty(), args);
    }

    private List<IssueRecord> findIssues(String sql, int limit) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IssueRecord(
                rs.getString("issue_number"), rs.getString("status"), rs.getString("phase"),
                numbers(rs), instant(rs, "issue_started_at"), instant(rs, "betting_ends_at"),
                instant(rs, "draw_ends_at"), instant(rs, "settled_at")), limit);
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

    /** game_bet.user_id is the demo_user_account primary key, not sys_user.id. */
    public record BetRecord(long id, long accountId, String betCode, String requestIdempotencyKey,
                            String issueNumber, int ballNumber,
                            PlayType playType, List<Integer> parameters, BigDecimal stake,
                            BigDecimal odds, SettlementStatus settlementStatus,
                            BigDecimal netProfit, String explanation) {
    }

    public record BetAuditRecord(long id, String displayName, PlayType playType,
                                 List<Integer> parameters, BigDecimal stake) {
    }

    public record BetDayStatistics(BigDecimal turnover, BigDecimal netProfit) {
        public BetDayStatistics {
            turnover = normalizeMoney(turnover);
            netProfit = normalizeMoney(netProfit);
        }

        private static BigDecimal normalizeMoney(BigDecimal value) {
            return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public record WinnerRecord(long betId, String userCode, String displayName, int ballNumber,
                               String playType, BigDecimal stake, BigDecimal netProfit) {
    }

    private static BigDecimal normalizedStake(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 2) {
            throw new IllegalArgumentException("WALLET_AMOUNT_INVALID: stake 必须为正数且最多两位小数");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static List<Integer> numbers(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        List<Integer> numbers = new ArrayList<>(8);
        for (int index = 1; index <= 8; index++) {
            numbers.add(resultSet.getObject("number_" + index, Integer.class));
        }
        return numbers;
    }
}
