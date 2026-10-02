package com.xupan.server.game.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/** Renders persisted bet fields back into the same command form accepted by the room. */
public final class BetCommandFormatter {

    private BetCommandFormatter() {
    }

    public static String format(PlayType playType, List<Integer> parameters, BigDecimal stake) {
        if (playType == null || parameters == null || stake == null) {
            throw new IllegalArgumentException("注单指令参数不完整");
        }
        String amount = stake.stripTrailingZeros().toPlainString();
        return switch (playType) {
            case FAN -> parameter(parameters, 0) + "番" + amount;
            case ANGLE -> parameters.stream().map(String::valueOf).collect(Collectors.joining()) + "角" + amount;
            case CAR -> formatCar(parameters, amount);
            case STRICT -> parameter(parameters, 0) + "严" + parameter(parameters, 1) + "/" + amount;
            case ADD -> parameter(parameters, 0) + "加" + parameter(parameters, 1) + parameter(parameters, 2) + "/" + amount;
            case POSITIVE -> parameter(parameters, 0) + "正" + amount;
            case TONG -> parameter(parameters, 0) + "通" + parameter(parameters, 1) + parameter(parameters, 2) + "/" + amount;
            case NONE -> formatNone(parameters, amount);
            case ODD_EVEN -> (parameter(parameters, 0) == 1 ? "单" : "双") + amount;
            case BIG_SMALL -> (parameter(parameters, 0) == 1 ? "大" : "小") + amount;
            case SPECIAL -> parameters.stream().map(value -> String.format("%02d", value))
                    .collect(Collectors.joining("/")) + "特" + amount;
        };
    }

    private static String formatCar(List<Integer> parameters, String amount) {
        if (parameters.size() == 3) {
            int excluded = java.util.stream.IntStream.rangeClosed(1, 4)
                    .filter(value -> !parameters.contains(value))
                    .findFirst()
                    .orElse(0);
            return excluded + "车" + amount;
        }
        return parameter(parameters, 0) + "车" + amount;
    }

    private static String formatNone(List<Integer> parameters, String amount) {
        if (parameters.size() >= 3) {
            return parameter(parameters, 0) + parameter(parameters, 1) + "无" + parameter(parameters, 2) + "/" + amount;
        }
        return parameter(parameters, 0) + "无" + parameter(parameters, 1) + "/" + amount;
    }

    private static int parameter(List<Integer> parameters, int index) {
        if (index >= parameters.size()) {
            throw new IllegalArgumentException("注单指令参数不足");
        }
        return parameters.get(index);
    }
}
