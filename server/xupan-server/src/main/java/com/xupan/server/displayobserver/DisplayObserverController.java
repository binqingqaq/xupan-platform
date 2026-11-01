package com.xupan.server.displayobserver;

import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.TokenService;
import com.xupan.server.auth.web.AuthController;
import com.xupan.server.auth.web.CurrentUserResponse;
import com.xupan.server.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DisplayObserverController {

    private final DisplayObserverBindingService bindingService;
    private final DisplayObserverAccessService accessService;
    private final DisplayObserverRateLimiter rateLimiter;
    private final TokenService tokenService;
    private final AuthenticationService authenticationService;
    private final boolean refreshCookieSecure;

    public DisplayObserverController(DisplayObserverBindingService bindingService,
                                     DisplayObserverAccessService accessService,
                                     DisplayObserverRateLimiter rateLimiter,
                                     TokenService tokenService,
                                     AuthenticationService authenticationService,
                                     @Value("${xupan.auth.refresh-cookie-secure:true}") boolean refreshCookieSecure) {
        this.bindingService = bindingService;
        this.accessService = accessService;
        this.rateLimiter = rateLimiter;
        this.tokenService = tokenService;
        this.authenticationService = authenticationService;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @GetMapping("/display/observer-link")
    public ObserverLinkResponse observerLink() {
        return bindingService.currentPublicBinding()
                .map(binding -> new ObserverLinkResponse(true, observerUrl(binding.publicId())))
                .orElseGet(() -> new ObserverLinkResponse(false, null));
    }

    @PostMapping("/display-observer/exchange")
    public ExchangeResponse exchange(@Valid @RequestBody ExchangeRequest request,
                                     HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        rateLimiter.checkExchange(httpRequest.getRemoteAddr(), request.publicId());
        DisplayObserverAccessService.ResolvedObserver binding = accessService.resolve(request.publicId())
                .orElseThrow(() -> BusinessException.notFound(
                        "DISPLAY_OBSERVER_UNAVAILABLE", "开奖页只读入口不可用"));
        TokenService.RequestMetadata metadata = new TokenService.RequestMetadata(
                httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
        TokenService.IssuedTokens tokens = tokenService.issueDisplayObserver(
                binding.viewerUserId(), binding.publicId(), metadata);
        if (!accessService.isCurrent(binding.viewerUserId(), binding.scope())) {
            tokenService.revoke(tokens.sessionId());
            throw BusinessException.notFound("DISPLAY_OBSERVER_UNAVAILABLE", "开奖页只读入口不可用");
        }
        AuthController.writeObserverRefreshCookie(httpResponse, tokens.refreshToken(), refreshCookieSecure);
        AuthenticationService.CurrentUser user = authenticationService.currentUser(
                binding.viewerUserId(), "DISPLAY_OBSERVER", binding.scope());
        return new ExchangeResponse(tokens.accessToken(), expiresIn(tokens), CurrentUserResponse.from(user));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.code(), "message", exception.publicMessage()));
    }

    private String observerUrl(String publicId) {
        return "/display/observe/" + publicId;
    }

    private static long expiresIn(TokenService.IssuedTokens tokens) {
        return Math.max(0, tokens.accessExpiresAt().getEpochSecond() - Instant.now().getEpochSecond());
    }

    public record ObserverLinkResponse(boolean enabled, String href) {
    }

    public record ExchangeRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{16}") String publicId) {
    }

    public record ExchangeResponse(String accessToken, long expiresIn, CurrentUserResponse user) {
    }
}
