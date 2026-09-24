package com.xupan.server.playerauth.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.TokenService;
import com.xupan.server.auth.web.CurrentUserResponse;
import com.xupan.server.playerauth.service.PlayerLinkAuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
public class PlayerLinkAuthController {

    private static final String PLAYER_ROOM_CODE = "33";

    private final PlayerLinkAuthenticationService service;
    private final boolean refreshCookieSecure;

    public PlayerLinkAuthController(PlayerLinkAuthenticationService service,
                                    @Value("${xupan.auth.refresh-cookie-secure:true}") boolean refreshCookieSecure) {
        this.service = service;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @PostMapping("/api/player-auth/exchange")
    public ExchangeResponse exchange(@Valid @RequestBody ExchangeRequest request, HttpServletRequest httpRequest,
                                     HttpServletResponse response) {
        PlayerLinkAuthenticationService.ExchangeResult result = service.exchange(request.token(), metadata(httpRequest));
        com.xupan.server.auth.web.AuthController.writePlayerRefreshCookie(
                response, result.tokens().refreshToken(), refreshCookieSecure);
        return ExchangeResponse.from(result);
    }

    @PostMapping("/api/admin/player-desk/players/{userId}/access-links")
    public LinkResponse issue(Authentication authentication, @PathVariable long userId, HttpServletRequest request) {
        return LinkResponse.from(service.issue(userId, userId(authentication)), origin(request));
    }

    @GetMapping("/api/admin/player-desk/players/{userId}/access-links/current")
    public LinkResponse current(Authentication authentication, @PathVariable long userId, HttpServletRequest request) {
        return LinkResponse.from(service.current(userId, userId(authentication)), origin(request));
    }

    @PostMapping("/api/admin/player-desk/players/{userId}/access-links/{linkId}/revoke")
    public void revoke(Authentication authentication, @PathVariable long userId, @PathVariable long linkId) {
        service.revoke(userId, linkId, userId(authentication));
    }

    @PostMapping("/api/admin/player-desk/players/{userId}/access-links/{linkId}/restore")
    public void restore(Authentication authentication, @PathVariable long userId, @PathVariable long linkId) {
        service.restore(userId, linkId, userId(authentication));
    }

    @PostMapping("/api/admin/player-desk/players/{userId}/access-links/rotate")
    public LinkResponse rotate(Authentication authentication, @PathVariable long userId, HttpServletRequest request) {
        return LinkResponse.from(service.rotate(userId, userId(authentication)), origin(request));
    }

    @PatchMapping("/api/admin/player-desk/players/{userId}/access-links/expiration")
    public ExpirationResponse updateExpiration(Authentication authentication, @PathVariable long userId,
                                               @Valid @RequestBody ExpirationRequest request) {
        PlayerLinkAuthenticationService.Expiration result = service.updateExpiration(userId, request.days(), userId(authentication));
        return new ExpirationResponse(result.linkId(), result.expiresAt(), result.days());
    }

    private static long userId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.getUserId() : 0L;
    }

    private static TokenService.RequestMetadata metadata(HttpServletRequest request) {
        return new TokenService.RequestMetadata(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    private static String origin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean standard = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + host + (standard ? "" : ":" + port);
    }

    public record ExchangeRequest(@NotBlank String token) {}
    public record ExpirationRequest(@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(3650) int days) {}
    public record ExpirationResponse(long linkId, java.time.Instant expiresAt, int days) {}

    public record ExchangeResponse(String accessToken, long expiresIn, CurrentUserResponse user) {
        static ExchangeResponse from(PlayerLinkAuthenticationService.ExchangeResult result) {
            return new ExchangeResponse(result.tokens().accessToken(),
                    Math.max(0, result.tokens().accessExpiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond()),
                    CurrentUserResponse.from(result.user()));
        }
    }

    public record LinkResponse(long linkId, long userId, String scope, java.time.Instant expiresAt, String accessUrl) {
        static LinkResponse from(PlayerLinkAuthenticationService.IssuedLink link, String origin) {
            String token = URLEncoder.encode(link.rawToken(), StandardCharsets.UTF_8);
            return new LinkResponse(link.linkId(), link.userId(), link.scope(), link.expiresAt(),
                    origin + "/" + PLAYER_ROOM_CODE + "/" + token);
        }
    }
}
