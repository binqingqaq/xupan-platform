package com.xupan.server.game.service;

import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BY220 赔率换算、封盘与取消的单元验证。
 *
 * <p>换算依据 {@code common.php:4562-4571}：普通玩法 {@code 1 + num × PeiLv/100}，
 * 特码直接使用 {@code tePeilv}；封盘依据 {@code common.php:2133-2135}。</p>
 */
class AgentBettingPolicyTest {

    private final AgentBettingPolicy policy = new AgentBettingPolicy();

    private static MachineBettingConfig machine(int oddsRate, int specialOddsRate, int closeSeconds, int cancelSeconds) {
        return new MachineBettingConfig(new BigDecimal(oddsRate), new BigDecimal(specialOddsRate),
                closeSeconds, cancelSeconds, "SINGLE", new BigDecimal("5"), new BigDecimal("1"));
    }

    @Test
    void playWeightsFollowBy220PlayerConsole() {
        assertThat(policy.weight(PlayType.FAN)).isEqualByComparingTo("3");
        assertThat(policy.weight(PlayType.STRICT)).isEqualByComparingTo("2");
        assertThat(policy.weight(PlayType.TONG)).isEqualByComparingTo("0.5");
        assertThat(policy.weight(PlayType.CAR)).isEqualByComparingTo("0.333333333333");
        assertThat(policy.weight(PlayType.ANGLE)).isEqualByComparingTo("1");
        assertThat(policy.weight(PlayType.NONE)).isEqualByComparingTo("1");
        assertThat(policy.weight(PlayType.ODD_EVEN)).isEqualByComparingTo("1");
        assertThat(policy.weight(PlayType.BIG_SMALL)).isEqualByComparingTo("1");
    }

    @Test
    void configuredOddsAreConvertedAndRoundedToThreeDecimals() {
        MachineBettingConfig machine = machine(95, 18, 0, 10);

        assertThat(policy.effectiveOdds(PlayType.BIG_SMALL, new BigDecimal("1.900"), machine))
                .isEqualByComparingTo("1.950");
        assertThat(policy.effectiveOdds(PlayType.FAN, new BigDecimal("3.500"), machine))
                .isEqualByComparingTo("3.850");
        assertThat(policy.effectiveOdds(PlayType.STRICT, new BigDecimal("2.500"), machine))
                .isEqualByComparingTo("2.900");
        assertThat(policy.effectiveOdds(PlayType.CAR, new BigDecimal("1.200"), machine))
                .isEqualByComparingTo("1.316");
        assertThat(policy.effectiveOdds(PlayType.TONG, new BigDecimal("1.200"), machine))
                .isEqualByComparingTo("1.475");
        assertThat(policy.effectiveOdds(PlayType.NONE, null, machine, List.of(1, 2, 3)))
                .isEqualByComparingTo("1.475");
        assertThat(policy.effectiveOdds(PlayType.NONE, null, machine, List.of(1, 2)))
                .isEqualByComparingTo("1.950");
        assertThat(policy.effectiveOdds(PlayType.SPECIAL, new BigDecimal("17.000"), machine))
                .isEqualByComparingTo("18.000");
    }

