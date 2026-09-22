package com.xupan.server.auth.domain;

import java.time.Instant;

/** Persisted opaque-token session. Only token digests are held by this object. */
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
        String scope) {

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
                securityVersion, authMode, scope);
    }
}
