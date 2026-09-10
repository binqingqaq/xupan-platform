package com.xupan.server.game.web;

import com.xupan.server.game.repository.DemoAccountRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public DemoAccountController(DemoAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping("/account")
    public AccountView account() {
        return AccountView.from(accountRepository.findByCode(DemoAccountRepository.DEFAULT_USER_CODE));
    }

    @GetMapping("/admin/accounts")
    public List<AccountView> accounts() {
        return accountRepository.findAll().stream().map(AccountView::from).toList();
    }

    @GetMapping("/admin/accounts/{userCode}/ledger")
    public List<LedgerView> ledger(@PathVariable String userCode) {
        return accountRepository.findLedger(userCode).stream().map(LedgerView::from).toList();
    }

    @PostMapping("/admin/accounts/{userCode}/balance")
    public AccountView adjust(@PathVariable String userCode,
                              @Valid @RequestBody BalanceAdjustmentRequest request) {
        if (request.amount().signum() == 0) {
            throw new IllegalArgumentException("余额调整金额不能为 0");
        }
        return AccountView.from(accountRepository.adjust(userCode, request.amount(),
                "ADMIN_ADJUST", request.reason(), "DEMO-ADMIN"));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBusinessError(RuntimeException exception) {
        return Map.of("message", exception.getMessage());
    }

    public record AccountView(long id, String userCode, String displayName,
                              BigDecimal balance, String status) {
        private static AccountView from(DemoAccountRepository.AccountRecord account) {
            return new AccountView(account.id(), account.userCode(), account.displayName(),
                    account.balance(), account.status());
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
