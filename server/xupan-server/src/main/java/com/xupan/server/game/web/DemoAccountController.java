package com.xupan.server.game.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.game.repository.DemoAccountRepository;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.service.VirtualWalletService;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBusinessError(RuntimeException exception) {
        return Map.of("message", exception.getMessage());
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
