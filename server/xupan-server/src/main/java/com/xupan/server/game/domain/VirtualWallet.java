package com.xupan.server.game.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record VirtualWallet(
        long accountId,
        long userId,
        String userCode,
        String displayName,
        BigDecimal balance,
        String status
) {

    public VirtualWallet {
        if (accountId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("钱包身份必须为正数");
        }
        userCode = Objects.requireNonNull(userCode, "userCode");
        displayName = Objects.requireNonNull(displayName, "displayName");
        balance = money(balance, "balance");
        status = Objects.requireNonNull(status, "status");
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null || value.scale() > 2) {
            throw new IllegalArgumentException(field + " 必须为最多两位小数");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
