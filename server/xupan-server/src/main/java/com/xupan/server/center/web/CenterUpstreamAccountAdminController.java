package com.xupan.server.center.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.service.CenterAgentService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Super-admin endpoints for the internal center upstream accounts assigned to agent groups. */
@RestController
@RequestMapping("/api/admin/center/accounts")
public class CenterUpstreamAccountAdminController {

    private final CenterUpstreamAccountService service;
    private final CenterAgentService agentService;

    public CenterUpstreamAccountAdminController(CenterUpstreamAccountService service,
                                                CenterAgentService agentService) {
        this.service = service;
        this.agentService = agentService;
    }

    @GetMapping
    public List<CenterUpstreamAccountService.AccountView> list(Authentication authentication) {
        return service.listAccounts(operator(authentication));
    }

    @GetMapping("/{id}/sessions")
    public List<CenterUpstreamAccountService.MachineSessionView> sessions(
            Authentication authentication, @PathVariable long id) {
        return service.machineSessions(id, operator(authentication));
    }

    @GetMapping("/{id}/ledger-groups")
    public CenterLedgerGroupPage ledgerGroups(Authentication authentication, @PathVariable long id,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        long operator = operator(authentication);
        service.requireManagedAccount(id, operator);
        CenterAgentService.LedgerGroupPage result = agentService.ledgerGroups(id, page, pageSize, operator);
        return new CenterLedgerGroupPage(result.items().stream().map(CenterLedgerGroup::from).toList(),
                result.page(), result.pageSize(), result.total(), result.balanced());
    }

    @GetMapping("/{id}/ledger-groups/{groupType}/{referenceId}/entries")
    public List<CenterLedgerEntryView> ledgerGroupEntries(Authentication authentication, @PathVariable long id,
                                                          @PathVariable String groupType,
                                                          @PathVariable String referenceId) {
        long operator = operator(authentication);
        service.requireManagedAccount(id, operator);
        return agentService.ledgerGroupEntries(id, groupType, referenceId, operator).stream()
                .map(CenterLedgerEntryView::from).toList();
    }

    @PostMapping
    public CenterUpstreamAccountService.CreatedAccount create(
            Authentication authentication, @Valid @RequestBody CreateAccountRequest request) {
        return service.createAccount(request.groupId(), request.username(), request.password(),
                request.initialQuota(), request.expiresAt(), operator(authentication));
    }

    @PostMapping("/generated")
    public CenterUpstreamAccountService.GeneratedAccount createGenerated(
            Authentication authentication, @Valid @RequestBody CreateGeneratedAccountRequest request) {
        return service.createGeneratedAccount(request.groupId(), request.username(), request.password(),
                request.maxSingleAmount(), request.maxIssueExposure(), request.initialQuota(),
                request.expiresAt(), operator(authentication));
    }

    @PatchMapping("/{id}/status")
    public CenterUpstreamAccountService.AccountView changeStatus(
            Authentication authentication, @PathVariable long id,
            @Valid @RequestBody AccountStatusRequest request) {
        return service.changeStatus(id, request.status(), request.reason(), operator(authentication));
    }

    @PatchMapping("/{id}/expiry")
    public CenterUpstreamAccountService.AccountView renew(
            Authentication authentication, @PathVariable long id,
            @Valid @RequestBody AccountExpiryRequest request) {
        return service.renew(id, request.expiresAt(), request.reason(), operator(authentication));
    }

    @PostMapping("/{id}/password")
    public CenterUpstreamAccountService.AccountView resetPassword(
            Authentication authentication, @PathVariable long id,
            @Valid @RequestBody ResetPasswordRequest request) {
        return service.resetPassword(id, request.newPassword(), request.reason(), operator(authentication));
    }

    @PatchMapping("/{id}")
    public CenterUpstreamAccountService.AccountView edit(
            Authentication authentication, @PathVariable long id,
            @Valid @RequestBody EditAccountRequest request) {
        return service.editAccount(id, request.expiresAt(), request.newPassword(), request.additionalQuota(),
                request.maxSingleAmount(), request.maxIssueExposure(), operator(authentication));
    }

    @DeleteMapping("/{id}")
    public void delete(Authentication authentication, @PathVariable long id,
                       @Valid @RequestBody DeleteAccountRequest request) {
        service.deleteAccount(id, request.reason(), operator(authentication));
    }

