package com.xupan.server.game.domain;

public record BallResult(int number, int fan, String parity, String size) {

    public static BallResult fromNumber(int number) {
        return fromNumber(number, "", 1);
    }

    public static BallResult fromNumber(int number, String gameCode, int ruleVersion) {
        if (number < 1 || number > DrawRule.maxNumber(gameCode, ruleVersion)) {
            throw new IllegalArgumentException("开奖号码超出适用规则的范围");
        }
        int fan = number % 4 == 0 ? 4 : number % 4;
        String parity = fan % 2 == 1 ? "ODD" : "EVEN";
        String size = DrawRule.isBig(number, gameCode, ruleVersion) ? "BIG" : "SMALL";
        return new BallResult(number, fan, parity, size);
    }
}
