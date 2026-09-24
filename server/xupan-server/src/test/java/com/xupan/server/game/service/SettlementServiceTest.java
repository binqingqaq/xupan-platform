package com.xupan.server.game.service;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementServiceTest {

    private final SettlementService service = new SettlementService();
    private final BallResult result = BallResult.fromNumber(18); // 2 番、大、双
    private final BigDecimal stake = new BigDecimal("100.00");

    @Test
    void settlesCoreWinAndLoseRules() {
        assertStatus(PlayType.FAN, List.of(2), SettlementStatus.WIN);
        assertStatus(PlayType.FAN, List.of(1), SettlementStatus.LOSE);
        assertStatus(PlayType.ANGLE, List.of(1, 2), SettlementStatus.WIN);
        assertStatus(PlayType.ODD_EVEN, List.of(2), SettlementStatus.WIN);
        assertStatus(PlayType.BIG_SMALL, List.of(1), SettlementStatus.WIN);
        assertStatus(PlayType.SPECIAL, List.of(2), SettlementStatus.LOSE);
    }

    @Test
    void settlesDrawRulesAndNoneIsTongAlias() {
        assertStatus(PlayType.STRICT, List.of(2, 1), SettlementStatus.WIN);
        assertStatus(PlayType.STRICT, List.of(1, 2), SettlementStatus.DRAW);
        assertStatus(PlayType.ADD, List.of(2, 1, 3), SettlementStatus.WIN);
        assertStatus(PlayType.ADD, List.of(1, 2, 3), SettlementStatus.DRAW);
        assertStatus(PlayType.POSITIVE, List.of(2), SettlementStatus.WIN);
        assertStatus(PlayType.POSITIVE, List.of(4), SettlementStatus.LOSE);
        assertStatus(PlayType.TONG, List.of(1, 2, 3), SettlementStatus.WIN);
        assertStatus(PlayType.NONE, List.of(1, 2, 3), SettlementStatus.WIN);
        assertStatus(PlayType.NONE, List.of(2, 1), SettlementStatus.WIN);
        assertStatus(PlayType.NONE, List.of(1, 2), SettlementStatus.LOSE);
        assertStatus(PlayType.NONE, List.of(1, 3), SettlementStatus.DRAW);
        assertStatus(PlayType.TONG, List.of(3, 4, 1), SettlementStatus.DRAW);
        assertStatus(PlayType.TONG, List.of(1, 3, 2), SettlementStatus.LOSE);
        assertStatus(PlayType.NONE, List.of(1, 2, 3), SettlementStatus.WIN);
        assertStatus(PlayType.CAR, List.of(1, 2, 3), SettlementStatus.WIN);
        assertStatus(PlayType.CAR, List.of(1, 3, 4), SettlementStatus.LOSE);
        assertStatus(PlayType.SPECIAL, List.of(18), SettlementStatus.WIN);
    }

    @Test
    void calculatesNetProfitFromOddsWithoutReturningStake() {
        SettlementResult settlement = service.settle(PlayType.FAN, List.of(2), stake,
                new BigDecimal("3.850"), result);

        assertThat(settlement.status()).isEqualTo(SettlementStatus.WIN);
        assertThat(settlement.netProfit()).isEqualByComparingTo("285.00");
        assertThat(settlement.odds()).isEqualByComparingTo("3.850");
    }

    @Test
    void rejectsInvalidAngleParametersInsteadOfTreatingThemAsLoss() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> service.settle(PlayType.ANGLE, List.of(1, 9), stake,
                        new BigDecimal("1.950"), result));
    }

    private void assertStatus(PlayType playType, List<Integer> parameters, SettlementStatus expected) {
        SettlementResult settlement = service.settle(playType, parameters, stake,
                new BigDecimal("1.950"), result);
        assertThat(settlement.status()).isEqualTo(expected);
    }
}
