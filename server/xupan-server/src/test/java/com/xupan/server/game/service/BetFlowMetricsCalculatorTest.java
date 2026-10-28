package com.xupan.server.game.service;

import com.xupan.server.game.domain.PlayType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BetFlowMetricsCalculatorTest {

    @Test
    void specialComboUsesWinningNumberUnitForSingleRebateAndGrossPayoutForDoubleF() {
        BetFlowMetricsCalculator.Metrics metrics = BetFlowMetricsCalculator.calculate(
                PlayType.SPECIAL, "1,2", new BigDecimal("200.00"), new BigDecimal("18.00"),
                new BigDecimal("1600.00"), "WIN", new BigDecimal("1.50"), new BigDecimal("0.01"),
                new BigDecimal("100.00"));

        assertThat(metrics.singleFlow()).isEqualByComparingTo("1800.00");
        assertThat(metrics.fanShui()).isEqualByComparingTo("1.00");
        assertThat(metrics.doubleFanShui()).isEqualByComparingTo("18.00");
    }

    @Test
    void specialSingleRebateUsesRWhileDoubleRebateUsesPlayerF() {
        BetFlowMetricsCalculator.Metrics metrics = BetFlowMetricsCalculator.calculate(
                PlayType.SPECIAL, "12", new BigDecimal("100.00"), new BigDecimal("18.00"),
                new BigDecimal("1700.00"), "WIN", new BigDecimal("2.00"), BigDecimal.ONE);

        assertThat(metrics.fanShui()).isEqualByComparingTo("100.00");
        assertThat(metrics.doubleFanShui()).isEqualByComparingTo("36.00");
    }

    @Test
    void appliesBy220IntermediateTwoDecimalTruncationToFPercentage() {
        BetFlowMetricsCalculator.Metrics metrics = BetFlowMetricsCalculator.calculate(
                PlayType.FAN, "2", new BigDecimal("100.00"), new BigDecimal("3.850"),
                new BigDecimal("100.00"), "WIN", new BigDecimal("1.50"), BigDecimal.ZERO);

        assertThat(metrics.fanShui()).isEqualByComparingTo("1.00");
    }

    @Test
    void legacySpecialMetricsInferUnitStakeFromStoredInstructionTotal() {
        BetFlowMetricsCalculator.Metrics metrics = BetFlowMetricsCalculator.calculate(
                PlayType.SPECIAL, "1,2", new BigDecimal("200.00"), new BigDecimal("18.00"),
                new BigDecimal("1600.00"), "WIN", new BigDecimal("1.50"), new BigDecimal("0.01"));

        assertThat(metrics.fanShui()).isEqualByComparingTo("1.00");
    }

    @Test
    void fractionalRebatesMatchBy220TruncationForOneSpecialUnit() {
        BetFlowMetricsCalculator.Metrics metrics = BetFlowMetricsCalculator.calculate(
                PlayType.SPECIAL, "12", new BigDecimal("123.45"), new BigDecimal("18.00"),
                new BigDecimal("2098.65"), "WIN", new BigDecimal("1.50"), new BigDecimal("0.0199"),
                new BigDecimal("123.45"));

        // BCMath: 123.45 * 0.0199 -> 2.45; 1.50 / 100 -> 0.01; 2222.10 * 0.01 -> 22.22.
        assertThat(metrics.singleFlow()).isEqualByComparingTo("2222.10");
        assertThat(metrics.fanShui()).isEqualByComparingTo("2.45");
        assertThat(metrics.doubleFanShui()).isEqualByComparingTo("22.22");
    }

    @Test
    void fractionalRebatesTruncateIntermediateMultiplierAndFinalAmount() {
        BetFlowMetricsCalculator.Metrics metrics = BetFlowMetricsCalculator.calculate(
                PlayType.SPECIAL, "12", new BigDecimal("123.45"), new BigDecimal("18.00"),
                new BigDecimal("2154.54"), "WIN", new BigDecimal("1.99"), new BigDecimal("0.015"),
                new BigDecimal("123.45"));

        // BCMath: 123.45 * 0.015 -> 1.85; 1.99 / 100 -> 0.01; 2277.99 * 0.01 -> 22.77.
        assertThat(metrics.singleFlow()).isEqualByComparingTo("2277.99");
        assertThat(metrics.fanShui()).isEqualByComparingTo("1.85");
        assertThat(metrics.doubleFanShui()).isEqualByComparingTo("22.77");
    }
}
