package com.xupan.server.game.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

public record WalletLedgerEntry(
        long id,
        long accountId,
        long userId,
        WalletOperationType operationType,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        Long operatorUserId,
        String operatorName,
        String idempotencyKey,
        Long relatedBetId,
        String issueNumber,
        String reason,
        Instant createdAt
) {

    public WalletLedgerEntry {
        if (id <= 0 || accountId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("流水身份必须为正数");
        }
        operationType = Objects.requireNonNull(operationType, "operationType");
        amount = money(amount, "amount");
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("流水金额不能为零");
        }
        balanceBefore = money(balanceBefore, "balanceBefore");
        balanceAfter = money(balanceAfter, "balanceAfter");
        operatorName = Objects.requireNonNull(operatorName, "operatorName");
        reason = Objects.requireNonNull(reason, "reason");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null || value.scale() > 2) {
            throw new IllegalArgumentException(field + " 必须为最多两位小数");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
