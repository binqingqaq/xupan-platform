package com.xupan.server.system.web;

import com.xupan.server.system.service.UserAdminService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UserDetailResponse(long id, String username, String displayName, String status,
                                 List<String> roles, Instant createdAt, Instant lastLoginAt,
                                 WalletResponse wallet) {
    public static UserDetailResponse from(UserAdminService.UserDetail detail) {
        UserAdminService.WalletSummary wallet = detail.wallet();
        return new UserDetailResponse(detail.id(), detail.username(), detail.displayName(), detail.status(),
                detail.roles(), detail.createdAt(), detail.lastLoginAt(),
                wallet == null ? null : new WalletResponse(wallet.accountId(), wallet.balance(), wallet.status()));
    }

    public record WalletResponse(long accountId, BigDecimal balance, String status) {
    }
}
