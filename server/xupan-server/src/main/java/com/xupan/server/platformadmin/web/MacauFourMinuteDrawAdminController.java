package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.MacauFourMinuteDrawService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/macau-four-minute/draws")
public class MacauFourMinuteDrawAdminController {

    private final MacauFourMinuteDrawService service;

    public MacauFourMinuteDrawAdminController(MacauFourMinuteDrawService service) {
        this.service = service;
    }

    @GetMapping
    public MacauFourMinuteDrawService.Page list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(page, pageSize, operator(authentication).getUserId());
    }

    @PutMapping("/{issueNumber}/preset")
    public MacauFourMinuteDrawService.DrawView savePreset(
            Authentication authentication,
            @PathVariable long issueNumber,
            @Valid @RequestBody PresetRequest request) {
        return service.savePreset(issueNumber, request.numbers(), operator(authentication).getUserId());
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record PresetRequest(@NotNull List<Integer> numbers) {
    }
}
