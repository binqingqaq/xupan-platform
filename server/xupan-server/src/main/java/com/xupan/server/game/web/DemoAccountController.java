package com.xupan.server.game.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.game.repository.DemoAccountRepository;
import com.xupan.server.web.BusinessException;
import jakarta.validation.Valid;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.service.VirtualWalletService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/demo")
public class DemoAccountController {

    private final DemoAccountRepository accountRepository;
    private final VirtualWalletService walletService;

    public DemoAccountController(DemoAccountRepository accountRepository, VirtualWalletService walletService) {
        this.accountRepository = accountRepository;
        this.walletService = walletService;
    }

    @GetMapping("/account")
    public AccountView account(Authentication authentication) {
        return AccountView.from(walletService.getForCurrentUser(authenticatedUser(authentication).getUserId()));
    }

    @GetMapping("/admin/accounts")
    public List<AccountView> accounts() {
        return accountRepository.findAll().stream().map(account -> AccountView.from(account)).toList();
    }

    @GetMapping("/admin/accounts/{userCode}/ledger")
    public List<LedgerView> ledger(@PathVariable String userCode) {
        return accountRepository.findLedger(userCode).stream().map(LedgerView::from).toList();
    }

    @PostMapping("/admin/accounts/{userCode}/balance")
    @Deprecated
    public AccountView adjust(@PathVariable String userCode,
                              @Valid @RequestBody BalanceAdjustmentRequest request) {
        throw BusinessException.conflict("WALLET_LEGACY_ENDPOINT_DISABLED",
                "旧余额调整接口已停用，请使用虚拟钱包管理员接口");
    }

    private static AuthenticatedUser authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("GAME_BET_USER_REQUIRED");
        }
        return user;
    }

    public record AccountView(long id, String userCode, String displayName,
                              BigDecimal balance, String status) {
        private static AccountView from(DemoAccountRepository.AccountRecord account) {
            return new AccountView(account.id(), account.userCode(), account.displayName(),
                    account.balance(), account.status());
        }

        private static AccountView from(VirtualWallet wallet) {
            return new AccountView(wallet.accountId(), wallet.userCode(), wallet.displayName(),
                    wallet.balance(), wallet.status());
        }
    }

    public record LedgerView(long id, String userCode, String operationType,
                             BigDecimal amount, BigDecimal balanceBefore,
                             BigDecimal balanceAfter, String reason,
                             String operatorName, Instant createdAt) {
        private static LedgerView from(DemoAccountRepository.LedgerRecord ledger) {
            return new LedgerView(ledger.id(), ledger.userCode(), ledger.operationType(), ledger.amount(),
                    ledger.balanceBefore(), ledger.balanceAfter(), ledger.reason(), ledger.operatorName(),
                    ledger.createdAt());
        }
    }
}
