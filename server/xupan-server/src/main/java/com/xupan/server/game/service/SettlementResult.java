package com.xupan.server.game.service;

import com.xupan.server.game.domain.SettlementStatus;

import java.math.BigDecimal;

public record SettlementResult(
        SettlementStatus status,
        BigDecimal stake,
        BigDecimal odds,
        BigDecimal netProfit,
        String explanation
) {
}
