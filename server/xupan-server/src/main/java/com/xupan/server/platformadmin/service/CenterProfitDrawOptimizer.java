package com.xupan.server.platformadmin.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Finds a legal draw with the greatest modeled center net profit. */
public final class CenterProfitDrawOptimizer {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private CenterProfitDrawOptimizer() {
    }

    /**
     * @param netProfitByBallAndNumber rows are balls; column index zero represents number 1
     * @param target minimum acceptable center net profit
     * @param distinctNumbers whether selected numbers must be unique across balls
     * @return the deterministic maximum-profit draw when its profit reaches the target
     */
    public static Optional<Result> optimize(List<List<BigDecimal>> netProfitByBallAndNumber,
                                            BigDecimal target, boolean distinctNumbers) {
        validate(netProfitByBallAndNumber, target, distinctNumbers);
        int ballCount = netProfitByBallAndNumber.size();
        int numberCount = netProfitByBallAndNumber.getFirst().size();
        List<Integer> numbers = distinctNumbers
                ? maximumDistinctAssignment(netProfitByBallAndNumber, numberCount)
                : maximumIndependentChoices(netProfitByBallAndNumber);
        BigDecimal total = ZERO;
        for (int ball = 0; ball < ballCount; ball++) {
            total = total.add(netProfitByBallAndNumber.get(ball).get(numbers.get(ball) - 1));
        }
        total = total.setScale(2);
        if (total.compareTo(target) < 0) {
            return Optional.empty();
        }
        return Optional.of(new Result(numbers, total));
    }

    private static List<Integer> maximumIndependentChoices(List<List<BigDecimal>> matrix) {
        List<Integer> numbers = new ArrayList<>(matrix.size());
        for (List<BigDecimal> row : matrix) {
            int best = 0;
            for (int number = 1; number < row.size(); number++) {
                if (row.get(number).compareTo(row.get(best)) > 0) {
                    best = number;
                }
            }
            numbers.add(best + 1);
        }
        return List.copyOf(numbers);
    }

    private static List<Integer> maximumDistinctAssignment(List<List<BigDecimal>> matrix, int numberCount) {
        int ballCount = matrix.size();
        BigDecimal[] rowPotential = new BigDecimal[ballCount + 1];
        BigDecimal[] columnPotential = new BigDecimal[numberCount + 1];
        java.util.Arrays.fill(rowPotential, BigDecimal.ZERO);
        java.util.Arrays.fill(columnPotential, BigDecimal.ZERO);
        int[] assignedRow = new int[numberCount + 1];
        int[] previousColumn = new int[numberCount + 1];

        // Rectangular Hungarian assignment minimizes negated profit. Columns are scanned
        // in number order, making equal-profit solutions deterministic.
        for (int row = 1; row <= ballCount; row++) {
            assignedRow[0] = row;
            BigDecimal[] minimumReducedCost = new BigDecimal[numberCount + 1];
            boolean[] usedColumn = new boolean[numberCount + 1];
            int currentColumn = 0;
            do {
                usedColumn[currentColumn] = true;
                int currentRow = assignedRow[currentColumn];
                BigDecimal delta = null;
                int nextColumn = 0;
                for (int column = 1; column <= numberCount; column++) {
                    if (usedColumn[column]) {
                        continue;
                    }
                    BigDecimal reducedCost = matrix.get(currentRow - 1).get(column - 1).negate()
                            .subtract(rowPotential[currentRow]).subtract(columnPotential[column]);
                    if (minimumReducedCost[column] == null
                            || reducedCost.compareTo(minimumReducedCost[column]) < 0) {
                        minimumReducedCost[column] = reducedCost;
                        previousColumn[column] = currentColumn;
                    }
                    if (delta == null || minimumReducedCost[column].compareTo(delta) < 0
                            || (minimumReducedCost[column].compareTo(delta) == 0 && column < nextColumn)) {
                        delta = minimumReducedCost[column];
                        nextColumn = column;
                    }
                }
                for (int column = 0; column <= numberCount; column++) {
                    if (usedColumn[column]) {
                        rowPotential[assignedRow[column]] = rowPotential[assignedRow[column]].add(delta);
                        columnPotential[column] = columnPotential[column].subtract(delta);
                    } else if (minimumReducedCost[column] != null) {
                        minimumReducedCost[column] = minimumReducedCost[column].subtract(delta);
                    }
                }
                currentColumn = nextColumn;
            } while (assignedRow[currentColumn] != 0);

            do {
                int priorColumn = previousColumn[currentColumn];
                assignedRow[currentColumn] = assignedRow[priorColumn];
                currentColumn = priorColumn;
            } while (currentColumn != 0);
        }

        int[] numberByBall = new int[ballCount];
        for (int column = 1; column <= numberCount; column++) {
            if (assignedRow[column] != 0) {
                numberByBall[assignedRow[column] - 1] = column;
            }
        }
        List<Integer> numbers = new ArrayList<>(ballCount);
        for (int number : numberByBall) {
            if (number == 0) {
                throw new IllegalStateException("最大权指派未能为每个球位分配号码");
            }
            numbers.add(number);
        }
        return List.copyOf(numbers);
    }

    private static void validate(List<List<BigDecimal>> matrix, BigDecimal target, boolean distinctNumbers) {
        if (matrix == null || matrix.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个球位收益行");
        }
        if (target == null || target.signum() < 0 || !hasCentPrecision(target)) {
            throw new IllegalArgumentException("目标净赢必须是非负的两位小数金额");
        }
        if (matrix.getFirst() == null || matrix.getFirst().isEmpty()) {
            throw new IllegalArgumentException("至少需要一个可选号码");
        }
        int numberCount = matrix.getFirst().size();
        if (distinctNumbers && matrix.size() > numberCount) {
            throw new IllegalArgumentException("号码数量不足以为每个球位分配不重复号码");
        }
        for (List<BigDecimal> row : matrix) {
            if (row == null || row.size() != numberCount) {
                throw new IllegalArgumentException("每个球位必须提供相同数量的号码收益");
            }
            for (BigDecimal amount : row) {
                if (amount == null || !hasCentPrecision(amount)) {
                    throw new IllegalArgumentException("每个号码收益必须是两位小数金额");
                }
            }
        }
    }

    private static boolean hasCentPrecision(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= 2;
    }

    public record Result(List<Integer> numbers, BigDecimal centerNetProfit) {
        public Result {
            numbers = List.copyOf(numbers);
            centerNetProfit = centerNetProfit.setScale(2);
        }
    }
}
