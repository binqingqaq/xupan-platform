package com.xupan.server.game.service;

import com.xupan.server.game.domain.PlayType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Per-bet BY220 getRecordData/getShuang/getShuangs flow and rebate calculations. */
public final class BetFlowMetricsCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal THREE = new BigDecimal("3");

    private BetFlowMetricsCalculator() {}

    public static Metrics calculate(PlayType playType, String parametersText, BigDecimal stake,
                                    BigDecimal odds, BigDecimal netProfit, String settlementStatus,
                                    BigDecimal rebateRate, BigDecimal specialRebateRate) {
        BigDecimal normalizedStake = money(stake);
        BigDecimal normalizedProfit = money(netProfit);
        BigDecimal normalizedOdds = odds == null ? BigDecimal.ZERO : odds;
        boolean special = playType == PlayType.SPECIAL;
        boolean won = "WIN".equals(settlementStatus);
        boolean lost = "LOSE".equals(settlementStatus);
        BigDecimal singleFlow = ZERO;
        BigDecimal doubleFlow = ZERO;
        BigDecimal fanShui = ZERO;
        BigDecimal doubleFanShui = ZERO;
        if (won) {
            BigDecimal payout = money(normalizedStake.multiply(normalizedOdds));
            singleFlow = special ? payout : normalizedProfit;
            doubleFlow = wonDoubleFlow(playType, parametersText, normalizedStake);
            fanShui = special
                    ? money(normalizedStake.multiply(rate(specialRebateRate)))
                    : rebate(normalizedProfit, rebateRate);
        } else if (lost) {
            doubleFlow = normalizedStake;
        }
        if (won || lost) {
            BigDecimal base = special
                    ? won ? normalizedStake.multiply(normalizedOdds) : ZERO
                    : doubleRebateBase(playType, parametersText, normalizedStake, won);
            doubleFanShui = rebate(base, rebateRate);
        }
        return new Metrics(singleFlow, doubleFlow, normalizedProfit, fanShui, doubleFanShui);
    }

    private static BigDecimal wonDoubleFlow(PlayType playType, String parametersText, BigDecimal stake) {
        if (playType == PlayType.CAR) return divide(stake, THREE);
        if (playType == PlayType.TONG || isThreeParameterNone(playType, parametersText)) return divide(stake, TWO);
        return stake;
    }

    private static BigDecimal doubleRebateBase(PlayType playType, String parametersText, BigDecimal stake,
                                               boolean won) {
        if (playType == PlayType.CAR) return won ? divide(stake, THREE) : stake;
        if (playType == PlayType.TONG || isThreeParameterNone(playType, parametersText)) return divide(stake, TWO);
        return stake;
    }

    private static boolean isThreeParameterNone(PlayType playType, String parametersText) {
        if (playType != PlayType.NONE || parametersText == null) return false;
        int count = 0;
        for (String value : parametersText.split(",")) if (!value.isBlank()) count++;
        return count == 3;
    }

    private static BigDecimal rebate(BigDecimal base, BigDecimal rateValue) {
        return money(base.multiply(rate(rateValue)).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
    }

    private static BigDecimal divide(BigDecimal value, BigDecimal divisor) {
        return money(value.divide(divisor, 6, RoundingMode.HALF_UP));
    }

    private static BigDecimal rate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public record Metrics(BigDecimal singleFlow, BigDecimal doubleFlow, BigDecimal profit,
                          BigDecimal fanShui, BigDecimal doubleFanShui) {
        public static final Metrics ZERO = new Metrics(
                BetFlowMetricsCalculator.ZERO, BetFlowMetricsCalculator.ZERO, BetFlowMetricsCalculator.ZERO,
                BetFlowMetricsCalculator.ZERO, BetFlowMetricsCalculator.ZERO);

        public Metrics plus(Metrics other) {
            return new Metrics(money(singleFlow.add(other.singleFlow)), money(doubleFlow.add(other.doubleFlow)),
                    money(profit.add(other.profit)), money(fanShui.add(other.fanShui)),
                    money(doubleFanShui.add(other.doubleFanShui)));
        }
    }
}
