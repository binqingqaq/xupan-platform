package com.xupan.server.auth.domain;

import java.time.Instant;

/**
 * Persisted opaque-token session. Only token digests are held by this object.
 *
 * <p>{@code entry} 是 Flyway V75 引入的**会话入口维度**（BY220 admin.php 与 robot.php 两套入口
 * 必须各自独立会话）：{@link #ENTRY_PLATFORM} 平台入口（/platform-admin，超管/子账号）、
 * {@link #ENTRY_CONSOLE} 代理后台入口（/console，机器账号）。刷新时由
 * {@code TokenService} 校验请求入口与会话入口一致，不一致一律 401，绝不静默切换身份。</p>
 *
 * <p>玩家链接会话（{@code auth_mode = 'PLAYER_LINK'}）不属于这两个后台入口，按
 * {@link #ENTRY_PLATFORM} 落库，其刷新鉴权仍由 {@code authMode} 负责。</p>
 */
public record SessionRecord(
        long id,
        String sessionId,
        long userId,
        String accessTokenHash,
        Instant accessExpiresAt,
        String refreshTokenHash,
        Instant refreshExpiresAt,
        String deviceLabel,
        String ipDigest,
        String userAgentDigest,
        Instant lastSeenAt,
        Instant revokedAt,
        Instant createdAt,
        long securityVersion,
        String authMode,
        String scope,
        String entry) {

    /** 平台入口：BY220 {@code admin.php}（/platform-admin，超管与子账号）。 */
    public static final String ENTRY_PLATFORM = "PLATFORM";
    /** 代理后台入口：BY220 {@code robot.php}（/console，机器账号）。 */
    public static final String ENTRY_CONSOLE = "CONSOLE";

    public SessionRecord {
        entry = entry == null || entry.isBlank() ? ENTRY_PLATFORM : entry;
    }

    /** 兼容旧签名：不传入口时按平台入口处理（V75 之前的会话语义）。 */
    public SessionRecord(long id, String sessionId, long userId, String accessTokenHash,
                         Instant accessExpiresAt, String refreshTokenHash, Instant refreshExpiresAt,
                         String deviceLabel, String ipDigest, String userAgentDigest,
                         Instant lastSeenAt, Instant revokedAt, Instant createdAt,
                         long securityVersion, String authMode, String scope) {
        this(id, sessionId, userId, accessTokenHash, accessExpiresAt, refreshTokenHash,
                refreshExpiresAt, deviceLabel, ipDigest, userAgentDigest, lastSeenAt, revokedAt,
                createdAt, securityVersion, authMode, scope, ENTRY_PLATFORM);
    }

    public SessionRecord(long id, String sessionId, long userId, String accessTokenHash,
                         Instant accessExpiresAt, String refreshTokenHash, Instant refreshExpiresAt,
                         String deviceLabel, String ipDigest, String userAgentDigest,
                         Instant lastSeenAt, Instant revokedAt, Instant createdAt) {
        this(id, sessionId, userId, accessTokenHash, accessExpiresAt, refreshTokenHash,
                refreshExpiresAt, deviceLabel, ipDigest, userAgentDigest, lastSeenAt, revokedAt,
                createdAt, -1L, "PASSWORD", null);
    }

    public SessionRecord(long id, String sessionId, long userId, String accessTokenHash,
                         Instant accessExpiresAt, String refreshTokenHash, Instant refreshExpiresAt,
                         String deviceLabel, String ipDigest, String userAgentDigest,
                         Instant lastSeenAt, Instant revokedAt, Instant createdAt,
                         long securityVersion) {
        this(id, sessionId, userId, accessTokenHash, accessExpiresAt, refreshTokenHash,
                refreshExpiresAt, deviceLabel, ipDigest, userAgentDigest, lastSeenAt, revokedAt,
                createdAt, securityVersion, "PASSWORD", null);
    }

    public boolean isAccessTokenValid(Instant now, long currentSecurityVersion) {
        return now != null
                && revokedAt == null
                && accessExpiresAt != null
                && now.isBefore(accessExpiresAt)
                && (securityVersion < 0 || securityVersion == currentSecurityVersion);
    }

    public boolean isChatOnly() {
        return "CHAT_ONLY".equals(scope);
    }

    public boolean isAccessTokenValid(Instant now) {
        return isAccessTokenValid(now, securityVersion);
    }

    public boolean isRefreshTokenValid(Instant now) {
        return now != null && revokedAt == null && refreshExpiresAt != null && now.isBefore(refreshExpiresAt);
    }

    public SessionRecord withRevokedAt(Instant value) {
        return new SessionRecord(id, sessionId, userId, accessTokenHash, accessExpiresAt, refreshTokenHash,
                refreshExpiresAt, deviceLabel, ipDigest, userAgentDigest, lastSeenAt, value, createdAt,
                securityVersion, authMode, scope, entry);
    }
}
