package com.xupan.server.platformadmin.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CenterProfitDrawOptimizerTest {

    @Test
    void findsMaximumDistinctNumberAssignmentAcrossBalls() {
        var result = CenterProfitDrawOptimizer.optimize(matrix(
                new String[]{"10.00", "9.00"},
                new String[]{"10.00", "1.00"}), new BigDecimal("10.00"), true).orElseThrow();

        assertThat(result.numbers()).containsExactly(2, 1);
        assertThat(result.centerNetProfit()).isEqualByComparingTo("19.00");
    }

    @Test
    void returnsEmptyWhenMaximumProfitMissesTarget() {
        assertThat(CenterProfitDrawOptimizer.optimize(matrix(
                new String[]{"1.00", "2.00"},
                new String[]{"3.00", "1.00"}), new BigDecimal("5.01"), true)).isEmpty();
    }

    @Test
    void allowsNumberReuseWhenRuleDoesNotRequireDistinctNumbers() {
        var result = CenterProfitDrawOptimizer.optimize(matrix(
                new String[]{"8.00", "0.00"},
                new String[]{"9.00", "1.00"}), new BigDecimal("17.00"), false).orElseThrow();

        assertThat(result.numbers()).containsExactly(1, 1);
        assertThat(result.centerNetProfit()).isEqualByComparingTo("17.00");
    }

    @Test
    void resolvesTiesDeterministicallyAndPrefersEarlierNumbersForIndependentRows() {
        var matrix = matrix(
                new String[]{"2.00", "2.00", "1.00"},
                new String[]{"2.00", "2.00", "1.00"});

        var first = CenterProfitDrawOptimizer.optimize(matrix, new BigDecimal("4.00"), true).orElseThrow();
        var second = CenterProfitDrawOptimizer.optimize(matrix, new BigDecimal("4.00"), true).orElseThrow();

        assertThat(first.numbers()).containsExactlyElementsOf(second.numbers());
        assertThat(first.centerNetProfit()).isEqualByComparingTo("4.00");
    }

    @Test
    void validatesRowsTargetAndCentsPrecision() {
        assertThatThrownBy(() -> CenterProfitDrawOptimizer.optimize(List.of(), BigDecimal.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CenterProfitDrawOptimizer.optimize(matrix(
                new String[]{"1.001"}), BigDecimal.ZERO, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CenterProfitDrawOptimizer.optimize(matrix(
                new String[]{"1.00"}), new BigDecimal("-0.01"), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distinctAssignmentMatchesExhaustiveSearchForSmallMatrices() {
        Random random = new Random(47);
        for (int sample = 0; sample < 50; sample++) {
            List<List<BigDecimal>> matrix = new java.util.ArrayList<>();
            for (int ball = 0; ball < 4; ball++) {
                List<BigDecimal> row = new java.util.ArrayList<>();
                for (int number = 0; number < 6; number++) {
                    row.add(BigDecimal.valueOf(random.nextInt(401), 2));
                }
                matrix.add(row);
            }

            BigDecimal expected = maximumDistinctProfit(matrix, 0, new boolean[6]);
            var actual = CenterProfitDrawOptimizer.optimize(matrix, BigDecimal.ZERO.setScale(2), true)
                    .orElseThrow();

            assertThat(actual.centerNetProfit()).isEqualByComparingTo(expected);
        }
    }

    @Test
    void samplesOnlyQualifyingDistinctDrawsAndIsRepeatableForSeed() {
        List<List<BigDecimal>> matrix = matrix(
                new String[]{"1.00", "2.00", "3.00"},
                new String[]{"2.00", "3.00", "1.00"},
                new String[]{"3.00", "1.00", "2.00"});

        var first = CenterProfitDrawOptimizer.sampleAtLeastTarget(matrix, new BigDecimal("7.00"),
                true, null, new Random(29)).orElseThrow();
        var second = CenterProfitDrawOptimizer.sampleAtLeastTarget(matrix, new BigDecimal("7.00"),
                true, null, new Random(29)).orElseThrow();

        assertThat(first.numbers()).containsExactlyElementsOf(second.numbers());
        assertThat(first.numbers()).doesNotHaveDuplicates();
        assertThat(first.centerNetProfit()).isGreaterThanOrEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void samplerFindsAnotherQualifyingDrawWhenPreviousOneIsExcluded() {
        List<List<BigDecimal>> matrix = matrix(
                new String[]{"1.00", "1.00"},
                new String[]{"1.00", "1.00"});

        var result = CenterProfitDrawOptimizer.sampleAtLeastTarget(matrix, new BigDecimal("2.00"),
                true, List.of(1, 2), new Random(13)).orElseThrow();

        assertThat(result.numbers()).containsExactly(2, 1);
        assertThat(result.centerNetProfit()).isEqualByComparingTo("2.00");
    }

    @Test
    void samplerReturnsEmptyWhenExcludedPreviousIsTheOnlyQualifyingDraw() {
        List<List<BigDecimal>> matrix = matrix(
                new String[]{"10.00", "0.00"},
                new String[]{"0.00", "10.00"});

        assertThat(CenterProfitDrawOptimizer.sampleAtLeastTarget(matrix, new BigDecimal("20.00"),
                true, List.of(1, 2), new Random(3))).isEmpty();
    }

    @Test
    void samplerExcludesAnExactRepeatedSequenceWithoutBlockingOtherNumberReuse() {
        List<List<BigDecimal>> matrix = matrix(
                new String[]{"5.00", "5.00"},
                new String[]{"5.00", "5.00"});

        var result = CenterProfitDrawOptimizer.sampleAtLeastTarget(matrix, new BigDecimal("10.00"),
                false, List.of(1, 1), new Random(7)).orElseThrow();

        assertThat(result.numbers()).isNotEqualTo(List.of(1, 1));
        assertThat(result.centerNetProfit()).isEqualByComparingTo("10.00");
    }

    private static BigDecimal maximumDistinctProfit(List<List<BigDecimal>> matrix, int ball,
                                                    boolean[] usedNumbers) {
        if (ball == matrix.size()) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal best = null;
        for (int number = 0; number < usedNumbers.length; number++) {
            if (usedNumbers[number]) {
                continue;
            }
            usedNumbers[number] = true;
            BigDecimal candidate = matrix.get(ball).get(number)
                    .add(maximumDistinctProfit(matrix, ball + 1, usedNumbers));
            usedNumbers[number] = false;
            if (best == null || candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<List<BigDecimal>> matrix(String[]... rows) {
        return java.util.Arrays.stream(rows)
                .map(row -> java.util.Arrays.stream(row).map(BigDecimal::new).toList())
                .toList();
    }
}
