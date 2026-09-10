package com.xupan.server.auth.domain;

import java.time.Instant;

/** Database-backed login identity. Secrets are represented only by a password hash. */
public record UserAccount(
        long id,
        String username,
        String displayName,
        String avatarKey,
        String passwordHash,
        String status,
        int failedLoginCount,
        Instant lockedUntil,
        long securityVersion,
        Instant lastLoginAt,
        String lastLoginIp) {

    public boolean canLogin(Instant now) {
        if (now == null || "DISABLED".equals(status) || "DELETED".equals(status)) {
            return false;
        }
        if ("LOCKED".equals(status)) {
            return lockedUntil != null && !now.isBefore(lockedUntil);
        }
        return "ACTIVE".equals(status) && (lockedUntil == null || !now.isBefore(lockedUntil));
    }
}
