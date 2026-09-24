package com.xupan.server.auth.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.TokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "xupan_refresh";
    public static final String PLAYER_REFRESH_COOKIE = "xupan_player_refresh";
    public static final String REFRESH_AUDIENCE_HEADER = "X-Xupan-Auth-Audience";
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthenticationService authenticationService;
    private final TokenService tokenService;
    private final boolean refreshCookieSecure;

    public AuthController(AuthenticationService authenticationService, TokenService tokenService,
                          @Value("${xupan.auth.refresh-cookie-secure:true}") boolean refreshCookieSecure) {
        this.authenticationService = authenticationService;
        this.tokenService = tokenService;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse response) {
        TokenService.RequestMetadata metadata = metadata(httpRequest);
        AuthenticationService.LoginResult result = authenticationService.login(
                request.username(), request.password(), request.deviceLabel(), metadata);
        writeRefreshCookie(response, result.tokens().refreshToken());
        return AuthResponse.from(result);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        RefreshAudience audience = refreshAudience(request);
        String rawRefreshToken = refreshCookie(request, audience.cookieName());
        try {
            TokenService.IssuedTokens tokens = authenticationService.refresh(
                    rawRefreshToken, metadata(request), audience.authMode());
            var session = tokenService.validateAccessToken(tokens.accessToken())
                    .orElseThrow(() -> new TokenService.InvalidTokenException("刷新状态无效"));
            AuthenticationService.CurrentUser user = authenticationService.currentUser(
                    session.userId(), session.authMode(), session.scope());
            writeRefreshCookie(response, audience, tokens.refreshToken(), refreshCookieSecure);
            return new AuthResponse(tokens.accessToken(), expiresIn(tokens), CurrentUserResponse.from(user));
        } catch (TokenService.InvalidTokenException exception) {
            clearRefreshCookie(response, audience);
            throw exception;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser user = principal(authentication);
        authenticationService.logout(user.getUserId(),
                (String) request.getAttribute(AuthenticationService.SESSION_ID_ATTRIBUTE), metadata(request));
        clearRefreshCookie(response, "PLAYER_LINK".equals(user.authMode())
                ? RefreshAudience.PLAYER : RefreshAudience.ADMIN);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        AuthenticatedUser user = principal(authentication);
        return CurrentUserResponse.from(authenticationService.currentUser(user.getUserId(), user.authMode(), user.scope()));
    }

    @PostMapping("/ws-ticket")
    public WsTicketResponse wsTicket(Authentication authentication,
                                     HttpServletRequest request,
                                     @RequestParam(required = false) String roomCode) {
        AuthenticatedUser user = principal(authentication);
        String sessionId = (String) request.getAttribute(AuthenticationService.SESSION_ID_ATTRIBUTE);
        TokenService.IssuedWsTicket ticket = tokenService.issueWsTicket(user.getUserId(), sessionId, roomCode);
        return new WsTicketResponse(ticket.rawTicket(), TokenService.WS_TICKET_LIFETIME.toSeconds());
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    private static TokenService.RequestMetadata metadata(HttpServletRequest request) {
        return new TokenService.RequestMetadata(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    private static String refreshCookie(HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static void writeRefreshCookie(HttpServletResponse response, String value, boolean secure) {
        writeRefreshCookie(response, RefreshAudience.ADMIN, value, secure);
    }

    public static void writePlayerRefreshCookie(HttpServletResponse response, String value, boolean secure) {
        writeRefreshCookie(response, RefreshAudience.PLAYER, value, secure);
    }

    private static void writeRefreshCookie(HttpServletResponse response, RefreshAudience audience,
                                           String value, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(audience.cookieName(), value)
                .httpOnly(true).secure(secure).sameSite("Strict").path(COOKIE_PATH)
                .maxAge(TokenService.REFRESH_TOKEN_LIFETIME).build().toString());
    }

    private void writeRefreshCookie(HttpServletResponse response, String value) {
        writeRefreshCookie(response, value, refreshCookieSecure);
    }

    private void clearRefreshCookie(HttpServletResponse response, RefreshAudience audience) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(audience.cookieName(), "")
                .httpOnly(true).secure(refreshCookieSecure).sameSite("Strict").path(COOKIE_PATH)
                .maxAge(Duration.ZERO).build().toString());
    }

    private static RefreshAudience refreshAudience(HttpServletRequest request) {
        String value = request.getHeader(REFRESH_AUDIENCE_HEADER);
        return "PLAYER".equalsIgnoreCase(value) ? RefreshAudience.PLAYER : RefreshAudience.ADMIN;
    }

    private static long expiresIn(TokenService.IssuedTokens tokens) {
        return Math.max(0, tokens.accessExpiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond());
    }

    public record WsTicketResponse(String ticket, long expiresIn) {
    }

    public enum RefreshAudience {
        ADMIN(REFRESH_COOKIE, "PASSWORD"),
        PLAYER(PLAYER_REFRESH_COOKIE, "PLAYER_LINK");

        private final String cookieName;
        private final String authMode;

        RefreshAudience(String cookieName, String authMode) {
            this.cookieName = cookieName;
            this.authMode = authMode;
        }

        public String cookieName() {
            return cookieName;
        }

        public String authMode() {
            return authMode;
        }
    }
}