    private static long operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user.getUserId();
    }

    public record CreateAccountRequest(@NotNull @Positive Long groupId,
                                       @NotBlank @Size(max = 64) String username,
                                       String password,
                                       BigDecimal initialQuota,
                                       Instant expiresAt) {
        @Override
        public String toString() {
            return "CreateAccountRequest[groupId=" + groupId + ", username=" + username
                    + ", password=[REDACTED], initialQuota=" + initialQuota + ", expiresAt=" + expiresAt + "]";
        }
    }

    public record CreateGeneratedAccountRequest(@NotNull @Positive Long groupId,
                                                @Size(max = 64) String username,
                                                @Size(max = 72) String password,
                                                @Positive BigDecimal maxSingleAmount,
                                                @Positive BigDecimal maxIssueExposure,
                                                BigDecimal initialQuota, Instant expiresAt) {
        @Override
        public String toString() {
            return "CreateGeneratedAccountRequest[groupId=" + groupId + ", username=" + username
                    + ", password=[REDACTED], initialQuota=" + initialQuota + ", expiresAt=" + expiresAt + "]";
        }
    }

    public record CenterLedgerGroupPage(List<CenterLedgerGroup> items, int page, int pageSize,
                                        long total, boolean balanced) {
    }

    public record CenterLedgerGroup(String groupType, String groupReferenceId, Instant latestEntryAt,
                                    long entryCount, Long centerOrderId, String agentOrderNo,
                                    Long localBetId, String gameCode, String issueNumber, String playType,
                                    String playText, BigDecimal orderAmount, String orderStatus,
                                    String settleResult, BigDecimal settleAmount, String bucket,
                                    String entryType, String direction, BigDecimal amount,
                                    BigDecimal balanceBefore, BigDecimal balanceAfter, String reason,
                                    String sourceReferenceType, String sourceReferenceId) {
        static CenterLedgerGroup from(CenterAgentRepository.LedgerGroupRow row) {
            return new CenterLedgerGroup(row.groupType(), row.groupReferenceId(), row.latestEntryAt(),
                    row.entryCount(), row.centerOrderId(), row.agentOrderNo(), row.localBetId(),
                    row.gameCode(), row.issueNumber(), row.playType(), row.playText(), row.orderAmount(),
                    row.orderStatus(), row.settleResult(), row.settleAmount(), row.bucket(), row.entryType(),
                    row.direction(), row.amount(), row.balanceBefore(), row.balanceAfter(), row.reason(),
                    row.sourceReferenceType(), row.sourceReferenceId());
        }
    }

    public record CenterLedgerEntryView(long id, String bucket, String entryType, String direction,
                                        BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
                                        String reason, String referenceType, String referenceId,
                                        String idempotencyKey, Long operatorUserId, Instant createdAt) {
        static CenterLedgerEntryView from(CenterAgentRepository.LedgerRow row) {
            return new CenterLedgerEntryView(row.id(), row.bucket(), row.entryType(), row.direction(),
                    row.amount(), row.balanceBefore(), row.balanceAfter(), row.reason(), row.referenceType(),
                    row.referenceId(), row.idempotencyKey(), row.operatorUserId(), row.createdAt());
        }
    }

    public record AccountStatusRequest(@NotBlank String status,
                                       @NotBlank @Size(max = 255) String reason) {
    }

    public record AccountExpiryRequest(@NotNull Instant expiresAt,
                                       @NotBlank @Size(max = 255) String reason) {
    }

    public record EditAccountRequest(@NotNull Instant expiresAt,
                                     @Size(max = 72) String newPassword,
                                     @NotNull @PositiveOrZero BigDecimal additionalQuota,
                                     @NotNull @Positive BigDecimal maxSingleAmount,
                                     @NotNull @Positive BigDecimal maxIssueExposure) {
        @Override
        public String toString() {
            return "EditAccountRequest[expiresAt=" + expiresAt + ", newPassword=[REDACTED]"
                    + ", additionalQuota=" + additionalQuota + ", maxSingleAmount=" + maxSingleAmount
                    + ", maxIssueExposure=" + maxIssueExposure + "]";
        }
    }

    public record ResetPasswordRequest(@NotBlank String newPassword,
                                       @NotBlank @Size(max = 255) String reason) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[newPassword=[REDACTED]]";
        }
    }

    public record DeleteAccountRequest(@NotBlank @Size(max = 255) String reason) {
    }
}
