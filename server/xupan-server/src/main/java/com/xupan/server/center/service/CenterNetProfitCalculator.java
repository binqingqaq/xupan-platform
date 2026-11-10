package com.xupan.server.center.service;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.service.SettlementResult;
import com.xupan.server.game.service.SettlementService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** Applies the same settlement snapshot and center-side sign convention as the center settlement flow. */
@Component
public class CenterNetProfitCalculator {

    private final SettlementService settlementService;

    public CenterNetProfitCalculator(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    public List<Integer> parseParameters(String centerPlayText) {
        return CenterSettlementService.parseParameters(centerPlayText);
    }

    public BigDecimal forOrder(String gameCode, String playTypeName, String playText,
                               BigDecimal amount, BigDecimal odds, BigDecimal machineOddsRate,
                               int drawNumber, int drawRuleVersion) {
        if (gameCode == null || amount == null || odds == null
                || playTypeName == null || playText == null) {
            throw new IllegalArgumentException("中心订单结算快照不完整");
        }
        final SettlementStatus playerStatus;
        final BigDecimal playerNetProfit;
        try {
            var playType = com.xupan.server.game.domain.PlayType.valueOf(
                    playTypeName.trim().toUpperCase(Locale.ROOT));
            List<Integer> parameters = CenterSettlementService.parseParameters(playText);
            SettlementResult outcome = settlementService.settle(playType, parameters, amount,
                    CenterSettlementService.normalizeOdds(odds), null,
                    machineOddsRate, BallResult.fromNumber(drawNumber, gameCode, drawRuleVersion));
            playerStatus = outcome.status();
            playerNetProfit = outcome.netProfit();
        } catch (IllegalArgumentException invalidSnapshot) {
            throw new IllegalArgumentException("中心订单无法按正式结算规则还原", invalidSnapshot);
        }

        return switch (playerStatus) {
            case WIN -> playerNetProfit.max(BigDecimal.ZERO).negate().setScale(2);
            case LOSE -> amount.setScale(2);
            case DRAW -> BigDecimal.ZERO.setScale(2);
            case PENDING, CANCELED -> throw new IllegalArgumentException("已接受中心订单已存在非终态结算结果");
        };
    }
}
