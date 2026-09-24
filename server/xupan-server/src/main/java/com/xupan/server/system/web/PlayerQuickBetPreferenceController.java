package com.xupan.server.system.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.system.service.PlayerQuickBetPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/me/quick-bet-preferences")
public class PlayerQuickBetPreferenceController {

    private final PlayerQuickBetPreferenceService service;

    public PlayerQuickBetPreferenceController(PlayerQuickBetPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public PreferenceResponse get(Authentication authentication) {
        return PreferenceResponse.from(service.getAmounts(userId(authentication)));
    }

    @PutMapping
    public PreferenceResponse update(Authentication authentication,
                                     @RequestBody PreferenceRequest request) {
        List<BigDecimal> amounts = request == null ? null : request.amounts();
        return PreferenceResponse.from(service.saveAmounts(userId(authentication), amounts));
    }

    private static long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user.getUserId();
    }

    public record PreferenceRequest(List<BigDecimal> amounts) {
    }

    public record PreferenceResponse(List<BigDecimal> amounts) {
        static PreferenceResponse from(List<BigDecimal> amounts) {
            return new PreferenceResponse(amounts);
        }
    }
}
