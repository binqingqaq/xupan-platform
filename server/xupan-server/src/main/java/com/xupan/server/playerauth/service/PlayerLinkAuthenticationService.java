package com.xupan.server.playerauth.service;

import com.xupan.server.auth.repository.LoginAuditRepository;
import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.auth.service.TokenService;
import com.xupan.server.playerauth.domain.PlayerAccessLink;
import com.xupan.server.playerauth.domain.PlayerLinkTarget;
import com.xupan.server.playerauth.repository.PlayerAccessLinkRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class PlayerLinkAuthenticationService {

    public static final String SCOPE_PLAYER_FULL = "PLAYER_FULL";
    private static final String PLAYER_LINK_AUTH_MODE = "PLAYER_LINK";
    private static final String NORMAL_PLAYER_KIND = "NORMAL";
    private static final String BOT_PLAYER_KIND = "BOT";

    private final PlayerAccessLinkRepository linkRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PermissionService permissionService;
    private final TokenService tokenService;
    private final AuthenticationService authenticationService;
    private final LoginAuditRepository loginAuditRepository;
    private final OperationAuditRepository operationAuditRepository;
    private final PlayerLinkTokenCipher tokenCipher;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final Duration linkLifetime;
    private final int defaultLinkDays;

    @Autowired
    public PlayerLinkAuthenticationService(PlayerAccessLinkRepository linkRepository,
                                           UserRepository userRepository,
                                           SessionRepository sessionRepository,
                                           PermissionService permissionService,
                                           TokenService tokenService,
                                           AuthenticationService authenticationService,
                                           LoginAuditRepository loginAuditRepository,
                                           OperationAuditRepository operationAuditRepository,
                                           PlayerLinkTokenCipher tokenCipher,
                                           @Value("${xupan.auth.player-link-lifetime:168h}") Duration linkLifetime) {
        this(linkRepository, userRepository, sessionRepository, permissionService, tokenService,
                authenticationService, loginAuditRepository, operationAuditRepository, tokenCipher, linkLifetime,
                new SecureRandom(), Clock.systemUTC());
    }

    PlayerLinkAuthenticationService(PlayerAccessLinkRepository linkRepository,
                                    UserRepository userRepository,
                                    SessionRepository sessionRepository,
                                    PermissionService permissionService,
                                    TokenService tokenService,
                                    AuthenticationService authenticationService,
                                    LoginAuditRepository loginAuditRepository,
                                    OperationAuditRepository operationAuditRepository,
                                    PlayerLinkTokenCipher tokenCipher,
                                    Duration linkLifetime, SecureRandom secureRandom, Clock clock) {
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.permissionService = permissionService;
        this.tokenService = tokenService;
        this.authenticationService = authenticationService;
        this.loginAuditRepository = loginAuditRepository;
        this.operationAuditRepository = operationAuditRepository;
        this.tokenCipher = tokenCipher;
        this.linkLifetime = linkLifetime;
        this.defaultLinkDays = Math.max(1, Math.toIntExact(linkLifetime.toDays()));
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    @Transactional
    public IssuedLink issue(long userId, long operatorUserId) {
        requireAdmin(operatorUserId);
        PlayerLinkTarget target = target(userId);
        ensureIssuable(target);
        Instant now = clock.instant();
        PlayerAccessLink current = linkRepository.findLatestByUserId(userId)
                .filter(link -> link.usableAt(now))
                .orElse(null);
        if (current != null && current.tokenCiphertext() != null) {
            return issuedFromStored(current);
        }
        String rawToken = randomToken();
        PlayerAccessLink link = new PlayerAccessLink(0L, userId, TokenService.sha256(rawToken),
                tokenCipher.encrypt(rawToken), SCOPE_PLAYER_FULL, now.plus(Duration.ofDays(configuredDays(userId))), null, null, operatorUserId, now);
        linkRepository.insert(link);
        userRepository.updateAuthMode(userId, PLAYER_LINK_AUTH_MODE);
        sessionRepository.revokeAllActiveByUserId(userId, now);
        audit(operatorUserId, "POST", "/api/admin/player-desk/players/" + userId + "/access-links",
                Long.toString(userId), "scope=" + SCOPE_PLAYER_FULL);
        long linkId = jdbcLinkId(link.tokenHash());
        return new IssuedLink(linkId, userId, SCOPE_PLAYER_FULL, link.expiresAt(), rawToken);
    }

    @Transactional
    public IssuedLink current(long userId, long operatorUserId) {
        requireAdmin(operatorUserId);
        PlayerLinkTarget target = target(userId);
        ensureIssuable(target);
        PlayerAccessLink link = linkRepository.findLatestByUserId(userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_LINK_NOT_FOUND", "当前没有玩家链接"));
        if (link.tokenCiphertext() == null) {
            throw BusinessException.notFound("PLAYER_LINK_DISPLAY_UNAVAILABLE", "当前链接需要刷新后才能展示");
        }
        return issuedFromStored(link);
    }

    @Transactional
    public IssuedLink rotate(long userId, long operatorUserId) {
        requireAdmin(operatorUserId);
        PlayerLinkTarget target = target(userId);
        ensureIssuable(target);
        Instant now = clock.instant();
        linkRepository.revokeAllByUserId(userId, now);
        sessionRepository.revokeAllActiveByUserId(userId, now);
        return issueInternal(userId, operatorUserId, now);
    }

    @Transactional
    public void revoke(long userId, long linkId, long operatorUserId) {
        requireAdmin(operatorUserId);
        PlayerLinkTarget target = target(userId);
        ensureIssuable(target);
        if (linkRepository.findByIdAndUserId(linkId, userId).isEmpty()
                || linkRepository.revoke(linkId, userId, clock.instant()) == 0) {
            throw BusinessException.notFound("PLAYER_LINK_NOT_FOUND", "玩家链接不存在");
        }
        sessionRepository.revokeAllActiveByUserId(userId, clock.instant());
        audit(operatorUserId, "POST", "/api/admin/player-desk/players/" + userId + "/access-links/" + linkId + "/revoke",
                Long.toString(linkId), "revoked=true");
    }

    @Transactional
    public void restore(long userId, long linkId, long operatorUserId) {
        requireAdmin(operatorUserId);
        PlayerLinkTarget target = target(userId);
        ensureIssuable(target);
        linkRepository.findByIdAndUserId(linkId, userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_LINK_NOT_FOUND", "玩家链接不存在"));
        if (linkRepository.restore(linkId, userId) != 1) {
            throw BusinessException.notFound("PLAYER_LINK_NOT_FOUND", "玩家链接不存在");
        }
        audit(operatorUserId, "POST", "/api/admin/player-desk/players/" + userId + "/access-links/" + linkId + "/restore",
                Long.toString(linkId), "revoked=false");
    }

    @Transactional
    public Expiration updateExpiration(long userId, int days, long operatorUserId) {
        requireAdmin(operatorUserId);
        if (days < 1 || days > 3650) {
            throw BusinessException.badRequest("PLAYER_LINK_EXPIRATION_INVALID", "链接有效期必须为 1 到 3650 天");
        }
        PlayerLinkTarget target = target(userId);
        ensureIssuable(target);
        PlayerAccessLink link = linkRepository.findLatestByUserId(userId).orElse(null);
        linkRepository.saveConfiguredDays(userId, days);
        audit(operatorUserId, "PATCH", "/api/admin/player-desk/players/" + userId + "/access-links/expiration",
                Long.toString(userId), "nextRefreshDays=" + days);
        return new Expiration(link == null ? 0 : link.id(), link == null ? null : link.expiresAt(), days);
    }

    @Transactional
    public ExchangeResult exchange(String rawToken, TokenService.RequestMetadata metadata) {
        if (rawToken == null || rawToken.isBlank() || rawToken.codePoints().anyMatch(Character::isWhitespace)) {
            throw invalidLink();
        }
        Instant now = clock.instant();
        PlayerAccessLink link = linkRepository.findUsableByHashForUpdate(TokenService.sha256(rawToken), now)
                .orElseThrow(PlayerLinkAuthenticationService::invalidLink);
        if (!link.usableAt(now) || linkRepository.touchUsed(link.id(), now) != 1) {
            throw invalidLink();
        }
        TokenService.IssuedTokens tokens = tokenService.issuePlayerLink(link.userId(), "player-link", metadata);
        AuthenticationService.CurrentUser user = authenticationService.currentUser(
                link.userId(), PLAYER_LINK_AUTH_MODE, SCOPE_PLAYER_FULL);
        loginAuditRepository.recordSuccess("player-link", link.userId(),
                metadata == null ? null : metadata.ipAddress(), metadata == null ? null : metadata.userAgent(), now);
        return new ExchangeResult(tokens, user);
    }

    private IssuedLink issueInternal(long userId, long operatorUserId, Instant now) {
        String rawToken = randomToken();
        PlayerAccessLink link = new PlayerAccessLink(0L, userId, TokenService.sha256(rawToken),
                tokenCipher.encrypt(rawToken), SCOPE_PLAYER_FULL, now.plus(Duration.ofDays(configuredDays(userId))), null, null, operatorUserId, now);
        linkRepository.insert(link);
        userRepository.updateAuthMode(userId, PLAYER_LINK_AUTH_MODE);
        audit(operatorUserId, "POST", "/api/admin/player-desk/players/" + userId + "/access-links/rotate",
                Long.toString(userId), "scope=" + SCOPE_PLAYER_FULL);
        return new IssuedLink(jdbcLinkId(link.tokenHash()), userId, SCOPE_PLAYER_FULL, link.expiresAt(), rawToken);
    }

    private long jdbcLinkId(String tokenHash) {
        return linkRepository.findByHash(tokenHash).map(PlayerAccessLink::id)
                .orElseThrow(() -> new IllegalStateException("创建玩家链接后未取得 ID"));
    }

    private int configuredDays(long userId) {
        int days = linkRepository.findConfiguredDays(userId, defaultLinkDays);
        return days > 0 ? days : defaultLinkDays;
    }

    private IssuedLink issuedFromStored(PlayerAccessLink link) {
        return new IssuedLink(link.id(), link.userId(), link.scope(), link.expiresAt(), tokenCipher.decrypt(link.tokenCiphertext()));
    }

    private PlayerLinkTarget target(long userId) {
        return userRepository.findPlayerLinkTargetForUpdate(userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_LINK_NOT_FOUND", "玩家不存在"));
    }

    private static void ensureIssuable(PlayerLinkTarget target) {
        if (!NORMAL_PLAYER_KIND.equals(target.playerKind()) && !BOT_PLAYER_KIND.equals(target.playerKind())) {
            throw BusinessException.badRequest("PLAYER_LINK_NOT_ALLOWED", "当前玩家分类不支持玩家链接");
        }
        if (!"ACTIVE".equals(target.userStatus()) || !"ACTIVE".equals(target.accountStatus())) {
            throw BusinessException.badRequest("PLAYER_LINK_STATUS_INVALID", "当前玩家状态不允许链接登录");
        }
    }

    private void requireAdmin(long operatorUserId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, "USER_MANAGE")) {
            throw BusinessException.forbidden("PLAYER_LINK_OPERATION_FORBIDDEN", "当前账号没有玩家链接管理权限");
        }
    }

    private void audit(long operator, String method, String path, String resource, String summary) {
        operationAuditRepository.record(operator, "USER_MANAGE", method, path, resource,
                "SUCCESS", null, summary, null, clock.instant());
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static AuthenticationService.AuthenticationFailure invalidLink() {
        return new AuthenticationService.AuthenticationFailure("PLAYER_LINK_INVALID");
    }

    public record IssuedLink(long linkId, long userId, String scope, Instant expiresAt, String rawToken) {}
    public record Expiration(long linkId, Instant expiresAt, int days) {}

    public record ExchangeResult(TokenService.IssuedTokens tokens, AuthenticationService.CurrentUser user) {}

}
