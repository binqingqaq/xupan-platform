package com.xupan.server.center.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Super-admin endpoints for the internal center upstream accounts assigned to agent groups. */
@RestController
@RequestMapping("/api/admin/center/accounts")
public class CenterUpstreamAccountAdminController {

    private final CenterUpstreamAccountService service;

    public CenterUpstreamAccountAdminController(CenterUpstreamAccountService service) {
        this.service = service;
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

    @PostMapping
    public CenterUpstreamAccountService.CreatedAccount create(
            Authentication authentication, @Valid @RequestBody CreateAccountRequest request) {
        return service.createAccount(request.groupId(), request.username(), request.password(),
                request.initialQuota(), request.expiresAt(), operator(authentication));
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

    public record AccountStatusRequest(@NotBlank String status,
                                       @NotBlank @Size(max = 255) String reason) {
    }

    public record AccountExpiryRequest(@NotNull Instant expiresAt,
                                       @NotBlank @Size(max = 255) String reason) {
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
