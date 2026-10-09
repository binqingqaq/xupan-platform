package com.xupan.server.system.service;

import com.xupan.server.game.domain.PlayType;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 为托生成合法且能被 {@code BetTextParser} 解析的下注文本。
 *
 * <p>参数完全随机，但始终落在各玩法的合法范围内：番值只能是 1-4，同玩法参数不重复，
 * 特号码在 01-20 之间。
 */
public final class BotBetTextGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int POOL_TOTAL = 800;
    private static final Map<String, Integer> APPROVED_WEIGHTS = Map.ofEntries(
            Map.entry("SPECIAL", 216), Map.entry("NONE", 216), Map.entry("CAR", 72),
            Map.entry("STRICT", 72), Map.entry("ANGLE", 36), Map.entry("FAN", 36),
            Map.entry("POSITIVE", 36), Map.entry("ODD", 9), Map.entry("EVEN", 9),
            Map.entry("BIG", 9), Map.entry("SMALL", 9), Map.entry("ADD", 40), Map.entry("TONG", 40));

    public static Map<String, Integer> approvedWeights() { return APPROVED_WEIGHTS; }
    private static final String[] BY220_ANGLE = {"12", "23", "14", "34"};
    private static final String[] BY220_CAR = {"123", "234", "134", "124"};
    private static final String[] BY220_STRICT = {"1严2", "1严4", "2严1", "2严3", "3严2", "3严4", "4严1", "4严3"};
    private static final String[] BY220_NONE = {
            "1无2", "1无3", "1无4", "2无1", "2无3", "2无4", "3无1", "3无2", "3无4", "4无1", "4无2", "4无3",
            "12无3", "12无4", "13无2", "13无4", "14无2", "14无3", "23无1", "23无4", "24无1", "24无3", "34无1", "34无2"
    };

    private BotBetTextGenerator() {
    }

    public static String randomBet(PlayType playType, BigDecimal amount) {
        return format(playType, randomParameters(playType), amount);
    }

    /** Single 13-leaf bot pool: approved probabilities total 800 units. */
    public static String by220AutomaticBet(BigDecimal amount) {
        int pick = RANDOM.nextInt(POOL_TOTAL);
        if (pick < 216) return format(PlayType.SPECIAL, List.of(1 + RANDOM.nextInt(20)), amount);
        pick -= 216;
        if (pick < 216) return formatNone(amount);
        pick -= 216;
        if (pick < 72) return BY220_CAR[RANDOM.nextInt(BY220_CAR.length)] + "/" + amountText(amount);
        pick -= 72;
        if (pick < 72) return BY220_STRICT[RANDOM.nextInt(BY220_STRICT.length)] + "/" + amountText(amount);
        pick -= 72;
        if (pick < 36) return BY220_ANGLE[RANDOM.nextInt(BY220_ANGLE.length)] + "角" + amountText(amount);
        pick -= 36;
        if (pick < 36) return (1 + RANDOM.nextInt(4)) + "番" + amountText(amount);
        pick -= 36;
        if (pick < 36) return (1 + RANDOM.nextInt(4)) + "正" + amountText(amount);
        pick -= 36;
        if (pick < 9) return "单" + amountText(amount);
        pick -= 9;
        if (pick < 9) return "双" + amountText(amount);
        pick -= 9;
        if (pick < 9) return "大" + amountText(amount);
        pick -= 9;
        if (pick < 9) return "小" + amountText(amount);
        pick -= 9;
        if (pick < 40) return format(PlayType.ADD, randomParameters(PlayType.ADD), amount);
        return format(PlayType.TONG, randomParameters(PlayType.TONG), amount);
    }

    private static String formatNone(BigDecimal amount) {
        return BY220_NONE[RANDOM.nextInt(BY220_NONE.length)] + "/" + amountText(amount);
    }

    private static String amountText(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
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
