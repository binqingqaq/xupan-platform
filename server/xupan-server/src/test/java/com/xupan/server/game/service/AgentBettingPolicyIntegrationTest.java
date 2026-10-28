package com.xupan.server.game.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.platformadmin.service.PlatformReportService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BY220 机器赔率、封盘、取消与撤单物理删除的集成验证（H2/MySQL/Flyway）。
 *
 * <p>对应 BY220 {@code x_robot} 的 {@code PeiLv/tePeilv/fengpan/cancel/isOpen}，
 * 以及 {@code common.php:2067-2106} 的撤单物理删除行为。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "spring.task.scheduling.enabled=false"})
class AgentBettingPolicyIntegrationTest {

    private static final String USERNAME = "agent-odds-integration";
    private static final String PEER_USERNAME = "agent-odds-peer-integration";
    private static final String SAME_MACHINE_USERNAME = "agent-odds-same-machine-player";
    private static final String OTHER_MACHINE_USERNAME = "agent-odds-other-machine-player";
    private static final String DISPLAY_NAME = "机器赔率测试玩家";
    private static final String PASSWORD = "AgentOdds12345";
    private static final String OTHER_GAME_CODE = "ODDS_TEST_GAME";

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
    @Autowired
    private PlatformReportService platformReportService;

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
        assertThat(carOdds).isEqualByComparingTo("1.316");
        assertThat(tongOdds).isEqualByComparingTo("1.475");
        assertThat(smallOdds).isEqualByComparingTo("1.950");
        assertThat(specialOdds).isEqualByComparingTo("18.000");
    }

    @Test
    void machineOddsAreIsolatedAndConfigurationChangesKeepPreviousBetSnapshots() {
        configureMachine(95, 18, 0, 10, true);
        long peerUserId = userRepository.insert(PEER_USERNAME, "机器乙赔率测试玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(peerUserId, "USER");
        long peerAccountId = walletService.ensureWalletForUser(peerUserId, "机器乙赔率测试玩家");
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status, bot_count)
                VALUES ('MACHINE-ODDS-TEST-B', '机器乙赔率测试机器', ?, FALSE, 'ACTIVE', 0)
                """, peerUserId);
        long peerMachineId = jdbc.queryForObject(
                "SELECT id FROM agent WHERE agent_code = 'MACHINE-ODDS-TEST-B'", Long.class);
        jdbc.update("UPDATE demo_user_account SET agent_id = ? WHERE id = ?", peerMachineId, peerAccountId);
        jdbc.update("""
                UPDATE agent
                   SET odds_rate = 90, special_odds_rate = 17, board_open = TRUE,
                       total_limit = 20000, positive_limit = 20000, angle_limit = 20000,
                       strict_limit = 20000, tong_limit = 20000, car_limit = 20000,
                       special_limit = 20000, odd_even_limit = 20000, big_small_limit = 20000,
                       fan_limit = 20000, add_limit = 20000, player_min_stake = 1, player_max_stake = 5001
                 WHERE id = ?
                """, peerMachineId);
        walletService.grant(peerUserId, peerUserId, new BigDecimal("10000.00"),
                "机器乙赔率测试上分", "AGENT-ODDS-PEER-GRANT");

        DemoGameService.GameView machineAView = gameService.current(userId);
        DemoGameService.GameView machineBView = gameService.current(peerUserId);
        assertThat(machineAView.displayOdds()).filteredOn(item -> item.ruleKey().equals("FAN"))
                .extracting(DemoGameService.DisplayOddsView::displayValue).containsExactly("3.850");
        assertThat(machineBView.displayOdds()).filteredOn(item -> item.ruleKey().equals("FAN"))
                .extracting(DemoGameService.DisplayOddsView::displayValue).containsExactly("3.700");

        placeBet(PlayType.FAN, List.of(3), new BigDecimal("10.00"), "machine-a-before-change");
        gameService.placeBetForUser(peerUserId, new PlaceBetRequest(1, PlayType.FAN, List.of(3),
                new BigDecimal("10.00"), "machine-b-snapshot"));
        jdbc.update("UPDATE agent SET odds_rate = 90, betting_config_version = betting_config_version + 1 WHERE id = ?",
                machineId);
        placeBet(PlayType.FAN, List.of(3), new BigDecimal("10.00"), "machine-a-after-change");

        assertThat(snapshotOdds(accountId, "SINGLE-machine-a-before-change")).isEqualByComparingTo("3.850");
        assertThat(snapshotOdds(accountId, "SINGLE-machine-a-after-change")).isEqualByComparingTo("3.700");
        assertThat(snapshotOdds(peerAccountId, "SINGLE-machine-b-snapshot")).isEqualByComparingTo("3.700");
    }

    @Test
    void specialPayoutAndReportUseOrderSnapshotsAfterMachineConfigChanges() {
        configureMachine(95, 18, 0, 10, true);
        gameService.current(userId);
        boolean previousSpecialEnabled = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT special_enabled FROM game_definition WHERE game_code = ?",
                Boolean.class, GameDataRepository.DEFAULT_GAME_CODE));
        jdbc.update("UPDATE game_definition SET special_enabled = TRUE WHERE game_code = ?",
                GameDataRepository.DEFAULT_GAME_CODE);
        jdbc.update("UPDATE demo_user_account SET rebate_rate = 5 WHERE id = ?", accountId);
        userRepository.assignRole(userId, "ADMIN");

        try {
            jdbc.update("UPDATE agent SET special_rebate_rate = 0.01 WHERE id = ?", machineId);
            placeBet(PlayType.SPECIAL, List.of(12), new BigDecimal("100.00"), "special-e2e-snapshot");
            jdbc.update("UPDATE agent SET special_odds_rate = 5, special_rebate_rate = 0.50 WHERE id = ?", machineId);

            gameService.draw(userId, List.of(12, 1, 2, 3, 4, 5, 6, 7));

            assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                    BigDecimal.class, accountId)).isEqualByComparingTo("11700.00");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM demo_balance_ledger
                     WHERE user_id = ? AND operation_type = 'SETTLEMENT_CREDIT'
                       AND amount = 1800.00
                    """, Integer.class, accountId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT CONCAT(b.odds_snapshot, ':', b.rebate_rate_snapshot, ':',
                                  b.special_rebate_rate_snapshot, ':', b.settlement_status)
                      FROM game_bet b JOIN game_bet_batch batch ON batch.id = b.batch_id
                     WHERE batch.idempotency_key = ?
                    """, String.class, "SINGLE-special-e2e-snapshot")).isEqualTo("18.0000:5.0000:0.0100:WIN");

            PlatformReportService.ProfitReport report = platformReportService.profitReport(
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(), null, null, userId);
            PlatformReportService.MachineProfitRow machine = report.machines().stream()
                    .filter(row -> row.machineId() == machineId).findFirst().orElseThrow();
            assertThat(machine.totalSingleFlow()).isEqualByComparingTo("1800.00");
            assertThat(machine.totalFanShui()).isEqualByComparingTo("1.00");
            assertThat(machine.rebateDouble()).isEqualByComparingTo("90.00");
            assertThat(machine.effectiveRebate()).isEqualByComparingTo("1.00");
        } finally {
            jdbc.update("UPDATE game_definition SET special_enabled = ? WHERE game_code = ?",
                    previousSpecialEnabled, GameDataRepository.DEFAULT_GAME_CODE);
        }
    }

    @Test
    void carBetUsesExactOneThirdSettlementAndOrderOddsSnapshot() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE demo_user_account SET rebate_rate = 0 WHERE id = ?", accountId);
        userRepository.assignRole(userId, "ADMIN");
        gameService.current(userId);

        placeBet(PlayType.CAR, List.of(1, 2, 3), new BigDecimal("100.00"), "car-e2e-exact-third");
        jdbc.update("UPDATE agent SET odds_rate = 20 WHERE id = ?", machineId);
        gameService.draw(userId, List.of(18, 1, 2, 3, 4, 5, 6, 7));

        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("10031.66");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(b.odds_snapshot, ':', b.settlement_status, ':', b.net_profit)
                  FROM game_bet b JOIN game_bet_batch batch ON batch.id = b.batch_id
                 WHERE batch.idempotency_key = ?
                """, String.class, "SINGLE-car-e2e-exact-third")).isEqualTo("1.3160:WIN:31.66");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger
                 WHERE user_id = ? AND operation_type = 'SETTLEMENT_CREDIT' AND amount = 131.66
                """, Integer.class, accountId)).isEqualTo(1);
    }

    @Test
    void losingSpecialBetHasNoPayoutOrSingleRebate() {
        configureMachine(95, 18, 0, 10, true);
        boolean previousSpecialEnabled = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT special_enabled FROM game_definition WHERE game_code = ?",
                Boolean.class, GameDataRepository.DEFAULT_GAME_CODE));
        jdbc.update("UPDATE game_definition SET special_enabled = TRUE WHERE game_code = ?",
                GameDataRepository.DEFAULT_GAME_CODE);
        jdbc.update("UPDATE agent SET special_rebate_rate = 0.01 WHERE id = ?", machineId);
        jdbc.update("UPDATE demo_user_account SET rebate_rate = 5 WHERE id = ?", accountId);
        userRepository.assignRole(userId, "ADMIN");
        gameService.current(userId);

        try {
            placeBet(PlayType.SPECIAL, List.of(12), new BigDecimal("100.00"), "special-miss-no-rebate");
            gameService.draw(userId, List.of(11, 1, 2, 3, 4, 5, 6, 7));

            assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                    BigDecimal.class, accountId)).isEqualByComparingTo("9900.00");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM demo_balance_ledger
                     WHERE user_id = ? AND operation_type = 'SETTLEMENT_CREDIT'
                    """, Integer.class, accountId)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT CONCAT(b.odds_snapshot, ':', b.special_rebate_rate_snapshot, ':', b.settlement_status)
                      FROM game_bet b JOIN game_bet_batch batch ON batch.id = b.batch_id
                     WHERE batch.idempotency_key = ?
                    """, String.class, "SINGLE-special-miss-no-rebate")).isEqualTo("18.0000:0.0100:LOSE");

            PlatformReportService.MachineProfitRow machine = platformReportService.profitReport(
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(), null, null, userId)
                    .machines().stream().filter(row -> row.machineId() == machineId).findFirst().orElseThrow();
            assertThat(machine.totalSingleFlow()).isEqualByComparingTo("0.00");
            assertThat(machine.totalDoubleFlow()).isEqualByComparingTo("100.00");
            assertThat(machine.totalFanShui()).isEqualByComparingTo("0.00");
            assertThat(machine.effectiveRebate()).isEqualByComparingTo("0.00");
        } finally {
            jdbc.update("UPDATE game_definition SET special_enabled = ? WHERE game_code = ?",
                    previousSpecialEnabled, GameDataRepository.DEFAULT_GAME_CODE);
        }
    }

    @Test
    void twoNumberSpecialBetPaysAndReportsOneWinningUnitFromOrderSnapshot() {
        configureMachine(95, 18, 0, 10, true);
        boolean previousSpecialEnabled = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT special_enabled FROM game_definition WHERE game_code = ?",
                Boolean.class, GameDataRepository.DEFAULT_GAME_CODE));
        jdbc.update("UPDATE game_definition SET special_enabled = TRUE WHERE game_code = ?",
                GameDataRepository.DEFAULT_GAME_CODE);
        jdbc.update("UPDATE agent SET special_rebate_rate = 0.01 WHERE id = ?", machineId);
        jdbc.update("UPDATE demo_user_account SET rebate_rate = 5 WHERE id = ?", accountId);
        userRepository.assignRole(userId, "ADMIN");
        gameService.current(userId);

        try {
            placeBet(PlayType.SPECIAL, List.of(12, 18), new BigDecimal("200.00"), "special-combo-one-win");
            jdbc.update("UPDATE agent SET special_odds_rate = 5, special_rebate_rate = 0.50 WHERE id = ?", machineId);
            gameService.draw(userId, List.of(12, 1, 2, 3, 4, 5, 6, 7));

            assertThat(jdbc.queryForObject("SELECT total_stake FROM game_bet_batch WHERE idempotency_key = ?",
                    BigDecimal.class, "SINGLE-special-combo-one-win")).isEqualByComparingTo("200.00");
            assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                    BigDecimal.class, accountId)).isEqualByComparingTo("11600.00");
            assertThat(jdbc.queryForObject("""
                    SELECT SUM(amount) FROM demo_balance_ledger
                     WHERE user_id = ? AND operation_type IN ('BET_DEBIT', 'SETTLEMENT_CREDIT')
                    """, BigDecimal.class, accountId)).isEqualByComparingTo("1600.00");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM demo_balance_ledger
                     WHERE user_id = ? AND operation_type = 'SETTLEMENT_CREDIT' AND amount = 1800.00
                    """, Integer.class, accountId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT CONCAT(b.odds_snapshot, ':', b.rebate_rate_snapshot, ':',
                                  b.special_rebate_rate_snapshot, ':', b.settlement_status)
                      FROM game_bet b JOIN game_bet_batch batch ON batch.id = b.batch_id
                     WHERE batch.idempotency_key = ?
                    """, String.class, "SINGLE-special-combo-one-win")).isEqualTo("18.0000:5.0000:0.0100:WIN");
            assertThat(jdbc.queryForObject("""
                    SELECT SUM(unit_stake) FROM game_bet_selection s
                      JOIN game_bet b ON b.id = s.bet_id
                      JOIN game_bet_batch batch ON batch.id = b.batch_id
                     WHERE batch.idempotency_key = ?
                    """, BigDecimal.class, "SINGLE-special-combo-one-win")).isEqualByComparingTo("200.00");

            PlatformReportService.MachineProfitRow machine = platformReportService.profitReport(
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(), null, null, userId)
                    .machines().stream().filter(row -> row.machineId() == machineId).findFirst().orElseThrow();
            assertThat(machine.totalSingleFlow()).isEqualByComparingTo("1800.00");
            assertThat(machine.totalProfit()).isEqualByComparingTo("1600.00");
            assertThat(machine.totalFanShui()).isEqualByComparingTo("1.00");
            assertThat(machine.rebateDouble()).isEqualByComparingTo("90.00");
        } finally {
            jdbc.update("UPDATE game_definition SET special_enabled = ? WHERE game_code = ?",
                    previousSpecialEnabled, GameDataRepository.DEFAULT_GAME_CODE);
        }
    }

    @Test
    void currentGameViewContainsMachineDisplayQuotesByRuleKey() {
        configureMachine(95, 18, 0, 10, true);

        DemoGameService.GameView view = gameService.current(userId);

        assertThat(view.displayOdds()).extracting(DemoGameService.DisplayOddsView::ruleKey)
                .containsExactly("BASE", "FAN", "STRICT", "CAR", "NONE_PAIR", "NONE_TRIPLE", "TONG", "SPECIAL");
        assertThat(view.displayOdds()).filteredOn(item -> item.ruleKey().equals("CAR"))
                .extracting(DemoGameService.DisplayOddsView::displayValue).containsExactly("1.316");
        assertThat(view.displayOdds()).filteredOn(item -> item.ruleKey().equals("NONE_PAIR"))
                .extracting(DemoGameService.DisplayOddsView::displayValue).containsExactly("1.950");
        assertThat(view.displayOdds()).filteredOn(item -> item.ruleKey().equals("NONE_TRIPLE"))
                .extracting(DemoGameService.DisplayOddsView::displayValue).containsExactly("1.475");
        assertThat(view.displayOdds()).filteredOn(item -> item.ruleKey().equals("SPECIAL"))
                .extracting(DemoGameService.DisplayOddsView::displayValue).containsExactly("18.0000");
        assertThat(view.configVersion()).isPositive();
    }

    @Test
    void specialBetIsRejectedWhenTheGameDisablesSpecialNumbers() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE game_definition SET special_enabled = FALSE WHERE game_code = ?",
                GameDataRepository.DEFAULT_GAME_CODE);
        try {
            assertThat(gameService.current(userId).specialEnabled()).isFalse();
            assertThatThrownBy(() -> placeBet(PlayType.SPECIAL, List.of(1, 2),
                    new BigDecimal("20.00"), "special-disabled"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.code()).isEqualTo("GAME_SPECIAL_DISABLED"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet_batch WHERE account_id = ?",
                    Long.class, accountId)).isZero();
            assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                    BigDecimal.class, accountId)).isEqualByComparingTo("10000.00");
        } finally {
            jdbc.update("UPDATE game_definition SET special_enabled = TRUE WHERE game_code = ?",
                    GameDataRepository.DEFAULT_GAME_CODE);
        }
    }

    @Test
    void unconfiguredMachineDoesNotFallBackToGlobalOdds() {
        configureMachine(0, 0, 0, 10, true);

        assertThatThrownBy(() -> placeBet(PlayType.FAN, List.of(3), new BigDecimal("100.00"), "fan-no-fallback"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MACHINE_ODDS_UNAVAILABLE"));
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
    void playerStakeMinimumAndMaximumAcceptExactEdgesAndRejectOutside() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE agent SET player_min_stake = 10, player_max_stake = 200 WHERE id = ?", machineId);

        assertThatThrownBy(() -> placeBet(PlayType.FAN, List.of(1), new BigDecimal("9.00"), "stake-below-min"))
                .isInstanceOf(BetLimitExceededException.class).hasMessageContaining("玩家最小注额10");
        placeBet(PlayType.FAN, List.of(1), new BigDecimal("10.00"), "stake-at-min");
        placeBet(PlayType.FAN, List.of(2), new BigDecimal("200.00"), "stake-at-max");
        assertThatThrownBy(() -> placeBet(PlayType.FAN, List.of(3), new BigDecimal("201.00"), "stake-above-max"))
                .isInstanceOf(BetLimitExceededException.class).hasMessageContaining("玩家最高注额200");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("9790.00");
    }

    @Test
    void loweringPlayerMaximumDoesNotRejectCurrentStakeBecauseHistoricalTotalIsHigher() {
        configureMachine(95, 18, 0, 10, true);
        placeBet(PlayType.FAN, List.of(1), new BigDecimal("500.00"), "history-over-new-max");
        jdbc.update("UPDATE agent SET player_max_stake = 200 WHERE id = ?", machineId);

        placeBet(PlayType.FAN, List.of(1), new BigDecimal("100.00"), "current-under-new-max");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(stake) FROM game_bet WHERE user_id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("600.00");
    }

    @Test
    void differentAliasesWithSameQuotaSelectionKeySharePersistedUsage() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE agent SET positive_limit = 300 WHERE id = ?", machineId);
        List<Integer> positiveAlias = List.of(1);
        List<Integer> noneAlias = List.of(1, 3);
        assertThat(com.xupan.server.game.domain.BetQuotaBucket.ruleKey(PlayType.POSITIVE, positiveAlias))
                .isEqualTo(com.xupan.server.game.domain.BetQuotaBucket.ruleKey(PlayType.NONE, noneAlias));
        placeBet(PlayType.POSITIVE, positiveAlias, new BigDecimal("250.00"), "positive-alias-first");

        assertThatThrownBy(() -> placeBet(PlayType.NONE, noneAlias, new BigDecimal("60.00"), "none-alias-over-limit"))
                .isInstanceOf(BetLimitExceededException.class).hasMessageContaining("正限额300");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("9750.00");
    }

    @Test
    void quotaUsageIsIsolatedByPlayerAndMachineEvenWhenDisplayNamesMatch() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE agent SET fan_limit = 100 WHERE id = ?", machineId);
        long sameMachineAccountId = createPlayerAccount(SAME_MACHINE_USERNAME, "同机另一个玩家", machineId,
                "SAME-MACHINE-QUOTA-GRANT");
        long otherMachineUserId = userRepository.insert(OTHER_MACHINE_USERNAME, DISPLAY_NAME,
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(otherMachineUserId, "USER");
        long otherMachineAccountId = walletService.ensureWalletForUser(otherMachineUserId, DISPLAY_NAME);
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status, bot_count)
                VALUES ('MACHINE-ODDS-TEST-C', '机器丙赔率测试机器', ?, FALSE, 'ACTIVE', 0)
                """, otherMachineUserId);
        long otherMachineId = jdbc.queryForObject(
                "SELECT id FROM agent WHERE agent_code = 'MACHINE-ODDS-TEST-C'", Long.class);
        jdbc.update("UPDATE demo_user_account SET agent_id = ? WHERE id = ?", otherMachineId, otherMachineAccountId);
        configurePeerMachine(otherMachineId, 95, 100);
        walletService.grant(otherMachineUserId, otherMachineUserId, new BigDecimal("10000.00"),
                "同名异机测试上分", "OTHER-MACHINE-QUOTA-GRANT");

        placeBet(PlayType.FAN, List.of(1), new BigDecimal("100.00"), "quota-player-a");
        gameService.placeBetForUser(userRepository.findByUsername(SAME_MACHINE_USERNAME).orElseThrow().id(),
                new PlaceBetRequest(1, PlayType.FAN, List.of(1), new BigDecimal("100.00"), "quota-player-b"));
        gameService.placeBetForUser(otherMachineUserId,
                new PlaceBetRequest(1, PlayType.FAN, List.of(1), new BigDecimal("100.00"), "quota-other-machine"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id IN (?, ?, ?)", Long.class,
                accountId, sameMachineAccountId, otherMachineAccountId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(stake) FROM game_bet WHERE user_id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("100.00");
    }

    @Test
    void quotaUsageSharesAcrossBallsButIsSeparateAcrossGames() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE agent SET fan_limit = 100, total_limit = 1000 WHERE id = ?", machineId);
        jdbc.update("""
                INSERT INTO game_definition
                    (id, game_code, display_name, ball_indexes, algorithm, switch_enabled,
                     special_enabled, special_model, keyboard_enabled, status)
                VALUES (999, ?, '额度维度测试游戏', '1,2,3,4,5,6,7,8', 'SUM', TRUE,
                        TRUE, 'MODEL_ONE', TRUE, 'ACTIVE')
                """, OTHER_GAME_CODE);
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, opened_at, issue_started_at,
                     betting_ends_at, draw_ends_at)
                VALUES (?, 'ODDS-3000000', 'OPEN', 'BETTING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 180 SECOND),
                        DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 300 SECOND))
                """, OTHER_GAME_CODE);

        long botUserId = userRepository.insert("agent-odds-quota-bot", "额度维度托玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(botUserId, "USER");
        long botAccountId = walletService.ensureWalletForUser(botUserId, "额度维度托玩家");
        jdbc.update("UPDATE demo_user_account SET agent_id = ?, player_kind = 'BOT' WHERE id = ?",
                machineId, botAccountId);
        walletService.grant(botUserId, botUserId, new BigDecimal("10000.00"),
                "额度维度托测试上分", "QUOTA-DIMENSION-BOT-GRANT");
        gameService.placeBetForUser(botUserId,
                new PlaceBetRequest(1, PlayType.FAN, List.of(1), new BigDecimal("100.00"), "quota-bot-first"));
        gameService.placeBetForUser(botUserId,
                new PlaceBetRequest(2, PlayType.FAN, List.of(1), new BigDecimal("100.00"), "quota-bot-second"));

        placeBet(PlayType.FAN, List.of(1), new BigDecimal("100.00"), "quota-ball-one");
        assertThatThrownBy(() -> gameService.placeBetForUser(userId,
                new PlaceBetRequest(2, PlayType.FAN, List.of(1), new BigDecimal("1.00"), "quota-ball-two")))
                .isInstanceOf(BetLimitExceededException.class).hasMessageContaining("番限额100");
        gameService.placeBetForUser(userId,
                new PlaceBetRequest(1, PlayType.FAN, List.of(1), new BigDecimal("100.00"), "quota-other-game",
                        OTHER_GAME_CODE));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT game_code) FROM game_bet WHERE user_id = ?",
                Long.class, accountId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, botAccountId))
                .isEqualTo(2);
    }

    @Test
    void machineQuotaAppliesWhenGlobalLimitIsLowerAndGlobalAppliesWhenMachineIsLower() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE game_betting_config SET fan_limit = 100 WHERE id = 1");
        jdbc.update("UPDATE agent SET total_limit = 500, fan_limit = 500 WHERE id = ?", machineId);

        placeBet(PlayType.FAN, List.of(1), new BigDecimal("200.00"), "machine-limit-above-global");

        long otherMachineUserId = userRepository.insert(OTHER_MACHINE_USERNAME, "机器乙同名额度玩家",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(otherMachineUserId, "USER");
        long otherMachineAccountId = walletService.ensureWalletForUser(otherMachineUserId, "机器乙同名额度玩家");
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status, bot_count)
                VALUES ('MACHINE-ODDS-TEST-C', '机器乙额度测试机器', ?, FALSE, 'ACTIVE', 0)
                """, otherMachineUserId);
        long otherMachineId = jdbc.queryForObject(
                "SELECT id FROM agent WHERE agent_code = 'MACHINE-ODDS-TEST-C'", Long.class);
        jdbc.update("UPDATE demo_user_account SET agent_id = ? WHERE id = ?", otherMachineId, otherMachineAccountId);
        configurePeerMachine(otherMachineId, 95, 100);
        walletService.grant(otherMachineUserId, otherMachineUserId, new BigDecimal("10000.00"),
                "机器乙额度测试上分", "OTHER-MACHINE-LIMIT-GRANT");
        jdbc.update("UPDATE game_betting_config SET fan_limit = 1000 WHERE id = 1");

        assertThatThrownBy(() -> gameService.placeBetForUser(otherMachineUserId,
                new PlaceBetRequest(1, PlayType.FAN, List.of(1), new BigDecimal("150.00"), "machine-limit-below-global")))
                .isInstanceOf(BetLimitExceededException.class).hasMessageContaining("番限额100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id IN (?, ?)", Long.class,
                accountId, otherMachineAccountId)).isEqualTo(1);
    }

    @Test
    void combinedSpecialBetIsRejectedAtomicallyWhenOneNumberExceedsItsQuota() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE agent SET special_limit = 300 WHERE id = ?", machineId);
        placeBet(PlayType.SPECIAL, List.of(1), new BigDecimal("250.00"), "special-number-one-near-limit");
        BigDecimal balanceBefore = jdbc.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", BigDecimal.class, accountId);

        assertThatThrownBy(() -> placeBet(PlayType.SPECIAL, List.of(1, 2), new BigDecimal("200.00"),
                "special-combination-over-number-limit"))
                .isInstanceOf(BetLimitExceededException.class).hasMessageContaining("1特限额300");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo(balanceBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet_batch WHERE account_id = ?",
                Long.class, accountId)).isEqualTo(1);
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

    @Test
    void cancellationReleasesSelectionQuotaForAnotherBet() {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE agent SET big_small_limit = 100 WHERE id = ?", machineId);
        placeBet(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "cancel-release-quota");

        DemoGameService.CancellationResult result = gameService.cancelCurrentBets(userId, Instant.now());

        assertThat(result.status()).isEqualTo(DemoGameService.CancellationResult.Status.CANCELED);
        placeBet(PlayType.BIG_SMALL, List.of(2), new BigDecimal("100.00"), "after-cancel-reuse-quota");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(stake) FROM game_bet WHERE user_id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("9900.00");
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

    @Test
    void concurrentBetsCannotConsumeTheSameRemainingMachineQuota() throws Exception {
        configureMachine(95, 18, 0, 10, true);
        jdbc.update("UPDATE agent SET total_limit = 1000, fan_limit = 500 WHERE id = ?", machineId);
        placeBet(PlayType.FAN, List.of(1), new BigDecimal("450.00"), "fan-last-50-existing");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> placeAtBarrier(start, "fan-last-50-a"));
            Future<Boolean> second = executor.submit(() -> placeAtBarrier(start, "fan-last-50-b"));
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS)).isNotEqualTo(second.get(20, TimeUnit.SECONDS));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class, accountId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("9500.00");
        assertThat(jdbc.queryForObject("SELECT SUM(stake) FROM game_bet WHERE user_id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo("500.00");
    }

    private boolean placeAtBarrier(CountDownLatch start, String key) throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            placeBet(PlayType.FAN, List.of(1), new BigDecimal("50.00"), key);
            return true;
        } catch (BetLimitExceededException rejected) {
            assertThat(rejected.getMessage()).contains("番限额500");
            return false;
        }
    }

    private BigDecimal placeAndReadOdds(PlayType playType, List<Integer> parameters, BigDecimal stake, String key) {
        placeBet(playType, parameters, stake, key);
        return snapshotOdds(accountId, "SINGLE-" + key);
    }

    private BigDecimal snapshotOdds(long account, String batchKey) {
        return jdbc.queryForObject("""
                SELECT b.odds_snapshot
                  FROM game_bet b
                  JOIN game_bet_batch batch ON batch.id = b.batch_id
                 WHERE batch.account_id = ? AND batch.idempotency_key = ?
                   AND b.instruction_no = 1
                """, BigDecimal.class, account, batchKey);
    }

    private void placeBet(PlayType playType, List<Integer> parameters, BigDecimal stake, String key) {
        gameService.placeBetForUser(userId, new PlaceBetRequest(1, playType, parameters, stake, key));
    }

    private long createPlayerAccount(String username, String displayName, long assignedMachineId, String grantKey) {
        long playerUserId = userRepository.insert(username, displayName, passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(playerUserId, "USER");
        long playerAccountId = walletService.ensureWalletForUser(playerUserId, displayName);
        jdbc.update("UPDATE demo_user_account SET agent_id = ? WHERE id = ?", assignedMachineId, playerAccountId);
        walletService.grant(playerUserId, playerUserId, new BigDecimal("10000.00"), "额度隔离测试上分", grantKey);
        return playerAccountId;
    }

    private void configurePeerMachine(long peerMachineId, int oddsRate, int fanLimit) {
        jdbc.update("""
                UPDATE agent
                   SET odds_rate = ?, special_odds_rate = 18, board_open = TRUE,
                       total_limit = 20000, positive_limit = 20000, angle_limit = 20000,
                       strict_limit = 20000, tong_limit = 20000, car_limit = 20000,
                       special_limit = 20000, odd_even_limit = 20000, big_small_limit = 20000,
                       fan_limit = ?, add_limit = 20000, player_min_stake = 1, player_max_stake = 5001
                 WHERE id = ?
                """, new BigDecimal(oddsRate), fanLimit, peerMachineId);
    }

    private void configureMachine(int oddsRate, int specialOddsRate, int closeSeconds, int cancelSeconds,
                                  boolean boardOpen) {
        jdbc.update("""
                UPDATE agent
                   SET odds_rate = ?, special_odds_rate = ?, close_seconds = ?, cancel_seconds = ?,
                       board_open = ?, rebate_rate = 5, special_rebate_rate = 1, rebate_mode = 'SINGLE',
                       total_limit = 20000, positive_limit = 20000, angle_limit = 20000,
                       strict_limit = 20000, tong_limit = 20000, car_limit = 20000,
                       special_limit = 20000, odd_even_limit = 20000, big_small_limit = 20000,
                       fan_limit = 20000, add_limit = 20000, player_min_stake = 1, player_max_stake = 5001
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
        jdbc.update("DELETE FROM game_bet_batch");
        jdbc.update("DELETE FROM game_issue_event");
        jdbc.update("DELETE FROM game_issue");
        jdbc.update("DELETE FROM game_definition WHERE game_code = ?", OTHER_GAME_CODE);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?, ?))",
                USERNAME, PEER_USERNAME, SAME_MACHINE_USERNAME, OTHER_MACHINE_USERNAME, "agent-odds-quota-bot");
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code IN "
                + "('MACHINE-ODDS-TEST', 'MACHINE-ODDS-TEST-B', 'MACHINE-ODDS-TEST-C'))");
        jdbc.update("DELETE FROM agent WHERE agent_code IN ('MACHINE-ODDS-TEST', 'MACHINE-ODDS-TEST-B', 'MACHINE-ODDS-TEST-C')");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?, ?))",
                USERNAME, PEER_USERNAME, SAME_MACHINE_USERNAME, OTHER_MACHINE_USERNAME, "agent-odds-quota-bot");
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?, ?, ?, ?)",
                USERNAME, PEER_USERNAME, SAME_MACHINE_USERNAME, OTHER_MACHINE_USERNAME, "agent-odds-quota-bot");
    }
}
