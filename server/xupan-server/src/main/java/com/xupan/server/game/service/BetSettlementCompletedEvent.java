package com.xupan.server.game.service;

import com.xupan.server.game.domain.SettlementStatus;

import java.math.BigDecimal;

/** Published after a bet has been durably settled and any payout has been applied. */
public record BetSettlementCompletedEvent(
        long betId,
        long accountId,
        String issueNumber,
        SettlementStatus status,
        BigDecimal stake,
        BigDecimal netProfit,
        BigDecimal returnAmount
) {
    public BetSettlementCompletedEvent {
        if (betId <= 0 || accountId <= 0 || issueNumber == null || issueNumber.isBlank()
                || status == null || stake == null || netProfit == null || returnAmount == null) {
            throw new IllegalArgumentException("结算反馈事件参数无效");
        }
    }
}
