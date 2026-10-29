package com.xupan.server.game.repository;

import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.service.SettlementResult;
import com.xupan.server.web.BusinessException;
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

    public static final String DEFAULT_GAME_CODE = "AU8";

    private final JdbcTemplate jdbcTemplate;

    public GameDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveIssue(String issueNumber, String status, List<Integer> numbers) {
        saveIssue(DEFAULT_GAME_CODE, issueNumber, status, numbers);
    }

    public void saveIssue(String gameCode, String issueNumber, String status, List<Integer> numbers) {
        if (gameCode == null || gameCode.isBlank()) throw new IllegalArgumentException("彩种键名不能为空");
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
                 WHERE game_code = ? AND issue_number = ? AND draw_confirmed_at IS NULL
                """, status, values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4), values.get(5), values.get(6), values.get(7), status, status, status,
                gameCode, issueNumber);
        if (updated == 0) {
            Long existing = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM game_issue WHERE game_code = ? AND issue_number = ?
                    """, Long.class, gameCode, issueNumber);
            Long confirmed = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM game_issue
                     WHERE game_code = ? AND issue_number = ? AND draw_confirmed_at IS NOT NULL
                    """, Long.class, gameCode, issueNumber);
            if (confirmed != null && confirmed > 0) {
                throw BusinessException.conflict("DRAW_RESULT_LOCKED", "开奖结果已确认，不能修改");
            }
            if (existing == null || existing == 0) {
                jdbcTemplate.update("""
                        INSERT INTO game_issue
                            (game_code, issue_number, status, number_1, number_2, number_3, number_4,
                             number_5, number_6, number_7, number_8, phase, opened_at, issue_started_at,
                             closed_at, settled_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                CASE WHEN ? = 'OPEN' THEN 'BETTING' ELSE 'SETTLED' END,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                                CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                                CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                        """, gameCode, issueNumber, status, values.get(0), values.get(1), values.get(2), values.get(3),
                        values.get(4), values.get(5), values.get(6), values.get(7), status, status, status);
            }
        }
    }

    public void saveBettingIssue(String issueNumber, Instant startedAt) {
        saveBettingIssue(DEFAULT_GAME_CODE, issueNumber, startedAt);
    }

    public void saveBettingIssue(String gameCode, String issueNumber, Instant startedAt) {
        if (gameCode == null || gameCode.isBlank()) throw new IllegalArgumentException("彩种键名不能为空");
        Instant bettingEndsAt = startedAt.plusSeconds(180);
        Instant drawEndsAt = startedAt.plusSeconds(300);
        try {
            jdbcTemplate.update("""
                    INSERT INTO game_issue
                        (game_code, issue_number, status, phase, opened_at, issue_started_at,
                         betting_ends_at, draw_ends_at)
                    VALUES (?, ?, 'OPEN', 'BETTING', ?, ?, ?, ?)
                    """, gameCode, issueNumber, timestamp(startedAt), timestamp(startedAt),
                    timestamp(bettingEndsAt), timestamp(drawEndsAt));
        } catch (DuplicateKeyException duplicate) {
            // A reset request and the scheduled finalizer can create the same next issue concurrently.
            // The existing row is the authoritative state; callers still append events idempotently.
        }
    }

    public void initializeSchedule(String issueNumber, Instant startedAt) {
        initializeSchedule(DEFAULT_GAME_CODE, issueNumber, startedAt);
    }

    public void initializeSchedule(String gameCode, String issueNumber, Instant startedAt) {
        jdbcTemplate.update("""
                UPDATE game_issue
                   SET phase = COALESCE(phase, 'BETTING'),
                       issue_started_at = COALESCE(issue_started_at, ?),
                       betting_ends_at = COALESCE(betting_ends_at, ?),
                       draw_ends_at = COALESCE(draw_ends_at, ?),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE game_code = ? AND issue_number = ?
                """, timestamp(startedAt), timestamp(startedAt.plusSeconds(180)),
                timestamp(startedAt.plusSeconds(300)), gameCode, issueNumber);
    }

    public boolean transitionPhase(String issueNumber, String expectedPhase, String nextPhase) {
        return transitionPhase(DEFAULT_GAME_CODE, issueNumber, expectedPhase, nextPhase);
    }

    public boolean transitionPhase(String gameCode, String issueNumber,
                                   String expectedPhase, String nextPhase) {
        return jdbcTemplate.update("""
                UPDATE game_issue
                   SET phase = ?, status = CASE WHEN ? = 'BETTING' THEN 'OPEN' ELSE 'CLOSED' END,
                       closed_at = CASE WHEN ? IN ('DRAWING', 'SETTLED') THEN COALESCE(closed_at, CURRENT_TIMESTAMP) ELSE closed_at END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE game_code = ? AND issue_number = ? AND phase = ?
                """, nextPhase, nextPhase, nextPhase, gameCode, issueNumber, expectedPhase) == 1;
    }

    public boolean saveFinalResult(String issueNumber, List<Integer> numbers, Instant settledAt) {
        return saveFinalResult(DEFAULT_GAME_CODE, issueNumber, numbers, settledAt);
    }

    public boolean saveFinalResult(String gameCode, String issueNumber, List<Integer> numbers, Instant settledAt) {
        if (numbers == null || numbers.size() != 8) {
            throw new IllegalArgumentException("期号结果必须包含 8 个号码");
        }
        return jdbcTemplate.update("""
                UPDATE game_issue
                   SET phase = 'SETTLED', status = 'CLOSED',
                       number_1 = ?, number_2 = ?, number_3 = ?, number_4 = ?,
                       number_5 = ?, number_6 = ?, number_7 = ?, number_8 = ?,
                       closed_at = COALESCE(closed_at, ?), settled_at = ?,
                       draw_result_version = draw_result_version + 1,
                       draw_confirmed_at = ?, draw_confirmed_by = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE game_code = ? AND issue_number = ? AND phase = 'DRAWING' AND draw_confirmed_at IS NULL
                   AND (number_1 IS NULL OR number_1 = ?) AND (number_2 IS NULL OR number_2 = ?)
                   AND (number_3 IS NULL OR number_3 = ?) AND (number_4 IS NULL OR number_4 = ?)
                   AND (number_5 IS NULL OR number_5 = ?) AND (number_6 IS NULL OR number_6 = ?)
                   AND (number_7 IS NULL OR number_7 = ?) AND (number_8 IS NULL OR number_8 = ?)
                """, numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3),
                numbers.get(4), numbers.get(5), numbers.get(6), numbers.get(7),
                timestamp(settledAt), timestamp(settledAt), timestamp(settledAt), gameCode, issueNumber,
                numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3),
                numbers.get(4), numbers.get(5), numbers.get(6), numbers.get(7)) == 1;
    }

    public boolean saveConfirmedResult(String gameCode, String issueNumber, List<Integer> numbers,
                                       Instant confirmedAt, Long confirmedBy) {
        if (numbers == null || numbers.size() != 8
                || numbers.stream().anyMatch(number -> number == null || number < 1 || number > 20)) {
            throw new IllegalArgumentException("期号结果必须包含 8 个 1 到 20 的号码");
        }
        return jdbcTemplate.update("""
                UPDATE game_issue
                   SET status='CLOSED', phase='SETTLED',
                       number_1=?, number_2=?, number_3=?, number_4=?,
                       number_5=?, number_6=?, number_7=?, number_8=?,
                       closed_at=COALESCE(closed_at, ?), settled_at=?,
                       draw_result_version=draw_result_version + 1,
                       draw_confirmed_at=?, draw_confirmed_by=?, updated_at=CURRENT_TIMESTAMP
                 WHERE game_code=? AND issue_number=? AND phase='DRAWING' AND draw_confirmed_at IS NULL
                   AND (number_1 IS NULL OR number_1 = ?) AND (number_2 IS NULL OR number_2 = ?)
                   AND (number_3 IS NULL OR number_3 = ?) AND (number_4 IS NULL OR number_4 = ?)
                   AND (number_5 IS NULL OR number_5 = ?) AND (number_6 IS NULL OR number_6 = ?)
                   AND (number_7 IS NULL OR number_7 = ?) AND (number_8 IS NULL OR number_8 = ?)
                """, numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3),
                numbers.get(4), numbers.get(5), numbers.get(6), numbers.get(7),
                timestamp(confirmedAt), timestamp(confirmedAt), timestamp(confirmedAt), confirmedBy,
                gameCode, issueNumber, numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3),
                numbers.get(4), numbers.get(5), numbers.get(6), numbers.get(7)) == 1;
    }

    public Optional<IssueRecord> findCurrentIssue() {
        return findCurrentIssue(DEFAULT_GAME_CODE);
    }

    public Optional<IssueRecord> findCurrentIssue(String gameCode) {
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE game_code = ? AND phase IN ('BETTING', 'DRAWING')
                 ORDER BY id DESC
                 LIMIT 1
                """, gameCode);
    }

    public Optional<IssueRecord> findCurrentIssueForUpdate(String gameCode) {
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE game_code = ? AND phase IN ('BETTING', 'DRAWING')
                 ORDER BY id DESC
                 LIMIT 1
                 FOR UPDATE
                """, gameCode);
    }

    public Optional<IssueRecord> findLatestIssue() {
        return findLatestIssue(DEFAULT_GAME_CODE);
    }

    public Optional<IssueRecord> findLatestIssue(String gameCode) {
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE game_code = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, gameCode);
    }

    public Optional<IssueRecord> findLatestSettledIssue() {
        return findLatestSettledIssue(DEFAULT_GAME_CODE);
    }

    public Optional<IssueRecord> findLatestSettledIssue(String gameCode) {
        return findIssue("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE game_code = ?
                   AND phase = 'SETTLED'
                   AND number_1 IS NOT NULL AND number_2 IS NOT NULL
                   AND number_3 IS NOT NULL AND number_4 IS NOT NULL
                   AND number_5 IS NOT NULL AND number_6 IS NOT NULL
                   AND number_7 IS NOT NULL AND number_8 IS NOT NULL
                 ORDER BY id DESC
                 LIMIT 1
                """, gameCode);
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
        return findSettledIssues(DEFAULT_GAME_CODE, limit);
    }

    public List<IssueRecord> findSettledIssues(String gameCode, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("开奖历史查询数量必须在 1 到 100 之间");
        }
        return findIssues("""
                SELECT issue_number, status, phase, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8, issue_started_at,
                       betting_ends_at, draw_ends_at, settled_at
                  FROM game_issue
                 WHERE game_code = ?
                   AND phase = 'SETTLED' AND settled_at IS NOT NULL
                   AND number_1 IS NOT NULL AND number_2 IS NOT NULL
                   AND number_3 IS NOT NULL AND number_4 IS NOT NULL
                   AND number_5 IS NOT NULL AND number_6 IS NOT NULL
                   AND number_7 IS NOT NULL AND number_8 IS NOT NULL
                 ORDER BY settled_at DESC, id DESC
                 LIMIT ?
                """, gameCode, limit);
    }

    /**
     * Reads the current fixed-size settled block from oldest to newest.
     * The complete history remains in game_issue; this only selects the block
     * currently visible in a bounded trend chart.
     */
    public List<IssueRecord> findLatestSettledBlock(int blockSize) {
        return findLatestSettledBlock(DEFAULT_GAME_CODE, blockSize);
    }

    public List<IssueRecord> findLatestSettledBlock(String gameCode, int blockSize) {
        if (blockSize < 1 || blockSize > 100) {
            throw new IllegalArgumentException("开奖走势分组数量必须在 1 到 100 之间");
        }
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM game_issue
                 WHERE game_code = ?
                   AND phase = 'SETTLED' AND settled_at IS NOT NULL
                   AND number_1 IS NOT NULL AND number_2 IS NOT NULL
                   AND number_3 IS NOT NULL AND number_4 IS NOT NULL
                   AND number_5 IS NOT NULL AND number_6 IS NOT NULL
                   AND number_7 IS NOT NULL AND number_8 IS NOT NULL
                """, Long.class, gameCode);
        if (total == null || total == 0) {
            return List.of();
        }
        int currentBlockSize = (int) (total % blockSize);
        if (currentBlockSize == 0) {
            currentBlockSize = blockSize;
        }
        List<IssueRecord> currentBlock = new ArrayList<>(findSettledIssues(gameCode, currentBlockSize));
        Collections.reverse(currentBlock);
        return List.copyOf(currentBlock);
    }

    public List<WinnerRecord> findWinningBets(String issueNumber) {
        if (issueNumber == null || issueNumber.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT b.id, COALESCE(a.user_code, b.player_code_snapshot) AS user_code,
                       COALESCE(a.display_name, b.player_name_snapshot) AS display_name,
                       b.ball_number, b.play_type,
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
    public boolean isPrimaryGameEnabled() {
        return isGameEnabled(DEFAULT_GAME_CODE);
    }

    public boolean isGameEnabled(String gameCode) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM game_definition
                 WHERE game_code = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, Long.class, gameCode);
        return count != null && count > 0;
    }

    public boolean gameExists(String gameCode) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM game_definition
                 WHERE game_code = ? AND deleted_at IS NULL
                """, Long.class, gameCode);
        return count != null && count > 0;
    }

    public boolean isSpecialBettingEnabled(String gameCode) {
        Boolean enabled = jdbcTemplate.query("""
                SELECT special_enabled FROM game_definition
                 WHERE game_code = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, rs -> rs.next() && rs.getBoolean(1), gameCode);
        return Boolean.TRUE.equals(enabled);
    }

    public BettingRates findBettingRatesByAccountId(long accountId) {
        List<BettingRates> rows = jdbcTemplate.query("""
                SELECT COALESCE(d.rebate_rate, 0) rebate_rate,
                       COALESCE(a.special_rebate_rate, 0) special_rebate_rate
                  FROM demo_user_account d
                  LEFT JOIN agent a ON a.id = d.agent_id
                 WHERE d.id = ?
                """, (rs, rowNum) -> new BettingRates(rs.getBigDecimal("rebate_rate"),
                rs.getBigDecimal("special_rebate_rate")), accountId);
        return rows.isEmpty() ? BettingRates.ZERO : rows.get(0);
    }

    /**
     * 读取玩家所属机器的 BY220 配置快照和 13 项限额。
     * 玩家没有归属机器时返回 {@link MachineBettingConfig#DEFAULT}，行为与配置前一致。
     */
    public MachineBettingConfig findMachineBettingConfig(long accountId) {
        List<MachineBettingConfig> rows = jdbcTemplate.query("""
                SELECT COALESCE(a.odds_rate, 0) odds_rate,
                       COALESCE(a.special_odds_rate, 0) special_odds_rate,
                       COALESCE(a.close_seconds, 0) close_seconds,
                       COALESCE(a.cancel_seconds, 0) cancel_seconds,
                       COALESCE(a.manual_betting_open, TRUE) manual_betting_open,
                       COALESCE(a.rebate_mode, 'SINGLE') rebate_mode,
                       COALESCE(a.rebate_rate, 0) rebate_rate,
                       COALESCE(a.special_rebate_rate, 0) special_rebate_rate,
                       COALESCE(a.betting_config_version, 1) config_version,
                       a.id machine_id,
                       COALESCE(a.total_limit, 0) total_limit,
                       COALESCE(a.positive_limit, 0) positive_limit,
                       COALESCE(a.angle_limit, 0) angle_limit,
                       COALESCE(a.strict_limit, 0) strict_limit,
                       COALESCE(a.tong_limit, 0) tong_limit,
                       COALESCE(a.car_limit, 0) car_limit,
                       COALESCE(a.special_limit, 0) special_limit,
                       COALESCE(a.odd_even_limit, 0) odd_even_limit,
                       COALESCE(a.big_small_limit, 0) big_small_limit,
                       COALESCE(a.fan_limit, 0) fan_limit,
                       COALESCE(a.add_limit, 0) add_limit,
                       COALESCE(a.player_max_stake, 0) player_max_stake,
                       COALESCE(a.player_min_stake, 0) player_min_stake
                  FROM demo_user_account d
                  LEFT JOIN agent a ON a.id = d.agent_id
                 WHERE d.id = ?
                """, (rs, rowNum) -> new MachineBettingConfig(
                rs.getBigDecimal("odds_rate"), rs.getBigDecimal("special_odds_rate"),
                rs.getInt("close_seconds"), rs.getInt("cancel_seconds"), rs.getBoolean("manual_betting_open"),
                rs.getString("rebate_mode"), rs.getBigDecimal("rebate_rate"),
                rs.getBigDecimal("special_rebate_rate"), rs.getLong("machine_id"),
                rs.getInt("config_version"), new MachineBettingConfig.BettingLimits(
                        rs.getBigDecimal("total_limit"), rs.getBigDecimal("positive_limit"),
                        rs.getBigDecimal("angle_limit"), rs.getBigDecimal("strict_limit"),
                        rs.getBigDecimal("tong_limit"), rs.getBigDecimal("car_limit"),
                        rs.getBigDecimal("special_limit"), rs.getBigDecimal("odd_even_limit"),
                        rs.getBigDecimal("big_small_limit"), rs.getBigDecimal("fan_limit"),
                        rs.getBigDecimal("add_limit"), rs.getBigDecimal("player_max_stake"),
                        rs.getBigDecimal("player_min_stake"))), accountId);
        return rows.isEmpty() ? MachineBettingConfig.DEFAULT : rows.get(0);
    }

    /**
     * 在下注事务中锁定玩家所属机器的手动开盘状态行。
     * 开关更新也锁定 agent 行，保证关盘提交后不会再有先前读到“开盘”的新注单提交。
     */
    public void lockMachineBettingState(long accountId) {
        jdbcTemplate.query("""
                SELECT ag.id
                  FROM demo_user_account d
                  JOIN agent ag ON ag.id = d.agent_id
                 WHERE d.id = ? AND ag.deleted_at IS NULL
                 FOR UPDATE
                """, rs -> {
            while (rs.next()) {
                rs.getLong(1);
            }
            return null;
        }, accountId);
    }

    /**
     * 按 BY220 语义物理删除未结算注单，作为撤单或未结订单删除的原子占用操作。
     * 钱包退款流水和操作审计另行保留，删除后该注单不再参与结算与报表。
     */
    public boolean deletePendingBetById(long betId) {
        if (betId <= 0) {
            throw new IllegalArgumentException("注单编号无效");
        }
        return jdbcTemplate.update("""
                DELETE FROM game_bet
                 WHERE id = ? AND settlement_status = 'PENDING' AND settled_at IS NULL
                """, betId) == 1;
    }

    public long createBetBatch(long accountId, long machineId, String gameCode, String issueNumber,
                               String idempotencyKey, String requestDigest, BigDecimal totalStake,
                               int configVersion) {
        jdbcTemplate.update("""
                INSERT INTO game_bet_batch
                    (account_id, machine_id, game_code, issue_number, idempotency_key,
                     request_digest, total_stake, machine_config_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, accountId, machineId, gameCode, issueNumber, idempotencyKey,
                requestDigest, totalStake, configVersion);
        Long id = jdbcTemplate.queryForObject("""
                SELECT id FROM game_bet_batch WHERE account_id = ? AND idempotency_key = ?
                """, Long.class, accountId, idempotencyKey);
        if (id == null) throw new IllegalStateException("下注批次保存后未找到主键");
        return id;
    }

    public Optional<BetBatchRecord> findBetBatch(long accountId, String idempotencyKey) {
        return jdbcTemplate.query("""
                SELECT id, account_id, machine_id, game_code, issue_number, request_digest,
                       total_stake, machine_config_version
                  FROM game_bet_batch WHERE account_id = ? AND idempotency_key = ?
                """, (rs, rowNum) -> new BetBatchRecord(rs.getLong("id"), rs.getLong("account_id"),
                rs.getLong("machine_id"), rs.getString("game_code"), rs.getString("issue_number"),
                rs.getString("request_digest"), rs.getBigDecimal("total_stake"),
                rs.getInt("machine_config_version")), accountId, idempotencyKey).stream().findFirst();
    }

    public List<BetRecord> findBetsByBatchId(long batchId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type,
                       parameters_text, stake, odds_snapshot, settlement_status, net_profit, explanation, created_at
                  FROM game_bet WHERE batch_id = ? ORDER BY instruction_no, id
                """, GameDataRepository::mapBetRecord, batchId);
    }

    public BigDecimal findSpecialUnitStake(long betId) {
        return jdbcTemplate.query("""
                SELECT unit_stake FROM game_bet_selection
                 WHERE bet_id = ? ORDER BY selection_no LIMIT 1
                """, rs -> rs.next() ? rs.getBigDecimal(1) : null, betId);
    }

    public long saveBetWithOddsSnapshot(long accountId, String betCode, String idempotencyKey,
                                        String issueNumber, int ballNumber,
                                        PlayType playType, List<Integer> parameters,
                                        BigDecimal stake, BigDecimal odds, BigDecimal rebateRate,
                                        BigDecimal specialRebateRate) {
        return saveBetWithOddsSnapshot(DEFAULT_GAME_CODE, accountId, betCode, idempotencyKey,
                issueNumber, ballNumber, playType, parameters, stake, odds, rebateRate, specialRebateRate);
    }

    public long saveBetWithOddsSnapshot(String gameCode, long accountId, String betCode, String idempotencyKey,
                                        String issueNumber, int ballNumber,
                                        PlayType playType, List<Integer> parameters,
                                        BigDecimal stake, BigDecimal odds, BigDecimal rebateRate,
                                        BigDecimal specialRebateRate) {
        return saveBetWithOddsSnapshot(gameCode, accountId, betCode, idempotencyKey, issueNumber,
                ballNumber, playType, parameters, stake, odds, rebateRate, specialRebateRate,
                0, 0, null, null, null, null, stake, null, null);
    }

    public long saveBetWithOddsSnapshot(String gameCode, long accountId, String betCode, String idempotencyKey,
                                        String issueNumber, int ballNumber,
                                        PlayType playType, List<Integer> parameters,
                                        BigDecimal stake, BigDecimal odds, BigDecimal rebateRate,
                                        BigDecimal specialRebateRate, long machineId, int configVersion,
                                        BigDecimal machineOddsRate, BigDecimal machineSpecialOddsRate,
                                        String quotaBucket, String selectionKey, BigDecimal unitStake) {
        return saveBetWithOddsSnapshot(gameCode, accountId, betCode, idempotencyKey, issueNumber,
                ballNumber, playType, parameters, stake, odds, rebateRate, specialRebateRate,
                machineId, configVersion, machineOddsRate, machineSpecialOddsRate,
                quotaBucket, selectionKey, unitStake, null, null);
    }

    public long saveBetWithOddsSnapshot(String gameCode, long accountId, String betCode, String idempotencyKey,
                                        String issueNumber, int ballNumber, PlayType playType,
                                        List<Integer> parameters, BigDecimal stake, BigDecimal odds,
                                        BigDecimal rebateRate, BigDecimal specialRebateRate, long machineId,
                                        int configVersion, BigDecimal machineOddsRate,
                                        BigDecimal machineSpecialOddsRate, String quotaBucket,
                                        String selectionKey, BigDecimal unitStake, Long batchId,
                                        Integer instructionNo) {
        if (gameCode == null || gameCode.isBlank()) throw new IllegalArgumentException("彩种键名不能为空");
        requireBallNumber(ballNumber);
        String parameterText = parameters == null ? "" : parameters.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        String playerKind = jdbcTemplate.queryForObject(
                "SELECT player_kind FROM demo_user_account WHERE id = ?", String.class, accountId);
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO game_bet
                        (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                         play_type, parameters_text, stake, odds_snapshot, rebate_rate_snapshot,
                         special_rebate_rate_snapshot, machine_id_snapshot,
                         machine_config_version_snapshot, machine_odds_rate_snapshot,
                         special_odds_rate_snapshot, quota_bucket, selection_key, batch_id, instruction_no,
                         player_kind_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setString(1, gameCode);
            statement.setLong(2, accountId);
            statement.setString(3, betCode);
            statement.setString(4, idempotencyKey);
            statement.setString(5, issueNumber);
            statement.setInt(6, ballNumber);
            statement.setString(7, playType.name());
            statement.setString(8, parameterText);
            statement.setBigDecimal(9, stake);
            statement.setBigDecimal(10, odds);
            statement.setBigDecimal(11, rebateRate == null ? BigDecimal.ZERO : rebateRate);
            statement.setBigDecimal(12, specialRebateRate == null ? BigDecimal.ZERO : specialRebateRate);
            statement.setObject(13, machineId > 0 ? machineId : null);
            statement.setInt(14, configVersion);
            statement.setBigDecimal(15, machineOddsRate);
            statement.setBigDecimal(16, machineSpecialOddsRate);
            statement.setString(17, quotaBucket);
            statement.setString(18, selectionKey);
            statement.setObject(19, batchId);
            statement.setObject(20, instructionNo);
            statement.setString(21, playerKind);
            return statement;
        });
        Long id = jdbcTemplate.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class, betCode);
        if (id == null) {
            throw new IllegalStateException("保存注单后未找到主键");
        }
        if (playType == PlayType.SPECIAL && parameters != null && !parameters.isEmpty()) {
            BigDecimal perNumber = unitStake == null ? stake.divide(BigDecimal.valueOf(parameters.size()),
                    2, java.math.RoundingMode.UNNECESSARY) : unitStake;
            for (int index = 0; index < parameters.size(); index++) {
                jdbcTemplate.update("""
                        INSERT INTO game_bet_selection
                            (bet_id, selection_no, selection_key, quota_bucket, number_value, unit_stake)
                        VALUES (?, ?, ?, 'SPECIAL_NUMBER', ?, ?)
                        """, id, index + 1, selectionKey + ":" + parameters.get(index),
                        parameters.get(index), perNumber);
            }
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

    private static void requireBallNumber(int ballNumber) {
        if (ballNumber < 1 || ballNumber > 8) {
            throw new IllegalArgumentException("下注无效：球号必须在 1 到 8 之间");
        }
    }

    public List<BetRecord> findBetsByIssue(String issueNumber) {
        return findBetsByIssue(DEFAULT_GAME_CODE, issueNumber);
    }

    public List<BetRecord> findBetsByIssue(String gameCode, String issueNumber) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, machine_odds_rate_snapshot, settlement_status, net_profit, explanation, created_at
                  FROM game_bet
                 WHERE game_code = ? AND issue_number = ?
                 ORDER BY id
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                 rs.getBigDecimal("net_profit"), rs.getString("explanation"),
                 rs.getTimestamp("created_at").toInstant(), rs.getBigDecimal("machine_odds_rate_snapshot")),
                gameCode, issueNumber);
    }

    public List<BetUsage> findBetUsageByIssue(long accountId, String issueNumber) {
        if (accountId <= 0 || issueNumber == null || issueNumber.isBlank()) {
            throw new IllegalArgumentException("下注额度查询参数无效");
        }
        return jdbcTemplate.query("""
                SELECT play_type, COALESCE(SUM(stake), 0) AS stake
                  FROM game_bet
                 WHERE user_id = ?
                   AND issue_number = ?
                   AND settlement_status <> 'CANCELED'
                 GROUP BY play_type
                """, (rs, rowNum) -> new BetUsage(
                PlayType.valueOf(rs.getString("play_type")),
                rs.getBigDecimal("stake")), accountId, issueNumber.trim());
    }

    public long countUnclassifiedQuotaBets(long accountId, String gameCode, String issueNumber) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM game_bet b
                 WHERE b.user_id = ? AND b.game_code = ? AND b.issue_number = ?
                   AND b.settlement_status <> 'CANCELED'
                   AND COALESCE(b.player_kind_snapshot, 'NORMAL') <> 'BOT'
                   AND (b.machine_id_snapshot IS NULL OR b.quota_bucket IS NULL OR b.selection_key IS NULL
                        OR (b.quota_bucket = 'SPECIAL_NUMBER' AND NOT EXISTS (
                            SELECT 1 FROM game_bet_selection s WHERE s.bet_id = b.id
                        )))
                """, Long.class, accountId, gameCode, issueNumber);
        return count == null ? 0 : count;
    }

    public BigDecimal findIssueStakeTotal(long accountId, long machineId, String gameCode, String issueNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(stake), 0) FROM game_bet
                 WHERE user_id = ? AND machine_id_snapshot = ? AND game_code = ? AND issue_number = ?
                   AND settlement_status <> 'CANCELED'
                   AND COALESCE(player_kind_snapshot, 'NORMAL') <> 'BOT'
                """, BigDecimal.class, accountId, machineId, gameCode, issueNumber);
    }

    public List<QuotaUsage> findQuotaUsageByIssue(long accountId, long machineId, String gameCode, String issueNumber) {
        if (accountId <= 0 || machineId <= 0 || gameCode == null || gameCode.isBlank()
                || issueNumber == null || issueNumber.isBlank()) {
            throw new IllegalArgumentException("下注额度查询参数无效");
        }
        return jdbcTemplate.query("""
                SELECT quota_bucket, selection_key, NULL number_value, SUM(stake) stake
                  FROM game_bet
                 WHERE user_id = ? AND machine_id_snapshot = ? AND game_code = ? AND issue_number = ?
                   AND settlement_status <> 'CANCELED'
                   AND COALESCE(player_kind_snapshot, 'NORMAL') <> 'BOT'
                   AND quota_bucket IS NOT NULL AND quota_bucket <> 'SPECIAL_NUMBER'
                 GROUP BY quota_bucket, selection_key
                UNION ALL
                SELECT 'SPECIAL_NUMBER' quota_bucket, s.selection_key, s.number_value, SUM(s.unit_stake) stake
                  FROM game_bet_selection s
                  JOIN game_bet b ON b.id = s.bet_id
                 WHERE b.user_id = ? AND b.machine_id_snapshot = ? AND b.game_code = ? AND b.issue_number = ?
                   AND b.settlement_status <> 'CANCELED'
                   AND COALESCE(b.player_kind_snapshot, 'NORMAL') <> 'BOT'
                   AND s.quota_bucket = 'SPECIAL_NUMBER'
                 GROUP BY s.selection_key, s.number_value
                """, (rs, rowNum) -> new QuotaUsage(rs.getString("quota_bucket"), rs.getString("selection_key"),
                rs.getObject("number_value", Integer.class), rs.getBigDecimal("stake")),
                accountId, machineId, gameCode, issueNumber.trim(), accountId, machineId, gameCode, issueNumber.trim());
    }

    public List<BetUsage> findBetUsageByIssueExcludingBet(long accountId, String issueNumber, long excludedBetId) {
        if (accountId <= 0 || issueNumber == null || issueNumber.isBlank() || excludedBetId <= 0) {
            throw new IllegalArgumentException("下注额度查询参数无效");
        }
        return jdbcTemplate.query("""
                SELECT play_type, COALESCE(SUM(stake), 0) AS stake
                  FROM game_bet
                 WHERE user_id = ?
                   AND issue_number = ?
                   AND id <> ?
                   AND settlement_status <> 'CANCELED'
                 GROUP BY play_type
                """, (rs, rowNum) -> new BetUsage(
                PlayType.valueOf(rs.getString("play_type")),
                rs.getBigDecimal("stake")), accountId, issueNumber.trim(), excludedBetId);
    }

    public List<BetAuditRecord> findBetAuditByIssue(String issueNumber) {
        return jdbcTemplate.query("""
                SELECT b.id, COALESCE(a.display_name, b.player_name_snapshot) AS display_name,
                       b.play_type, b.parameters_text, b.stake
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.issue_number = ?
                 ORDER BY b.id
                """, (rs, rowNum) -> new BetAuditRecord(
                rs.getLong("id"), rs.getString("display_name"),
                PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake")), issueNumber);
    }

    public List<BetAuditRecord> findBetAuditByIssueAndAgent(String issueNumber, long agentId) {
        if (issueNumber == null || issueNumber.isBlank() || agentId <= 0) return List.of();
        return jdbcTemplate.query("""
                SELECT b.id, COALESCE(a.display_name, b.player_name_snapshot) AS display_name,
                       b.play_type, b.parameters_text, b.stake
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.issue_number = ?
                   AND COALESCE(a.agent_id, b.agent_id_snapshot) = ?
                   AND b.settlement_status <> 'CANCELED'
                 ORDER BY b.id
                """, (rs, rowNum) -> new BetAuditRecord(
                rs.getLong("id"), rs.getString("display_name"),
                PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake")),
                issueNumber.trim(), agentId);
    }

    public List<WinnerBetAuditRecord> findWinningBetAuditByIssueAndAgent(String issueNumber, long agentId) {
        if (issueNumber == null || issueNumber.isBlank() || agentId <= 0) return List.of();
        return jdbcTemplate.query("""
                SELECT b.id, COALESCE(a.display_name, b.player_name_snapshot) AS display_name,
                       b.ball_number, b.play_type, b.parameters_text, b.stake, b.net_profit
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.issue_number = ? AND b.settlement_status = 'WIN'
                   AND COALESCE(a.agent_id, b.agent_id_snapshot) = ?
                 ORDER BY b.id
                """, (rs, rowNum) -> new WinnerBetAuditRecord(rs.getLong("id"),
                rs.getString("display_name"), rs.getInt("ball_number"),
                PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("net_profit")), issueNumber.trim(), agentId);
    }

    public List<BetRecord> findBetsByAccountId(long accountId, SettlementStatus status, int limit) {
        if (accountId <= 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("注单查询参数无效");
        }
        String statusClause = status == null ? "" : " AND settlement_status = ?";
        if (status == null) {
            return jdbcTemplate.query("""
                    SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type,
                            parameters_text, stake, odds_snapshot, settlement_status, net_profit, explanation, created_at
                      FROM game_bet
                     WHERE user_id = ?
                     ORDER BY id DESC
                     LIMIT ?
                    """, betMapper(), accountId, limit);
        }
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type,
                        parameters_text, stake, odds_snapshot, settlement_status, net_profit, explanation, created_at
                  FROM game_bet
                 WHERE user_id = ?
                """ + statusClause + """
                 ORDER BY id DESC
                 LIMIT ?
                """, betMapper(), accountId, status.name(), limit);
    }

    public List<BetRecord> findSettledBetsByAccountIdAndCreatedBetween(long accountId,
                                                                       Instant fromInclusive,
                                                                       Instant toExclusive,
                                                                       int limit) {
        if (accountId <= 0 || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive) || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("已结算注单查询参数无效");
        }
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                       play_type, parameters_text, stake, odds_snapshot, settlement_status,
                       net_profit, explanation, created_at
                  FROM game_bet
                 WHERE user_id = ?
                   AND settlement_status <> 'PENDING'
                   AND created_at >= ?
                   AND created_at < ?
                 ORDER BY id DESC
                 LIMIT ?
                """, betMapper(), accountId, timestamp(fromInclusive), timestamp(toExclusive), limit);
    }

    public BetDayStatistics findBetDayStatistics(long accountId, Instant fromInclusive, Instant toExclusive) {
        if (accountId <= 0 || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("注单日统计查询参数无效");
        }
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(stake), 0) AS turnover,
                       COALESCE(SUM(CASE WHEN settlement_status NOT IN ('PENDING', 'CANCELED')
                                         THEN net_profit ELSE 0 END), 0)
                           AS net_profit
                  FROM game_bet
                 WHERE user_id = ? AND settlement_status <> 'CANCELED'
                   AND created_at >= ? AND created_at < ?
                """, (rs, rowNum) -> new BetDayStatistics(
                rs.getBigDecimal("turnover"), rs.getBigDecimal("net_profit")),
                accountId, timestamp(fromInclusive), timestamp(toExclusive));
    }

    public long countBetIssues(long accountId, Instant fromInclusive, Instant toExclusive) {
        if (accountId <= 0 || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("下注期数查询参数无效");
        }
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT issue_number)
                  FROM game_bet
                 WHERE user_id = ? AND settlement_status <> 'CANCELED'
                   AND created_at >= ? AND created_at < ?
                """, Long.class, accountId, timestamp(fromInclusive), timestamp(toExclusive));
        return count == null ? 0 : count;
    }

    public List<BetRecord> findCancelableBets(long accountId, String issueNumber, Instant createdAfter) {
        if (accountId <= 0 || issueNumber == null || issueNumber.isBlank() || createdAfter == null) {
            throw new IllegalArgumentException("可撤单查询参数无效");
        }
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                       play_type, parameters_text, stake, odds_snapshot, settlement_status,
                       net_profit, explanation, created_at
                  FROM game_bet
                 WHERE user_id = ? AND issue_number = ? AND settlement_status = 'PENDING'
                   AND created_at >= ?
                 ORDER BY id
                """, betMapper(), accountId, issueNumber, timestamp(createdAfter));
    }

    public Optional<BetRecord> findBetByCode(String betCode) {
        return findBetsByCode(betCode).stream().findFirst();
    }

    public Optional<BetRecord> findBetByIdempotencyKey(long accountId, String idempotencyKey) {
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type, parameters_text,
                       stake, odds_snapshot, settlement_status, net_profit, explanation, created_at
                  FROM game_bet
                 WHERE user_id = ? AND request_idempotency_key = ?
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                 rs.getBigDecimal("net_profit"), rs.getString("explanation"),
                 rs.getTimestamp("created_at").toInstant()), accountId, idempotencyKey)
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
                       stake, odds_snapshot, settlement_status, net_profit, explanation, created_at
                  FROM game_bet
                 WHERE bet_code = ?
                """, (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                 rs.getBigDecimal("net_profit"), rs.getString("explanation"),
                 rs.getTimestamp("created_at").toInstant()), betCode);
    }

    private static org.springframework.jdbc.core.RowMapper<BetRecord> betMapper() {
        return (rs, rowNum) -> new BetRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                 rs.getBigDecimal("net_profit"), rs.getString("explanation"),
                 rs.getTimestamp("created_at").toInstant());
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

    private List<IssueRecord> findIssues(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IssueRecord(
                rs.getString("issue_number"), rs.getString("status"), rs.getString("phase"),
                numbers(rs), instant(rs, "issue_started_at"), instant(rs, "betting_ends_at"),
                instant(rs, "draw_ends_at"), instant(rs, "settled_at")), args);
    }

    private List<IssueRecord> findIssues(String sql, int limit) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IssueRecord(
                rs.getString("issue_number"), rs.getString("status"), rs.getString("phase"),
                numbers(rs), instant(rs, "issue_started_at"), instant(rs, "betting_ends_at"),
                instant(rs, "draw_ends_at"), instant(rs, "settled_at")), limit);
    }

    private static BetRecord mapBetRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BetRecord(rs.getLong("id"), rs.getLong("user_id"), rs.getString("bet_code"),
                rs.getString("request_idempotency_key"), rs.getString("issue_number"),
                rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getBigDecimal("net_profit"), rs.getString("explanation"),
                rs.getTimestamp("created_at").toInstant());
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
                            BigDecimal netProfit, String explanation, Instant createdAt,
                            BigDecimal machineOddsRate) {
        public BetRecord(long id, long accountId, String betCode, String requestIdempotencyKey,
                         String issueNumber, int ballNumber, PlayType playType, List<Integer> parameters,
                         BigDecimal stake, BigDecimal odds, SettlementStatus settlementStatus,
                         BigDecimal netProfit, String explanation, Instant createdAt) {
            this(id, accountId, betCode, requestIdempotencyKey, issueNumber, ballNumber, playType, parameters,
                    stake, odds, settlementStatus, netProfit, explanation, createdAt, null);
        }
    }

    public record BetAuditRecord(long id, String displayName, PlayType playType,
                                 List<Integer> parameters, BigDecimal stake) {
    }

    public record WinnerBetAuditRecord(long id, String displayName, int ballNumber, PlayType playType,
                                       List<Integer> parameters, BigDecimal stake, BigDecimal netProfit) {
    }

    public record BetUsage(PlayType playType, BigDecimal stake) {
    }

    public record BetBatchRecord(long id, long accountId, long machineId, String gameCode,
                                 String issueNumber, String requestDigest, BigDecimal totalStake,
                                 int machineConfigVersion) {
    }

    public record QuotaUsage(String quotaBucket, String selectionKey, Integer numberValue, BigDecimal stake) {
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

    public record BettingRates(BigDecimal rebateRate, BigDecimal specialRebateRate) {
        static final BettingRates ZERO = new BettingRates(BigDecimal.ZERO, BigDecimal.ZERO);
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
