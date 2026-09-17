package com.xupan.server.game.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.service.VirtualWalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.List;

@RestController
@RequestMapping("/api")
public class VirtualWalletController {

    private final VirtualWalletService walletService;

    public VirtualWalletController(VirtualWalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/me/wallet")
    public WalletResponse mine(Authentication authentication) {
        AuthenticatedUser user = principal(authentication);
        return WalletResponse.summary(walletService.getForCurrentUser(user.getUserId()),
                walletService.statistics(user.getUserId()),
                walletService.ledger(user.getUserId(), 20));
    }

    @GetMapping("/admin/users/{userId}/wallet")
    public WalletResponse adminWallet(@PathVariable long userId) {
        return WalletResponse.summary(walletService.getForAdmin(userId), walletService.statistics(userId),
                walletService.ledger(userId, 20));
    }

    @GetMapping("/admin/users/{userId}/wallet/ledger")
    public List<WalletResponse.LedgerEntryResponse> adminLedger(@PathVariable long userId,
                                                                @RequestParam(defaultValue = "50") int limit) {
        return walletService.ledger(userId, limit).stream()
                .map(WalletResponse.LedgerEntryResponse::from).toList();
    }

    @PostMapping("/admin/users/{userId}/wallet/grants")
    public WalletResponse grant(Authentication authentication, @PathVariable long userId,
                                @Valid @RequestBody WalletGrantRequest request) {
        AuthenticatedUser operator = principal(authentication);
        return WalletResponse.operation(walletService.grant(operator.getUserId(), userId,
                request.amount(), request.reason(), request.idempotencyKey()));
    }

    @PostMapping("/admin/users/{userId}/wallet/adjustments")
    public WalletResponse adjust(Authentication authentication, @PathVariable long userId,
                                 @Valid @RequestBody WalletAdjustmentRequest request) {
        AuthenticatedUser operator = principal(authentication);
        return WalletResponse.operation(walletService.adjust(operator.getUserId(), userId,
                request.amount(), request.reason(), request.idempotencyKey()));
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }
}
