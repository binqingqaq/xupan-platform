package com.xupan.server.game.domain;

import java.util.HashSet;
import java.util.List;

public final class DrawRule {
    public static final int LEGACY_VERSION = 1;
    public static final int AU8_FORTY_NUMBER_VERSION = 2;
    public static final int AU8_BALL_COUNT = 7;

    private DrawRule() {}

    public static int currentVersion(String gameCode) {
        return "AU8".equals(gameCode) ? AU8_FORTY_NUMBER_VERSION : LEGACY_VERSION;
    }

    public static int maxNumber(String gameCode, int version) {
        return "AU8".equals(gameCode) && version >= AU8_FORTY_NUMBER_VERSION ? 40 : 20;
    }

    public static boolean requiresDistinctNumbers(String gameCode, int version) {
        return "AU8".equals(gameCode) && version >= AU8_FORTY_NUMBER_VERSION;
    }

    public static void validateNumbers(String gameCode, int version, int expectedCount, List<Integer> numbers) {
        if (numbers == null || numbers.size() != expectedCount
                || numbers.stream().anyMatch(number -> number == null || number < 1
                || number > maxNumber(gameCode, version))) {
            throw new IllegalArgumentException("开奖结果数量或号码范围无效");
        }
        if (requiresDistinctNumbers(gameCode, version)
                && new HashSet<>(numbers).size() != expectedCount) {
            throw new IllegalArgumentException("AU8 同一期七个开奖号码不能重复");
        }
    }

    public static boolean isBig(int number, String gameCode, int version) {
        return number >= ("AU8".equals(gameCode) && version >= AU8_FORTY_NUMBER_VERSION ? 21 : 11);
    }
}
