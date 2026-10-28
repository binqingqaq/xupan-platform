package com.xupan.server.game.service;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SettlementService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal ONE = BigDecimal.ONE;

    public SettlementResult settle(
            PlayType playType,
            List<Integer> parameters,
            BigDecimal stake,
            BigDecimal odds,
            BallResult result
    ) {
        BigDecimal unitStake = playType == PlayType.SPECIAL
                ? inferUnitStake(parameters, stake)
                : stake;
        return settle(playType, parameters, stake, odds, unitStake, null, result);
    }

    public SettlementResult settle(
            PlayType playType,
            List<Integer> parameters,
            BigDecimal stake,
            BigDecimal odds,
            BigDecimal unitStake,
            BallResult result
    ) {
        return settle(playType, parameters, stake, odds, unitStake, null, result);
    }

    public SettlementResult settle(
            PlayType playType,
            List<Integer> parameters,
            BigDecimal stake,
            BigDecimal odds,
            BigDecimal unitStake,
            BigDecimal machineOddsRate,
            BallResult result
    ) {
        if (playType == null || result == null) {
            throw new IllegalArgumentException("玩法和开奖结果不能为空");
        }

        List<Integer> normalizedParameters = parameters == null ? List.of() : parameters;
        validateBet(playType, normalizedParameters, stake, odds);
        BigDecimal normalizedUnitStake = playType == PlayType.SPECIAL
                ? validateUnitStake(normalizedParameters, stake, unitStake)
                : stake;
        SettlementStatus status = statusFor(playType, normalizedParameters, result);
        BigDecimal netProfit = switch (status) {
            case WIN -> (playType == PlayType.SPECIAL
                    ? normalizedUnitStake.multiply(odds).subtract(stake)
                    : playType == PlayType.CAR && machineOddsRate != null
                    ? stake.multiply(machineOddsRate).divide(new BigDecimal("300"), 2, RoundingMode.DOWN)
                    : stake.multiply(odds.subtract(ONE))).setScale(2, RoundingMode.HALF_UP);
            case LOSE -> stake.negate().setScale(2, RoundingMode.HALF_UP);
            case DRAW, PENDING, CANCELED -> ZERO;
        };
        BigDecimal oddsSnapshot = playType == PlayType.SPECIAL
                ? odds.setScale(4, RoundingMode.UNNECESSARY)
                : odds.setScale(3, RoundingMode.UNNECESSARY);
        return new SettlementResult(status, money(stake), oddsSnapshot, netProfit,
                playType + " 玩法，结果为 " + status);
    }

    public void validateBet(PlayType playType, List<Integer> parameters,
                            BigDecimal stake, BigDecimal odds) {
        validateStake(stake);
        validateOdds(playType, odds);
        if (playType == null) {
            throw new IllegalArgumentException("玩法不能为空");
        }
        validateParameters(playType, parameters == null ? List.of() : parameters);
    }

    private SettlementStatus statusFor(PlayType playType, List<Integer> rawParameters, BallResult result) {
        List<Integer> parameters = rawParameters == null ? List.of() : rawParameters;
        int fan = result.fan();
        return switch (playType) {
            case FAN -> containsExactly(parameters, 1, fan) ? SettlementStatus.WIN : SettlementStatus.LOSE;
            case ANGLE -> fixedSet(parameters, 2) && parameters.contains(fan)
                    ? SettlementStatus.WIN : SettlementStatus.LOSE;
            case CAR -> fixedSet(parameters, 3) && parameters.contains(fan)
                    ? SettlementStatus.WIN : SettlementStatus.LOSE;
            case STRICT -> {
                requireSize(parameters, 2, playType);
                yield fan == parameters.get(0) ? SettlementStatus.WIN
                        : fan == parameters.get(1) ? SettlementStatus.DRAW : SettlementStatus.LOSE;
            }
            case ADD -> {
                requireSize(parameters, 3, playType);
                yield fan == parameters.get(0) ? SettlementStatus.WIN
                        : parameters.subList(1, 3).contains(fan) ? SettlementStatus.DRAW : SettlementStatus.LOSE;
            }
            case POSITIVE -> {
                int main = singleFan(parameters, playType);
                int opposite = main <= 2 ? main + 2 : main - 2;
                yield fan == main ? SettlementStatus.WIN
                        : fan == opposite ? SettlementStatus.LOSE : SettlementStatus.DRAW;
            }
            case TONG -> {
                requireSize(parameters, 3, playType);
                yield parameters.subList(1, 3).contains(fan) ? SettlementStatus.WIN
                        : fan == parameters.get(0) ? SettlementStatus.LOSE : SettlementStatus.DRAW;
            }
            case NONE -> {
                if (parameters.size() == 2) {
                    requireDistinctFans(parameters, 2, playType);
                    yield fan == parameters.get(0) ? SettlementStatus.WIN
                            : fan == parameters.get(1) ? SettlementStatus.LOSE : SettlementStatus.DRAW;
                }
                requireDistinctFans(parameters, 3, playType);
                yield parameters.subList(0, 2).contains(fan) ? SettlementStatus.WIN
                        : fan == parameters.get(2) ? SettlementStatus.LOSE : SettlementStatus.DRAW;
            }
            case ODD_EVEN -> {
                int choice = singleChoice(parameters, playType);
                boolean odd = "ODD".equals(result.parity());
                yield (choice == 1 && odd) || (choice == 2 && !odd)
                        ? SettlementStatus.WIN : SettlementStatus.LOSE;
            }
            case BIG_SMALL -> {
                int choice = singleChoice(parameters, playType);
                boolean big = "BIG".equals(result.size());
                yield (choice == 1 && big) || (choice == 2 && !big)
                        ? SettlementStatus.WIN : SettlementStatus.LOSE;
            }
            case SPECIAL -> parameters.contains(result.number())
                    ? SettlementStatus.WIN : SettlementStatus.LOSE;
        };
    }

    private static void validateParameters(PlayType playType, List<Integer> parameters) {
        switch (playType) {
            case FAN, POSITIVE -> requireSize(parameters, 1, playType);
            case ANGLE -> requireDistinctFans(parameters, 2, playType);
            case CAR -> requireDistinctFans(parameters, 3, playType);
            case STRICT -> requireSize(parameters, 2, playType);
            case ADD, TONG -> requireDistinctFans(parameters, 3, playType);
            case NONE -> {
                if (parameters.size() != 2 && parameters.size() != 3) {
                    throw new IllegalArgumentException(playType + " 需要 2 或 3 个参数");
                }
                requireDistinctFans(parameters, parameters.size(), playType);
            }
            case ODD_EVEN, BIG_SMALL -> singleChoice(parameters, playType);
            case SPECIAL -> {
                if (parameters.isEmpty() || new HashSet<>(parameters).size() != parameters.size()
                        || parameters.stream().anyMatch(value -> value < 1 || value > 20)) {
                    throw new IllegalArgumentException(playType + " 参数必须是 1 到 20 的不重复号码");
                }
            }
        }
    }

    private static boolean fixedSet(List<Integer> parameters, int expectedSize) {
        return parameters.size() == expectedSize && new HashSet<>(parameters).size() == expectedSize
                && parameters.stream().allMatch(SettlementService::validFan);
    }

    private static void requireDistinctFans(List<Integer> parameters, int expectedSize, PlayType playType) {
        if (!fixedSet(parameters, expectedSize)) {
            throw new IllegalArgumentException(playType + " 参数必须是 " + expectedSize + " 个不重复的 1 到 4 番");
        }
    }

    private static boolean containsExactly(List<Integer> parameters, int expectedSize, int value) {
        return parameters.size() == expectedSize && parameters.get(0) == value;
    }

    private static int singleFan(List<Integer> parameters, PlayType playType) {
        requireSize(parameters, 1, playType);
        requireFan(parameters.get(0), playType);
        return parameters.get(0);
    }

    private static int singleChoice(List<Integer> parameters, PlayType playType) {
        requireSize(parameters, 1, playType);
        if (parameters.get(0) < 1 || parameters.get(0) > 2) {
            throw new IllegalArgumentException(playType + " 参数必须是 1 或 2");
        }
        return parameters.get(0);
    }

    private static void requireSize(List<Integer> parameters, int expectedSize, PlayType playType) {
        if (parameters.size() != expectedSize) {
            throw new IllegalArgumentException(playType + " 需要 " + expectedSize + " 个参数");
        }
        if (!parameters.stream().allMatch(SettlementService::validFan)) {
            throw new IllegalArgumentException(playType + " 参数必须是 1 到 4");
        }
    }

    private static void requireFan(int value, PlayType playType) {
        if (!validFan(value)) {
            throw new IllegalArgumentException(playType + " 参数必须是 1 到 4");
        }
    }

    private static boolean validFan(int value) {
        return value >= 1 && value <= 4;
    }

    private static void validateStake(BigDecimal value) {
        if (value == null || value.scale() > 2 || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("投注额必须是大于 0 的金额，最多两位小数");
        }
    }

    private static void validateOdds(PlayType playType, BigDecimal value) {
        int maxScale = playType == PlayType.SPECIAL ? 4 : 3;
        int effectiveScale = value == null ? Integer.MAX_VALUE : Math.max(0, value.stripTrailingZeros().scale());
        if (value == null || effectiveScale > maxScale || value.compareTo(ONE) < 0) {
            throw new IllegalArgumentException("赔率必须是不小于 1 的金额且精度符合玩法要求");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal inferUnitStake(List<Integer> parameters, BigDecimal stake) {
        if (parameters == null || parameters.isEmpty() || stake == null) {
            throw new IllegalArgumentException("特码需要号码单价");
        }
        try {
            return stake.divide(BigDecimal.valueOf(parameters.size()), 2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidUnitStake) {
            throw new IllegalArgumentException("特码总额必须能按号码数等分到两位小数", invalidUnitStake);
        }
    }

    private static BigDecimal validateUnitStake(List<Integer> parameters, BigDecimal stake,
                                                BigDecimal unitStake) {
        if (unitStake == null) {
            return inferUnitStake(parameters, stake);
        }
        if (unitStake.scale() > 2 || unitStake.signum() <= 0) {
            throw new IllegalArgumentException("特码单号投注额必须为正数且最多两位小数");
        }
        BigDecimal normalized = money(unitStake);
        if (normalized.multiply(BigDecimal.valueOf(parameters.size())).compareTo(money(stake)) != 0) {
            throw new IllegalArgumentException("特码总投注额必须等于单号投注额乘号码数");
        }
        return normalized;
    }
}
