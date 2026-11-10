package com.xupan.server.platformadmin.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Checks target reachability and samples legal draws by modeled center net profit. */
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
                ? maximumDistinctAssignment(netProfitByBallAndNumber, allNumbers(numberCount))
                : maximumIndependentChoices(netProfitByBallAndNumber);
        BigDecimal total = totalProfit(netProfitByBallAndNumber, numbers);
        if (total.compareTo(target) < 0) {
            return Optional.empty();
        }
        return Optional.of(new Result(numbers, total));
    }

    /** Samples a qualifying draw, optionally excluding one previously written draw. */
    public static Optional<Result> sampleAtLeastTarget(List<List<BigDecimal>> netProfitByBallAndNumber,
                                                       BigDecimal target, boolean distinctNumbers,
                                                       List<Integer> excludedPrevious,
                                                       RandomGenerator random) {
        validate(netProfitByBallAndNumber, target, distinctNumbers);
        if (random == null) {
            throw new IllegalArgumentException("随机数生成器不能为空");
        }
        int ballCount = netProfitByBallAndNumber.size();
        int numberCount = netProfitByBallAndNumber.getFirst().size();
        if (excludedPrevious != null) {
            validateDraw(excludedPrevious, ballCount, numberCount, distinctNumbers);
        }
        if (maximumProfit(netProfitByBallAndNumber, 0, new boolean[numberCount], distinctNumbers)
                .compareTo(target) < 0) {
            return Optional.empty();
        }
        List<Integer> selected = new ArrayList<>(ballCount);
        if (excludedPrevious != null
                && !hasAlternativeCompletion(netProfitByBallAndNumber, target, distinctNumbers,
                selected, new boolean[numberCount], ZERO, excludedPrevious)) {
            return Optional.empty();
        }
        boolean found = sampleFrom(netProfitByBallAndNumber, target, distinctNumbers,
                excludedPrevious, random, selected, new boolean[numberCount], ZERO);
        if (!found) {
            return Optional.empty();
        }
        return Optional.of(new Result(selected, totalProfit(netProfitByBallAndNumber, selected)));
    }

    private static boolean sampleFrom(List<List<BigDecimal>> matrix, BigDecimal target,
                                      boolean distinctNumbers, List<Integer> excludedPrevious,
                                      RandomGenerator random, List<Integer> selected,
                                      boolean[] usedNumbers, BigDecimal prefixProfit) {
        int ball = selected.size();
        if (ball == matrix.size()) {
            return prefixProfit.compareTo(target) >= 0
                    && (excludedPrevious == null || !selected.equals(excludedPrevious));
        }
        int numberCount = matrix.getFirst().size();
        int[] order = new int[numberCount];
        for (int number = 0; number < numberCount; number++) {
            order[number] = number;
        }
        for (int index = order.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            int swap = order[index];
            order[index] = order[other];
            order[other] = swap;
        }

        for (int numberIndex : order) {
            if (distinctNumbers && usedNumbers[numberIndex]) {
                continue;
            }
            BigDecimal nextProfit = prefixProfit.add(matrix.get(ball).get(numberIndex));
            if (distinctNumbers) {
                usedNumbers[numberIndex] = true;
            }
            selected.add(numberIndex + 1);
            boolean reachable = maximumProfit(matrix, ball + 1, usedNumbers, distinctNumbers) != null
                    && nextProfit.add(maximumProfit(matrix, ball + 1, usedNumbers, distinctNumbers))
                    .compareTo(target) >= 0;
            if (reachable && excludedPrevious != null) {
                reachable = hasAlternativeCompletion(matrix, target, distinctNumbers, selected,
                        usedNumbers, nextProfit, excludedPrevious);
            }
            if (reachable && sampleFrom(matrix, target, distinctNumbers, excludedPrevious,
                    random, selected, usedNumbers, nextProfit)) {
                return true;
            }
            selected.removeLast();
            if (distinctNumbers) {
                usedNumbers[numberIndex] = false;
            }
        }
        return false;
    }

    private static boolean hasAlternativeCompletion(List<List<BigDecimal>> matrix, BigDecimal target,
                                                    boolean distinctNumbers, List<Integer> selected,
                                                    boolean[] usedNumbers, BigDecimal prefixProfit,
                                                    List<Integer> excludedPrevious) {
        if (!selected.equals(excludedPrevious.subList(0, selected.size()))) {
            return maximumProfit(matrix, selected.size(), usedNumbers, distinctNumbers) != null
                    && prefixProfit.add(maximumProfit(matrix, selected.size(), usedNumbers, distinctNumbers))
                    .compareTo(target) >= 0;
        }
        int firstRemainingBall = selected.size();
        int numberCount = matrix.getFirst().size();
        for (int divergentBall = firstRemainingBall; divergentBall < matrix.size(); divergentBall++) {
            boolean[] trialUsed = usedNumbers.clone();
            BigDecimal forcedProfit = prefixProfit;
            boolean validPrefix = true;
            for (int forcedBall = firstRemainingBall; forcedBall < divergentBall; forcedBall++) {
                int forcedNumberIndex = excludedPrevious.get(forcedBall) - 1;
                if (distinctNumbers && trialUsed[forcedNumberIndex]) {
                    validPrefix = false;
                    break;
                }
                if (distinctNumbers) {
                    trialUsed[forcedNumberIndex] = true;
                }
                forcedProfit = forcedProfit.add(matrix.get(forcedBall).get(forcedNumberIndex));
            }
            if (!validPrefix) {
                continue;
            }
            int excludedNumberIndex = excludedPrevious.get(divergentBall) - 1;
            for (int numberIndex = 0; numberIndex < numberCount; numberIndex++) {
                if (numberIndex == excludedNumberIndex || (distinctNumbers && trialUsed[numberIndex])) {
                    continue;
                }
                boolean[] afterChoice = trialUsed.clone();
                if (distinctNumbers) {
                    afterChoice[numberIndex] = true;
                }
                BigDecimal candidateProfit = forcedProfit.add(matrix.get(divergentBall).get(numberIndex));
                BigDecimal remainingMaximum = maximumProfit(matrix, divergentBall + 1,
                        afterChoice, distinctNumbers);
                if (remainingMaximum != null && candidateProfit.add(remainingMaximum).compareTo(target) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BigDecimal maximumProfit(List<List<BigDecimal>> matrix, int firstBall,
                                            boolean[] usedNumbers, boolean distinctNumbers) {
        if (firstBall == matrix.size()) {
            return ZERO;
        }
        List<List<BigDecimal>> remainingRows = matrix.subList(firstBall, matrix.size());
        if (!distinctNumbers) {
            return totalProfit(remainingRows, maximumIndependentChoices(remainingRows));
        }
        List<Integer> availableNumbers = new ArrayList<>();
        for (int index = 0; index < usedNumbers.length; index++) {
            if (!usedNumbers[index]) {
                availableNumbers.add(index);
            }
        }
        if (remainingRows.size() > availableNumbers.size()) {
            return null;
        }
        List<Integer> assignedNumbers = maximumDistinctAssignment(remainingRows, availableNumbers);
        return totalProfit(remainingRows, assignedNumbers);
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

    private static List<Integer> maximumDistinctAssignment(List<List<BigDecimal>> matrix,
                                                           List<Integer> availableNumbers) {
        int ballCount = matrix.size();
        int numberCount = availableNumbers.size();
        BigDecimal[] rowPotential = new BigDecimal[ballCount + 1];
        BigDecimal[] columnPotential = new BigDecimal[numberCount + 1];
        Arrays.fill(rowPotential, BigDecimal.ZERO);
        Arrays.fill(columnPotential, BigDecimal.ZERO);
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
                    int numberIndex = availableNumbers.get(column - 1);
                    BigDecimal reducedCost = matrix.get(currentRow - 1).get(numberIndex).negate()
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
                numberByBall[assignedRow[column] - 1] = availableNumbers.get(column - 1) + 1;
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

    private static BigDecimal totalProfit(List<List<BigDecimal>> matrix, List<Integer> numbers) {
        BigDecimal total = ZERO;
        for (int ball = 0; ball < matrix.size(); ball++) {
            total = total.add(matrix.get(ball).get(numbers.get(ball) - 1));
        }
        return total.setScale(2);
    }

    private static List<Integer> allNumbers(int numberCount) {
        List<Integer> numbers = new ArrayList<>(numberCount);
        for (int number = 1; number <= numberCount; number++) {
            numbers.add(number - 1);
        }
        return numbers;
    }

    private static void validateDraw(List<Integer> draw, int ballCount, int numberCount,
                                     boolean distinctNumbers) {
        if (draw.size() != ballCount || draw.stream().anyMatch(number -> number == null
                || number < 1 || number > numberCount)) {
            throw new IllegalArgumentException("排除的原预设必须是完整、合法的开奖结果");
        }
        if (distinctNumbers && draw.stream().distinct().count() != draw.size()) {
            throw new IllegalArgumentException("排除的原预设违反号码不重复规则");
        }
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
