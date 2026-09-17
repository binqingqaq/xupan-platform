package com.xupan.server.game.web;

import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletOperationResult;
import com.xupan.server.game.domain.WalletStatistics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record WalletResponse(
        long accountId,
        long userId,
        String userCode,
        String displayName,
        BigDecimal balance,
        String status,
        BigDecimal balanceBefore,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String operationType,
        Long ledgerId,
        Instant operatedAt,
        WalletStatistics statistics,
        List<LedgerEntryResponse> ledger
) {

    public static WalletResponse summary(VirtualWallet wallet, WalletStatistics statistics,
                                         List<WalletLedgerEntry> ledger) {
        return new WalletResponse(wallet.accountId(), wallet.userId(), wallet.userCode(), wallet.displayName(),
                wallet.balance(), wallet.status(), null, null, null, null, null, null,
                statistics,
                ledger == null ? List.of() : ledger.stream().map(LedgerEntryResponse::from).toList());
    }

    public static WalletResponse operation(WalletOperationResult result) {
        WalletLedgerEntry entry = result.ledger();
        return new WalletResponse(result.wallet().accountId(), result.wallet().userId(), result.wallet().userCode(),
                result.wallet().displayName(), result.wallet().balance(), result.wallet().status(),
                entry.balanceBefore(), entry.amount(), entry.balanceAfter(), entry.operationType().name(),
                entry.id(), entry.createdAt(), null, List.of());
    }

    public record LedgerEntryResponse(
            long id,
            long accountId,
            long userId,
            String operationType,
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
        static LedgerEntryResponse from(WalletLedgerEntry entry) {
            return new LedgerEntryResponse(entry.id(), entry.accountId(), entry.userId(),
                    entry.operationType().name(), entry.amount(), entry.balanceBefore(), entry.balanceAfter(),
                    entry.operatorUserId(), entry.operatorName(), entry.idempotencyKey(), entry.relatedBetId(),
                    entry.issueNumber(), entry.reason(), entry.createdAt());
        }
    }
}
