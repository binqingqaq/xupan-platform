package com.xupan.server.auth.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.SessionRecord;
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
    public static final String CONSOLE_REFRESH_COOKIE = "xupan_console_refresh";
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

    /**
     * 登录：按请求头 {@value #REFRESH_AUDIENCE_HEADER} 决定入口，写对应入口的刷新 Cookie 并落库入口。
     *
     * <p>平台入口沿用旧 Cookie 名 {@value #REFRESH_COOKIE}（老用户无感）；
     * 代理后台入口（BY220 {@code robot.php}，机器账号）用 {@value #CONSOLE_REFRESH_COOKIE}。
     * 两个入口的会话在库里靠 {@code auth_session.entry} 区分（Flyway V75）。</p>
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse response) {
        TokenService.RequestMetadata metadata = metadata(httpRequest);
        RefreshAudience audience = loginAudience(httpRequest);
        AuthenticationService.LoginResult result = authenticationService.login(
                request.username(), request.password(), request.deviceLabel(), metadata, audience.entry());
        writeRefreshCookie(response, audience, result.tokens().refreshToken(), refreshCookieSecure);
        return AuthResponse.from(result);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        RefreshAudience audience = refreshAudience(request);
        String rawRefreshToken = refreshCookie(request, audience.cookieName());
        try {
            TokenService.IssuedTokens tokens = authenticationService.refresh(
                    rawRefreshToken, metadata(request), audience.authMode(), audience.entry());
            var session = tokenService.validateAccessToken(tokens.accessToken())
                    .orElseThrow(() -> new TokenService.InvalidTokenException("刷新状态无效"));
            AuthenticationService.CurrentUser user = authenticationService.currentUser(
                    session.userId(), session.authMode(), session.scope());
            writeRefreshCookie(response, audience, tokens.refreshToken(), refreshCookieSecure);
            return new AuthResponse(tokens.accessToken(), expiresIn(tokens), CurrentUserResponse.from(user));
        } catch (TokenService.InvalidTokenException exception) {
            // 只清本入口的 Cookie：跨入口刷新被拒时，另一个入口的 Cookie 与会话都不受影响。
            clearRefreshCookie(response, audience);
            throw exception;
        }
    }

    /**
     * 退出：**只清当前入口的刷新 Cookie**。
     *
     * <p>BY220 里登录 {@code admin.php} 会清空整个 session 顺带把 {@code robot.php} 的机器踢下线
     * （使用者 2026-10-08 确认**不复现**该副作用）。旧实现清的是"整个 ADMIN 会话"，
     * 在 /console 退出会把 /platform-admin 一起踢掉；现在按会话的 {@code entry} 精确清理。</p>
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser user = principal(authentication);
        String sessionId = (String) request.getAttribute(AuthenticationService.SESSION_ID_ATTRIBUTE);
        authenticationService.logout(user.getUserId(), sessionId, metadata(request));
        clearRefreshCookie(response, logoutAudience(user, sessionId));
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
        writeRefreshCookie(response, RefreshAudience.PLATFORM, value, secure);
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

    private void clearRefreshCookie(HttpServletResponse response, RefreshAudience audience) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(audience.cookieName(), "")
                .httpOnly(true).secure(refreshCookieSecure).sameSite("Strict").path(COOKIE_PATH)
                .maxAge(Duration.ZERO).build().toString());
    }

    /**
     * 登录入口解析：只接受平台入口与代理入口。
     *
     * <p>向后兼容（阶段 5 的前端改造落地前，前端仍会发 {@code ADMIN}）：
     * {@code ADMIN}、缺省、空值与未知值一律按平台入口处理，并沿用旧 Cookie 名 {@value #REFRESH_COOKIE}，
     * 保证中间态不会打断现有登录。{@code PLAYER} 不作为登录入口——玩家会话由
     * {@code POST /api/player-auth/exchange} 用玩家链接签发。</p>
     */
    private static RefreshAudience loginAudience(HttpServletRequest request) {
        return refreshAudience(request) == RefreshAudience.CONSOLE
                ? RefreshAudience.CONSOLE : RefreshAudience.PLATFORM;
    }

    /**
     * 刷新入口解析：{@code CONSOLE} → 代理入口，{@code PLAYER} → 玩家链接，
     * {@code ADMIN} / 缺省 / 未知 → 平台入口（向后兼容旧前端与老会话）。
     */
    private static RefreshAudience refreshAudience(HttpServletRequest request) {
        String value = request.getHeader(REFRESH_AUDIENCE_HEADER);
        if (value == null) {
            return RefreshAudience.PLATFORM;
        }
        String normalized = value.trim();
        if ("CONSOLE".equalsIgnoreCase(normalized)) {
            return RefreshAudience.CONSOLE;
        }
        return "PLAYER".equalsIgnoreCase(normalized) ? RefreshAudience.PLAYER : RefreshAudience.PLATFORM;
    }

    /** 退出时按会话入口决定清哪个 Cookie；玩家链接会话仍只清玩家 Cookie。 */
    private RefreshAudience logoutAudience(AuthenticatedUser user, String sessionId) {
        if ("PLAYER_LINK".equals(user.authMode())) {
            return RefreshAudience.PLAYER;
        }
        return RefreshAudience.forEntry(tokenService.sessionEntry(sessionId));
    }

    private static long expiresIn(TokenService.IssuedTokens tokens) {
        return Math.max(0, tokens.accessExpiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond());
    }

    public record WsTicketResponse(String ticket, long expiresIn) {
    }

    /**
     * 刷新使用端（一个使用端一个刷新 Cookie，互不覆盖）。
     *
     * <p>{@code entry} 是会话入口维度（Flyway V75）：平台入口与代理入口各自校验，跨入口刷新一律 401。
     * 玩家链接没有后台入口维度，{@code entry} 为 {@code null} 表示不做入口校验，
     * 其身份隔离继续由 {@code authMode = PLAYER_LINK} 承担。</p>
     */
    public enum RefreshAudience {
        PLATFORM(REFRESH_COOKIE, "PASSWORD", SessionRecord.ENTRY_PLATFORM),
        CONSOLE(CONSOLE_REFRESH_COOKIE, "PASSWORD", SessionRecord.ENTRY_CONSOLE),
        PLAYER(PLAYER_REFRESH_COOKIE, "PLAYER_LINK", null);

        private final String cookieName;
        private final String authMode;
        private final String entry;

        RefreshAudience(String cookieName, String authMode, String entry) {
            this.cookieName = cookieName;
            this.authMode = authMode;
            this.entry = entry;
        }

        public String cookieName() {
            return cookieName;
        }

        public String authMode() {
            return authMode;
        }

        /** 该使用端要求的会话入口；{@code null} 表示校验时跳过入口维度。 */
        public String entry() {
            return entry;
        }

        /** 按会话入口反查使用端（退出登录用）；非 CONSOLE 一律平台入口。 */
        public static RefreshAudience forEntry(String entry) {
            return SessionRecord.ENTRY_CONSOLE.equals(entry) ? CONSOLE : PLATFORM;
        }
    }
}
