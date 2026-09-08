package com.xupan.server.game.domain;

public record BallResult(int number, int fan, String parity, String size) {

    public static BallResult fromNumber(int number) {
        if (number < 1 || number > 20) {
            throw new IllegalArgumentException("号码必须在 1 到 20 之间");
        }
        int fan = number % 4 == 0 ? 4 : number % 4;
        String parity = fan % 2 == 1 ? "ODD" : "EVEN";
        String size = number >= 11 ? "BIG" : "SMALL";
        return new BallResult(number, fan, parity, size);
    }
}