    @Test
    void displayOddsUseMachineRatesAndBy220CellSpecificPrecision() {
        MachineBettingConfig machine = machine(95, 18, 0, 10);

        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.BASE, machine))
                .isEqualByComparingTo("1.950");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.FAN, machine))
                .isEqualByComparingTo("3.850");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.STRICT, machine))
                .isEqualByComparingTo("2.900");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.CAR, machine))
                .isEqualByComparingTo("1.316");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.NONE_PAIR, machine))
                .isEqualByComparingTo("1.950");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.NONE_TRIPLE, machine))
                .isEqualByComparingTo("1.475");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.TONG, machine))
                .isEqualByComparingTo("1.475");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.SPECIAL, machine).toPlainString())
                .isEqualTo("18.0000");
        assertThat(policy.weight(PlayType.CAR)).isEqualByComparingTo("0.333333333333");
    }

    @Test
    void preservesFourDecimalSpecialOddsConfiguredForTheMachine() {
        MachineBettingConfig machine = new MachineBettingConfig(new BigDecimal("95"),
                new BigDecimal("18.1234"), 0, 10, "SINGLE", new BigDecimal("5"), new BigDecimal("1"));

        assertThat(policy.effectiveOdds(PlayType.SPECIAL, null, machine)).isEqualByComparingTo("18.1234");
        assertThat(policy.displayOdds(AgentBettingPolicy.DisplayOddsRuleKey.SPECIAL, machine))
                .isEqualByComparingTo("18.1234");
    }

    @Test
    void unconfiguredMachineNeverFallsBackToGlobalOdds() {
        MachineBettingConfig machine = machine(0, 0, 0, 10);

        assertThatThrownBy(() -> policy.effectiveOdds(PlayType.FAN, new BigDecimal("3.500"), machine))
                .isInstanceOf(IllegalStateException.class).hasMessage("MACHINE_ODDS_UNAVAILABLE");
        assertThatThrownBy(() -> policy.effectiveOdds(PlayType.SPECIAL, new BigDecimal("17.000"), machine))
                .isInstanceOf(IllegalStateException.class).hasMessage("MACHINE_SPECIAL_ODDS_UNAVAILABLE");
    }

    @Test
    void missingGlobalOddsIsRejectedWhenMachineHasNoConfiguration() {
        MachineBettingConfig machine = machine(0, 0, 0, 10);

        assertThatThrownBy(() -> policy.effectiveOdds(PlayType.FAN, null, machine))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MACHINE_ODDS_UNAVAILABLE");
    }

    @Test
    void sealWindowFollowsBy220BoundarySemantics() {
        Instant drawEndsAt = Instant.parse("2026-10-02T12:00:00Z");
        GameDataRepository.IssueRecord issue = new GameDataRepository.IssueRecord(
                "3000000", "OPEN", "BETTING", List.of(), drawEndsAt.minusSeconds(300),
                drawEndsAt.minusSeconds(120), drawEndsAt, null);

        MachineBettingConfig sixty = machine(95, 18, 60, 10);
        assertThat(policy.isClosedForBetting(sixty, issue, drawEndsAt.minusSeconds(61))).isFalse();
        // 剩余时间正好等于封盘秒数时仍按下注允许处理（BY220 使用 < 判断）。
        assertThat(policy.isClosedForBetting(sixty, issue, drawEndsAt.minusSeconds(60))).isFalse();
        assertThat(policy.isClosedForBetting(sixty, issue, drawEndsAt.minusSeconds(59))).isTrue();

        // close_seconds=0 表示不额外提前封盘，行为与配置前一致。
        MachineBettingConfig zero = machine(95, 18, 0, 10);
        assertThat(policy.isClosedForBetting(zero, issue, drawEndsAt.minusSeconds(1))).isFalse();
    }

    @Test
    void manualMachineClosureAndSealWindowProduceDistinctReasons() {
        Instant drawEndsAt = Instant.parse("2026-10-02T12:00:00Z");
        GameDataRepository.IssueRecord issue = new GameDataRepository.IssueRecord(
                "3000000", "OPEN", "BETTING", List.of(), drawEndsAt.minusSeconds(300),
                drawEndsAt.minusSeconds(120), drawEndsAt, null);

        MachineBettingConfig manuallyClosed = new MachineBettingConfig(new BigDecimal("95"), new BigDecimal("18"),
                60, 10, false, "SINGLE", BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(policy.rejectBet(manuallyClosed, issue, drawEndsAt.minusSeconds(90)))
                .contains(AgentBettingPolicy.MANUALLY_CLOSED);
        assertThat(policy.rejectBet(machine(95, 18, 60, 10), issue, drawEndsAt.minusSeconds(30)))
                .contains(AgentBettingPolicy.MACHINE_CLOSED);
        assertThat(policy.rejectBet(machine(95, 18, 60, 10), issue, drawEndsAt.minusSeconds(90)))
                .isEmpty();
    }

    @Test
    void cancellationWindowAndDisabledStateFollowCancelSeconds() {
        Instant now = Instant.parse("2026-10-02T12:00:00Z");

        assertThat(policy.cancellationWindowStart(machine(95, 18, 0, 10), now))
                .isEqualTo(now.minusSeconds(10));
        assertThat(machine(95, 18, 0, 0).cancellationDisabled()).isTrue();
        assertThat(machine(95, 18, 0, 10).cancellationDisabled()).isFalse();
    }
}
