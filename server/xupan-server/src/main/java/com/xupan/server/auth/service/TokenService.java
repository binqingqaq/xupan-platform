package com.xupan.server.auth.service;

import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.domain.WsTicket;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-side opaque token lifecycle. Raw token values are returned only to the caller. */
@Service
public class TokenService {

    public static final int ACCESS_TOKEN_BYTES = 32;
    public static final int REFRESH_TOKEN_BYTES = 48;
    public static final int WS_TICKET_BYTES = 32;
    public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(30);
    public static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);
    public static final Duration WS_TICKET_LIFETIME = Duration.ofSeconds(60);

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Autowired
    public TokenService(SessionRepository sessionRepository, UserRepository userRepository) {
        this(sessionRepository, userRepository, new SecureRandom(), Clock.systemUTC());
    }

    TokenService(SessionRepository sessionRepository, UserRepository userRepository,
                 SecureRandom secureRandom, Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public IssuedTokens issue(long userId, String deviceLabel, RequestMetadata metadata) {
        return issue(userId, deviceLabel, metadata, SessionRecord.ENTRY_PLATFORM);
    }

    /**
     * 按**入口**签发密码会话（Flyway V75 的会话入口维度）。
     *
     * <p>BY220 的 {@code admin.php}（平台后台）与 {@code robot.php}（代理后台，机器账号）是两套独立入口，
     * 使用者 2026-10-08 确认 Q2 = C1：两入口各自独立会话、独立退出、可同时并存。
     * 入口只允许 {@link SessionRecord#ENTRY_PLATFORM} / {@link SessionRecord#ENTRY_CONSOLE}，
     * 未知值一律按平台入口处理（未知即最保守的老行为）。</p>
     */
    public IssuedTokens issue(long userId, String deviceLabel, RequestMetadata metadata, String entry) {
        return issue(userId, deviceLabel, metadata, "PASSWORD", null, normalizeEntry(entry));
    }

    public IssuedTokens issueChatOnly(long userId, String deviceLabel, RequestMetadata metadata) {
        return issuePlayerLinkSession(userId, deviceLabel, metadata, "CHAT_ONLY");
    }

    public IssuedTokens issuePlayerLink(long userId, String deviceLabel, RequestMetadata metadata) {
        return issuePlayerLinkSession(userId, deviceLabel, metadata, "PLAYER_FULL");
    }

    private IssuedTokens issue(long userId, String deviceLabel, RequestMetadata metadata,
                               String authMode, String scope, String entry) {
        Instant now = clock.instant();
        UserAccount user = activeUser(userId, now);
        return issueSession(user, deviceLabel, metadata, authMode, scope, entry, now);
    }

    private IssuedTokens issuePlayerLinkSession(long userId, String deviceLabel,
                                                RequestMetadata metadata, String scope) {
        Instant now = clock.instant();
        UserAccount user = activeUserForSession(userId, "PLAYER_LINK", now);
        // 玩家链接不是后台入口，不参与入口隔离；仍按平台入口落库（见 V75 说明），
        // 其刷新鉴权由 authMode = 'PLAYER_LINK' 承担。
        return issueSession(user, deviceLabel, metadata, "PLAYER_LINK", scope,
                SessionRecord.ENTRY_PLATFORM, now);
    }

    private IssuedTokens issueSession(UserAccount user, String deviceLabel, RequestMetadata metadata,
                                      String authMode, String scope, String entry, Instant now) {
        String accessToken = randomToken(ACCESS_TOKEN_BYTES);
        String refreshToken = randomToken(REFRESH_TOKEN_BYTES);
        Instant accessExpiresAt = now.plus(ACCESS_TOKEN_LIFETIME);
        Instant refreshExpiresAt = now.plus(REFRESH_TOKEN_LIFETIME);
        String sessionId = UUID.randomUUID().toString();

        sessionRepository.insert(new SessionRecord(
                0L,
                sessionId,
                user.id(),
                sha256(accessToken),
                accessExpiresAt,
                sha256(refreshToken),
                refreshExpiresAt,
                deviceLabel,
                digestMetadata(metadata == null ? null : metadata.ipAddress()),
                digestMetadata(metadata == null ? null : metadata.userAgent()),
                now,
                null,
                now,
                user.securityVersion(),
                authMode,
                scope,
                entry));
        return new IssuedTokens(accessToken, refreshToken, sessionId, accessExpiresAt, refreshExpiresAt);
    }

    public IssuedTokens refresh(String rawRefreshToken, RequestMetadata metadata) {
        return refresh(rawRefreshToken, metadata, null, null);
    }

    public IssuedTokens refresh(String rawRefreshToken, RequestMetadata metadata, String requiredAuthMode) {
        return refresh(rawRefreshToken, metadata, requiredAuthMode, null);
    }

    /**
     * 刷新会话，并校验**入口**一致。
     *
     * <p>阶段 5 的核心约束：请求入口与会话 {@code entry} 不一致时抛
     * {@link InvalidTokenException}（→ 401），**绝不静默把会话切换成另一个入口的身份**。
     * 例如把 /console 的刷新 Cookie 放进平台入口的 Cookie 名里刷新，会被这里挡下。</p>
     *
     * <p>{@code requiredEntry} 为 {@code null} 表示该使用端没有入口维度（玩家链接），
     * 此时不做入口校验，身份隔离仍由 {@code requiredAuthMode} 完成。</p>
     */
    public IssuedTokens refresh(String rawRefreshToken, RequestMetadata metadata, String requiredAuthMode,
                                String requiredEntry) {
        String refreshHash = sha256Required(rawRefreshToken);
        Instant now = clock.instant();
        SessionRecord current = sessionRepository.findByRefreshTokenHash(refreshHash)
                .filter(session -> session.isRefreshTokenValid(now))
                .orElseThrow(() -> new InvalidTokenException("刷新令牌无效"));
        if (requiredAuthMode != null && !requiredAuthMode.equals(current.authMode())) {
            throw new InvalidTokenException("刷新身份不匹配");
        }
        if (requiredEntry != null && !requiredEntry.equals(current.entry())) {
            throw new InvalidTokenException("刷新入口不匹配");
        }
        UserAccount user = activeUserForSession(current.userId(), current.authMode(), now);
        if (current.securityVersion() != user.securityVersion()) {
            throw new InvalidTokenException("刷新令牌版本无效");
        }

        String accessToken = randomToken(ACCESS_TOKEN_BYTES);
        String refreshToken = randomToken(REFRESH_TOKEN_BYTES);
        Instant accessExpiresAt = now.plus(ACCESS_TOKEN_LIFETIME);
        Instant refreshExpiresAt = now.plus(REFRESH_TOKEN_LIFETIME);
        if (!sessionRepository.rotateTokensIfCurrent(
                current.sessionId(), refreshHash, sha256(accessToken), accessExpiresAt,
                sha256(refreshToken), refreshExpiresAt)) {
            throw new InvalidTokenException("刷新令牌已被使用或撤销");
        }
        return new IssuedTokens(accessToken, refreshToken, current.sessionId(),
                accessExpiresAt, refreshExpiresAt);
    }

    public void revoke(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionRepository.revoke(sessionId, clock.instant());
        }
    }

    /** Returns the persisted session only when the opaque access token is currently valid. */
    public Optional<SessionRecord> validateAccessToken(String rawAccessToken) {
        String accessHash = sha256Optional(rawAccessToken);
        if (accessHash == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return sessionRepository.findByAccessTokenHash(accessHash).filter(session -> {
            Optional<UserAccount> user = userRepository.findById(session.userId());
            return user.isPresent()
                    && isActiveForSession(user.get(), session.authMode(), now)
                    && (!session.isChatOnly() || "PLAYER_LINK".equals(session.authMode()))
                    && session.isAccessTokenValid(now, user.get().securityVersion());
        });
    }

    /** Allows the harmless logout action to remain idempotent after its session was revoked. */
    public Optional<SessionRecord> findSessionForLogout(String rawAccessToken) {
        String accessHash = sha256Optional(rawAccessToken);
        return accessHash == null ? Optional.empty() : sessionRepository.findByAccessTokenHash(accessHash);
    }

    /**
     * 会话的入口维度（Flyway V75）。退出登录时用它决定"只清哪个入口的刷新 Cookie"：
     * 平台入口清 {@code xupan_refresh}，代理入口清 {@code xupan_console_refresh}。
     *
     * <p>会话不存在时返回 {@code null}（调用方按平台入口兜底），不抛异常，
     * 以保证退出登录的幂等性（会话已被撤销时仍能正确清 Cookie）。</p>
     */
    public String sessionEntry(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionRepository.findBySessionId(sessionId).map(SessionRecord::entry).orElse(null);
    }

    /** 会话入口归一：只认 CONSOLE，其余（含 null / 未知值）一律平台入口。 */
    public static String normalizeEntry(String entry) {
        return SessionRecord.ENTRY_CONSOLE.equalsIgnoreCase(entry == null ? null : entry.trim())
                ? SessionRecord.ENTRY_CONSOLE : SessionRecord.ENTRY_PLATFORM;
    }

    public IssuedWsTicket issueWsTicket(long userId, String sessionId, String roomCode) {
        Instant now = clock.instant();
        SessionRecord session = sessionRepository.findBySessionId(sessionId)
                .filter(value -> value.userId() == userId)
                .orElseThrow(() -> new InvalidTokenException("会话无效"));
        UserAccount user = activeUserForSession(userId, session.authMode(), now);
        if (!session.isAccessTokenValid(now, user.securityVersion())) {
            throw new InvalidTokenException("会话无效");
        }
        String normalizedRoomCode = normalizeRoomCode(roomCode);
        if (session.isChatOnly() && normalizedRoomCode == null) {
            throw new InvalidTokenException("聊天室范围无效");
        }
        String rawTicket = randomToken(WS_TICKET_BYTES);
        WsTicket persisted = new WsTicket(
                0L, sha256(rawTicket), user.id(), session.sessionId(), normalizedRoomCode, session.scope(),
                now.plus(WS_TICKET_LIFETIME), null, now);
        sessionRepository.insertWsTicket(persisted);
        return new IssuedWsTicket(rawTicket, persisted);
    }

    /** Consumes a ticket only when the caller supplies its complete binding context. */
    public WsTicket consumeWsTicket(String rawTicket, long userId, String sessionId, String roomCode) {
        String ticketHash = sha256Required(rawTicket);
        return sessionRepository.consumeWsTicket(ticketHash, userId, sessionId, normalizeRoomCode(roomCode), clock.instant())
                .filter(ticket -> ticket.belongsTo(userId, sessionId, roomCode))
                .orElseThrow(() -> new InvalidTokenException("WebSocket 票据无效"));
    }

    /** Consumes a ticket after the handshake has bound it to its room, user and session. */
    public WsTicket consumeWsTicket(String rawTicket, String roomCode) {
        String ticketHash = sha256Required(rawTicket);
        return sessionRepository.consumeWsTicket(ticketHash, normalizeRoomCode(roomCode), clock.instant())
                .filter(ticket -> Objects.equals(normalizeRoomCode(ticket.roomCode()), normalizeRoomCode(roomCode)))
                .orElseThrow(() -> new InvalidTokenException("WebSocket 票据无效"));
    }

    public static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                result.append(Character.forDigit((valueByte >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(valueByte & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 必须支持 SHA-256", exception);
        }
    }

    private UserAccount activeUser(long userId, Instant now) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("用户无效"));
        if (!isActive(user, now)) {
            throw new InvalidTokenException("用户状态无效");
        }
        return user;
    }

    private UserAccount activeUserForSession(long userId, String authMode, Instant now) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("用户无效"));
        if (!isActiveForSession(user, authMode, now)) {
            throw new InvalidTokenException("用户状态无效");
        }
        return user;
    }

    private static boolean isActive(UserAccount user, Instant now) {
        return "ACTIVE".equals(user.status()) && user.canLogin(now);
    }

    private static boolean isActiveForSession(UserAccount user, String authMode, Instant now) {
        if (!"ACTIVE".equals(user.status()) || now == null) {
            return false;
        }
        if ("PLAYER_LINK".equals(authMode) && "TEST".equals(user.userType())) {
            return user.lockedUntil() == null || !now.isBefore(user.lockedUntil());
        }
        return user.canLogin(now);
    }

    private String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Required(String rawValue) {
        String value = rawValue == null ? "" : rawValue;
        if (value.isBlank() || containsWhitespace(value)) {
            throw new InvalidTokenException("令牌格式无效");
        }
        return sha256(value);
    }

    private static String sha256Optional(String rawValue) {
        if (rawValue == null || rawValue.isBlank() || containsWhitespace(rawValue)) {
            return null;
        }
        return sha256(rawValue);
    }

    private static boolean containsWhitespace(String value) {
        return value.codePoints().anyMatch(Character::isWhitespace);
    }

    private static String digestMetadata(String value) {
        return value == null || value.isBlank() ? null : sha256(value);
    }

    private static String normalizeRoomCode(String roomCode) {
        return roomCode == null || roomCode.isBlank() ? null : roomCode;
    }

    public record RequestMetadata(String ipAddress, String userAgent) {
        public static RequestMetadata empty() {
            return new RequestMetadata(null, null);
        }
    }

    public record IssuedTokens(String accessToken, String refreshToken, String sessionId,
                               Instant accessExpiresAt, Instant refreshExpiresAt) {
    }

    public record IssuedWsTicket(String rawTicket, WsTicket persisted) {
        public String ticket() {
            return rawTicket;
        }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
