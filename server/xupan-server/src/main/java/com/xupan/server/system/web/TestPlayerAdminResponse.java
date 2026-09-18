package com.xupan.server.system.web;

import com.xupan.server.game.repository.DemoAccountRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TestPlayerAdminResponse(
        long id,
        long accountId,
        long userId,
        String username,
        String userCode,
        String displayName,
        String avatarKey,
        String identityType,
        String status,
        String userStatus,
        boolean isTestPlayer,
        BigDecimal balance,
        Instant createdAt,
        Instant updatedAt,
        List<LedgerResponse> ledger
) {

    static TestPlayerAdminResponse from(DemoAccountRepository.TestPlayerRecord player,
                                        List<DemoAccountRepository.LedgerRecord> ledger) {
        return new TestPlayerAdminResponse(player.id(), player.id(), player.userId(), player.userCode(), player.userCode(),
                player.displayName(), player.avatarKey(), player.identityType(), player.status(),
                player.userStatus(), true, player.balance(), player.createdAt(), player.updatedAt(),
                ledger == null ? List.of() : ledger.stream().map(LedgerResponse::from).toList());
    }

    public record LedgerResponse(long id, String userCode, String operationType,
                                 BigDecimal amount, BigDecimal balanceBefore,
                                 BigDecimal balanceAfter, String reason,
                                 String operatorName, Instant createdAt) {
        private static LedgerResponse from(DemoAccountRepository.LedgerRecord ledger) {
            return new LedgerResponse(ledger.id(), ledger.userCode(), ledger.operationType(),
                    ledger.amount(), ledger.balanceBefore(), ledger.balanceAfter(), ledger.reason(),
                    ledger.operatorName(), ledger.createdAt());
        }
    }
}
