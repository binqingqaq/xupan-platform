package com.xupan.server.game.domain;

import java.util.Objects;

public record WalletOperationResult(
        VirtualWallet wallet,
        WalletLedgerEntry ledger
) {

    public WalletOperationResult {
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(ledger, "ledger");
    }
}
