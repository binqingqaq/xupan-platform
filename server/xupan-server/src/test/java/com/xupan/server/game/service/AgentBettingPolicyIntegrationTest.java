package com.xupan.server.game.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BY220 机器赔率、封盘、取消与撤单物理删除的集成验证（H2/Flyway）。
 *
 * <p>对应 BY220 {@code x_robot} 的 {@code PeiLv/tePeilv/fengpan/cancel/isOpen}，
 * 以及 {@code common.php:2067-2106} 的撤单物理删除行为。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "spring.task.scheduling.enabled=false"})
class AgentBettingPolicyIntegrationTest {

    private static final String USERNAME = "agent-odds-integration";
    private static final String DISPLAY_NAME = "机器赔率测试玩家";
    private static final String PASSWORD = "AgentOdds12345";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordPolicyService passwordPolicy;
    @Autowired
    private VirtualWalletService walletService;
    @Autowired
    private DemoGameService gameService;

    private long userId;
    private long accountId;
    private long machineId;

    @BeforeEach
    void setUp() {
        clean();
        userId = userRepository.insert(USERNAME, DISPLAY_NAME, passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "USER");
        accountId = walletService.ensureWalletForUser(userId, DISPLAY_NAME);
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status, bot_count)
                VALUES ('MACHINE-ODDS-TEST', '机器赔率测试机器', ?, FALSE, 'ACTIVE', 0)
                """, userId);
        machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = 'MACHINE-ODDS-TEST'", Long.class);
        jdbc.update("UPDATE demo_user_account SET agent_id = ? WHERE id = ?", machineId, accountId);
        walletService.grant(userId, userId, new BigDecimal("10000.00"), "机器赔率测试上分", "AGENT-ODDS-GRANT");
        applyGenerousBettingLimits();
        jdbc.update("DELETE FROM game_bet");
        jdbc.update("DELETE FROM game_issue_event");
        jdbc.update("DELETE FROM game_issue");
    }

    @AfterEach
    void tearDown() {
        clean();
        restoreDefaultBettingLimits();
    }

    /** 其他测试类会改写全局限额，本类先固定为宽松值，避免顺序依赖导致的偶发失败。 */
    private void applyGenerousBettingLimits() {
        jdbc.update("""
                UPDATE game_betting_config
                   SET special_limit = 20000, issue_total_limit = 20000, positive_limit = 20000,
                       angle_limit = 20000, strict_limit = 20000, tong_limit = 20000, car_limit = 20000,
                       odd_even_limit = 20000, big_small_limit = 20000, fan_limit = 20000, add_limit = 20000,
                       player_max_stake = 5001, player_min_stake = 1
                 WHERE id = 1
                """);
    }

    private void restoreDefaultBettingLimits() {
        jdbc.update("""
                UPDATE game_betting_config
                   SET special_limit = 200, issue_total_limit = 5000, positive_limit = 20000, angle_limit = 1000,
                       strict_limit = 20000, tong_limit = 20000, car_limit = 20000,
                       odd_even_limit = 20000, big_small_limit = 20000, fan_limit = 20000, add_limit = 20000,
                       player_max_stake = 5001, player_min_stake = 1
                 WHERE id = 1
                """);
    }

    @Test
    void machineOddsRateIsConvertedByPlayWeightAndSnapshotted() {
        configureMachine(95, 18, 0, 10, true);

        BigDecimal fanOdds = placeAndReadOdds(PlayType.FAN, List.of(3), new BigDecimal("100.00"), "fan-weight");
        BigDecimal strictOdds = placeAndReadOdds(PlayType.STRICT, List.of(1, 2), new BigDecimal("100.00"), "strict-weight");
        BigDecimal carOdds = placeAndReadOdds(PlayType.CAR, List.of(1, 2, 3), new BigDecimal("100.00"), "car-weight");
        BigDecimal tongOdds = placeAndReadOdds(PlayType.TONG, List.of(1, 2, 3), new BigDecimal("100.00"), "tong-weight");
        BigDecimal smallOdds = placeAndReadOdds(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "size-weight");
        BigDecimal specialOdds = placeAndReadOdds(PlayType.SPECIAL, List.of(7), new BigDecimal("100.00"), "special-odds");

        // BY220 getPeilv：1 + num × PeiLv/100；特码直接使用 tePeilv。
        assertThat(fanOdds).isEqualByComparingTo("3.850");
        assertThat(strictOdds).isEqualByComparingTo("2.900");
        assertThat(carOdds).isEqualByComparingTo("1.317");
        assertThat(tongOdds).isEqualByComparingTo("1.475");
        assertThat(smallOdds).isEqualByComparingTo("1.950");
        assertThat(specialOdds).isEqualByComparingTo("18.000");
    }

    @Test
    void unconfiguredMachineFallsBackToGlobalOdds() {
        configureMachine(0, 0, 0, 10, true);

        BigDecimal fanOdds = placeAndReadOdds(PlayType.FAN, List.of(3), new BigDecimal("100.00"), "fan-fallback");
        BigDecimal smallOdds = placeAndReadOdds(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "size-fallback");

        assertThat(fanOdds).isEqualByComparingTo("3.850");
        assertThat(smallOdds).isEqualByComparingTo("1.950");
    }

    @Test
    void closedBoardRejectsBetting() {
        configureMachine(95, 18, 0, 10, false);

        assertThatThrownBy(() -> placeBet(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "board-closed"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(AgentBettingPolicy.BOARD_CLOSED));
    }

    @Test
    void closeSecondsRejectsBettingInsideSealWindow() {
        // 每期 300 秒、封盘窗口设为 400 秒，当前期必然已经封盘。
        configureMachine(95, 18, 400, 10, true);

        assertThatThrownBy(() -> placeBet(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "sealed"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(AgentBettingPolicy.MACHINE_CLOSED));
    }

    @Test
    void cancellationPhysicallyDeletesBetAndRefundsOnce() {
        configureMachine(95, 18, 0, 10, true);
        placeBet(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "cancel-target");
        Long betId = jdbc.queryForObject("SELECT MAX(id) FROM game_bet WHERE user_id = ?", Long.class, accountId);
        assertThat(betId).isNotNull();

        DemoGameService.CancellationResult first = gameService.cancelCurrentBets(userId, Instant.now());
        assertThat(first.status()).isEqualTo(DemoGameService.CancellationResult.Status.CANCELED);
        assertThat(first.windowSeconds()).isEqualTo(10);
        assertThat(first.items()).hasSize(1);

        // BY220：撤单物理删除注单行，钱包退款流水作为金额事实保留。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE id = ?", Long.class, betId)).isZero();
        assertThat(refundLedgerCount(betId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("10000.00");

        DemoGameService.CancellationResult second = gameService.cancelCurrentBets(userId, Instant.now());
        assertThat(second.status()).isEqualTo(DemoGameService.CancellationResult.Status.NO_ELIGIBLE_BETS);
        assertThat(refundLedgerCount(betId)).isEqualTo(1);
    }

    /** 只统计撤单退款流水；下注本身也会写一条 BET_DEBIT 流水并关联同一注单号。 */
    private long refundLedgerCount(long betId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE related_bet_id = ? AND operation_type = 'SETTLEMENT_REVERSAL'
                """, Long.class, betId);
        return count == null ? 0 : count;
    }

    @Test
    void cancelSecondsZeroDisablesCancellation() {
        configureMachine(95, 18, 0, 0, true);
        placeBet(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "cancel-disabled");

        DemoGameService.CancellationResult result = gameService.cancelCurrentBets(userId, Instant.now());

        assertThat(result.status()).isEqualTo(DemoGameService.CancellationResult.Status.CANCELLATION_DISABLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(1);
    }

    private BigDecimal placeAndReadOdds(PlayType playType, List<Integer> parameters, BigDecimal stake, String key) {
        placeBet(playType, parameters, stake, key);
        return jdbc.queryForObject("""
                SELECT odds_snapshot FROM game_bet
                 WHERE user_id = ? AND request_idempotency_key = ?
                """, BigDecimal.class, accountId, key);
    }

    private void placeBet(PlayType playType, List<Integer> parameters, BigDecimal stake, String key) {
        gameService.placeBetForUser(userId, new PlaceBetRequest(1, playType, parameters, stake, key));
    }

    private void configureMachine(int oddsRate, int specialOddsRate, int closeSeconds, int cancelSeconds,
                                  boolean boardOpen) {
        jdbc.update("""
                UPDATE agent
                   SET odds_rate = ?, special_odds_rate = ?, close_seconds = ?, cancel_seconds = ?,
                       board_open = ?, rebate_rate = 5, special_rebate_rate = 1, rebate_mode = 'SINGLE'
                 WHERE id = ?
                """, new BigDecimal(oddsRate), new BigDecimal(specialOddsRate), closeSeconds, cancelSeconds,
                boardOpen, machineId);
    }

    private void clean() {
        jdbc.update("DELETE FROM auth_ws_ticket");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM demo_balance_ledger");
        jdbc.update("DELETE FROM game_bet");
        jdbc.update("DELETE FROM game_issue_event");
        jdbc.update("DELETE FROM game_issue");
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM agent WHERE agent_code = 'MACHINE-ODDS-TEST'");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
    }
}
