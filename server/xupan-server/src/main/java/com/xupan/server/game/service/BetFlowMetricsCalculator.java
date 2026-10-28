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
    private static final int MONEY_SCALE = 2;

    private BetFlowMetricsCalculator() {}

    public static Metrics calculate(PlayType playType, String parametersText, BigDecimal stake,
                                    BigDecimal odds, BigDecimal netProfit, String settlementStatus,
                                    BigDecimal rebateRate, BigDecimal specialRebateRate) {
        return calculate(playType, parametersText, stake, odds, netProfit, settlementStatus,
                rebateRate, specialRebateRate, null);
    }

    public static Metrics calculate(PlayType playType, String parametersText, BigDecimal stake,
                                    BigDecimal odds, BigDecimal netProfit, String settlementStatus,
                                    BigDecimal rebateRate, BigDecimal specialRebateRate,
                                    BigDecimal selectionUnitStake) {
        BigDecimal normalizedStake = money(stake);
        BigDecimal normalizedProfit = money(netProfit);
        boolean special = playType == PlayType.SPECIAL;
        BigDecimal unitStake = special
                ? normalizeUnitStake(parametersText, normalizedStake, selectionUnitStake)
                : normalizedStake;
        boolean won = "WIN".equals(settlementStatus);
        boolean lost = "LOSE".equals(settlementStatus);
        BigDecimal singleFlow = ZERO;
        BigDecimal doubleFlow = ZERO;
        BigDecimal fanShui = ZERO;
        BigDecimal doubleFanShui = ZERO;
        if (won) {
            BigDecimal payout = money(normalizedStake.add(normalizedProfit));
            singleFlow = special ? payout : normalizedProfit;
            doubleFlow = wonDoubleFlow(playType, parametersText, normalizedStake);
            fanShui = special
                    ? multiplyDown(unitStake, rate(specialRebateRate))
                    : rebate(normalizedProfit, rebateRate);
        } else if (lost) {
            doubleFlow = normalizedStake;
        }
        if (won || lost) {
            BigDecimal base = special
                    ? won ? money(normalizedStake.add(normalizedProfit)) : ZERO
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
        BigDecimal percentage = rate(rateValue).divide(ONE_HUNDRED, MONEY_SCALE, RoundingMode.DOWN);
        return base.multiply(percentage).setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    private static BigDecimal multiplyDown(BigDecimal amount, BigDecimal rateValue) {
        return amount.multiply(rateValue).setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    private static BigDecimal normalizeUnitStake(String parametersText, BigDecimal stake,
                                                 BigDecimal selectionUnitStake) {
        int selectionCount = selectionCount(parametersText);
        BigDecimal unitStake = selectionUnitStake;
        if (unitStake == null) {
            try {
                unitStake = stake.divide(BigDecimal.valueOf(selectionCount), MONEY_SCALE,
                        RoundingMode.UNNECESSARY);
            } catch (ArithmeticException invalidUnitStake) {
                throw new IllegalArgumentException("特码总额必须能按号码数等分到两位小数", invalidUnitStake);
            }
        }
        if (unitStake.signum() <= 0 || unitStake.scale() > MONEY_SCALE
                || money(unitStake).multiply(BigDecimal.valueOf(selectionCount)).compareTo(stake) != 0) {
            throw new IllegalArgumentException("特码总额必须等于单号投注额乘号码数");
        }
        return money(unitStake);
    }

    private static int selectionCount(String parametersText) {
        if (parametersText == null || parametersText.isBlank()) {
            throw new IllegalArgumentException("特码必须至少包含一个号码");
        }
        int count = 0;
        for (String value : parametersText.split(",")) {
            if (!value.isBlank()) count++;
        }
        if (count == 0) throw new IllegalArgumentException("特码必须至少包含一个号码");
        return count;
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
