package com.xupan.server.playerauth.domain;

import java.time.Instant;

public record PlayerAccessLink(long id, long userId, String tokenHash, String tokenCiphertext, String scope,
                               Instant expiresAt, Instant revokedAt, Instant lastUsedAt,
                               long createdBy, Instant createdAt) {

    public boolean usableAt(Instant now) {
        return now != null && "PLAYER_FULL".equals(scope) && revokedAt == null
                && expiresAt != null && now.isBefore(expiresAt);
    }
}
