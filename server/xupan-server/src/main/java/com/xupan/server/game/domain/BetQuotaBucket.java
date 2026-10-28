package com.xupan.server.game.domain;

import java.util.List;

/** BY220 limit buckets are kept separate from settlement rule keys. */
public enum BetQuotaBucket {
    TOTAL,
    POSITIVE,
    ANGLE,
    STRICT,
    TONG,
    CAR,
    SPECIAL_NUMBER,
    ODD_EVEN,
    BIG_SMALL,
    FAN,
    ADD;

    public static BetQuotaBucket forBet(PlayType playType, List<Integer> parameters) {
        if (playType == null) throw new IllegalArgumentException("玩法不能为空");
        int parameterCount = parameters == null ? 0 : parameters.size();
        return switch (playType) {
            case FAN -> FAN;
            case POSITIVE -> POSITIVE;
            case ANGLE -> ANGLE;
            case CAR -> CAR;
            case STRICT -> STRICT;
            case ADD -> ADD;
            case TONG -> TONG;
            case NONE -> {
                if (parameterCount == 2) {
                    int first = parameters.get(0);
                    int second = parameters.get(1);
                    yield Math.abs(first - second) == 2 ? POSITIVE : ADD;
                }
                if (parameterCount == 3) yield TONG;
                throw new IllegalArgumentException("无玩法需要 2 或 3 个参数");
            }
            case ODD_EVEN -> ODD_EVEN;
            case BIG_SMALL -> BIG_SMALL;
            case SPECIAL -> SPECIAL_NUMBER;
        };
    }

    public static String ruleKey(PlayType playType, List<Integer> parameters) {
        List<Integer> values = parameters == null ? List.of() : parameters;
        if (playType == PlayType.ANGLE) return encode(playType.name(), values.stream().sorted().toList());
        if (playType == PlayType.CAR) {
            List<Integer> sorted = values.stream().sorted().toList();
            int group = switch (sorted.toString()) {
                case "[1, 2, 4]" -> 1;
                case "[1, 2, 3]" -> 2;
                case "[2, 3, 4]" -> 3;
                case "[1, 3, 4]" -> 4;
                default -> throw new IllegalArgumentException("车玩法排列无效");
            };
            return encode("CAR_GROUP", List.of(group));
        }
        if (playType == PlayType.NONE && values.size() == 2) {
            int first = values.get(0);
            int second = values.get(1);
            if (Math.abs(first - second) == 2) return encode("POSITIVE", List.of(first));
            List<Integer> draws = List.of(1, 2, 3, 4).stream()
                    .filter(value -> value != first && value != second).toList();
            return encode("ADD", java.util.stream.Stream.concat(java.util.stream.Stream.of(first), draws.stream()).toList());
        }
        if (playType == PlayType.ADD) {
            return encode("ADD", java.util.stream.Stream.concat(java.util.stream.Stream.of(values.get(0)),
                    values.subList(1, 3).stream().sorted()).toList());
        }
        if (playType == PlayType.TONG && values.size() == 3) {
            return encode("TONG", java.util.stream.Stream.concat(java.util.stream.Stream.of(values.get(0)),
                    values.subList(1, 3).stream().sorted()).toList());
        }
        if (playType == PlayType.NONE && values.size() == 3) {
            return encode("TONG", java.util.stream.Stream.concat(java.util.stream.Stream.of(values.get(2)),
                    values.subList(0, 2).stream().sorted()).toList());
        }
        String type = playType.name();
        // Keep directional rules ordered after canonicalizing only BY220-equivalent aliases.
        return encode(type, values);
    }

    public static String carGroupKey(int group) {
        if (group < 1 || group > 4) throw new IllegalArgumentException("车组必须是 1 到 4");
        return encode("CAR_GROUP", List.of(group));
    }

    private static String encode(String type, List<Integer> values) {
        return type + ":" + values.size() + ":" + String.join(",", values.stream()
                .map(String::valueOf).toList());
    }
}
