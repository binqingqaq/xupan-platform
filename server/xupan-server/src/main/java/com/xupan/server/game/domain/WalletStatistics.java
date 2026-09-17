package com.xupan.server.game.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Aggregates persisted game bets while the wallet ledger remains the balance audit trail. */
public record WalletStatistics(
        long totalBetCount,
        long settledBetCount,
        long pendingBetCount,
        BigDecimal totalStake,
        BigDecimal settledStake,
        BigDecimal pendingStake,
        BigDecimal netProfit
) {

    public WalletStatistics {
        if (totalBetCount < 0 || settledBetCount < 0 || pendingBetCount < 0) {
            throw new IllegalArgumentException("下注统计数量不能为负数");
        }
        totalStake = money(totalStake);
        settledStake = money(settledStake);
        pendingStake = money(pendingStake);
        netProfit = money(netProfit);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
