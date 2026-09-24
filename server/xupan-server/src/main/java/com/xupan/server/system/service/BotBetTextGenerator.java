package com.xupan.server.system.service;

import com.xupan.server.game.domain.PlayType;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 为托生成合法且能被 {@code BetTextParser} 解析的下注文本。
 *
 * <p>参数完全随机，但始终落在各玩法的合法范围内：番值只能是 1-4，同玩法参数不重复，
 * 特号码在 01-20 之间。
 */
public final class BotBetTextGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private BotBetTextGenerator() {
    }

    public static String randomBet(PlayType playType, BigDecimal amount) {
        return format(playType, randomParameters(playType), amount);
    }

    public static List<Integer> randomParameters(PlayType playType) {
        return switch (playType) {
            case FAN, POSITIVE, CAR -> List.of(1 + RANDOM.nextInt(4));
            case ANGLE, STRICT, NONE -> distinctFans(2);
            case ADD, TONG -> distinctFans(3);
            case ODD_EVEN -> List.of(1 + RANDOM.nextInt(2));
            case BIG_SMALL -> List.of(1 + RANDOM.nextInt(2));
            case SPECIAL -> List.of(1 + RANDOM.nextInt(20));
        };
    }

    public static String format(PlayType playType, List<Integer> parameters, BigDecimal amount) {
        String value = amount.stripTrailingZeros().toPlainString();
        return switch (playType) {
            case FAN -> parameters.get(0) + "番" + value;
            case ANGLE -> parameters.get(0) + String.valueOf(parameters.get(1)) + "角" + value;
            case CAR -> parameters.get(0) + "车" + value;
            case STRICT -> parameters.get(0) + "念" + parameters.get(1) + "/" + value;
            case ADD -> parameters.get(0) + "加" + parameters.get(1) + parameters.get(2) + "/" + value;
            case POSITIVE -> parameters.get(0) + "正" + value;
            case TONG -> parameters.get(0) + "通" + parameters.get(1) + parameters.get(2) + "/" + value;
            case NONE -> parameters.get(0) + "无" + parameters.get(1) + "/" + value;
            case ODD_EVEN -> (parameters.get(0) == 1 ? "单" : "双") + value;
            case BIG_SMALL -> (parameters.get(0) == 1 ? "大" : "小") + value;
            case SPECIAL -> String.format("%02d", parameters.get(0)) + "特" + value;
        };
    }

    /** 从 1-4 中取不重复的番值，保证解析器的去重约束成立。 */
    private static List<Integer> distinctFans(int count) {
        List<Integer> pool = new ArrayList<>(List.of(1, 2, 3, 4));
        List<Integer> picked = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            picked.add(pool.remove(RANDOM.nextInt(pool.size())));
        }
        return List.copyOf(picked);
    }
}
