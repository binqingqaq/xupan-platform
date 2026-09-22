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
        String lastLoginIp,
        String userType,
        String internalCode,
        String authMode) {

    /** Compatibility constructor for domain-focused tests and legacy callers. */
    public UserAccount(long id, String username, String displayName, String avatarKey,
                       String passwordHash, String status, int failedLoginCount,
                       Instant lockedUntil, long securityVersion, Instant lastLoginAt,
                       String lastLoginIp) {
        this(id, username, displayName, avatarKey, passwordHash, status, failedLoginCount,
                lockedUntil, securityVersion, lastLoginAt, lastLoginIp, "REAL", null, "PASSWORD");
    }

    /** Compatibility constructor for callers that provide the user type but not business identity. */
    public UserAccount(long id, String username, String displayName, String avatarKey,
                       String passwordHash, String status, int failedLoginCount,
                       Instant lockedUntil, long securityVersion, Instant lastLoginAt,
                       String lastLoginIp, String userType) {
        this(id, username, displayName, avatarKey, passwordHash, status, failedLoginCount,
                lockedUntil, securityVersion, lastLoginAt, lastLoginIp, userType, null, "PASSWORD");
    }

    public boolean canLogin(Instant now) {
        if (now == null || "TEST".equals(userType)
                || "DISABLED".equals(status) || "DELETED".equals(status)) {
            return false;
        }
        if ("LOCKED".equals(status)) {
            return lockedUntil != null && !now.isBefore(lockedUntil);
        }
        return "ACTIVE".equals(status) && (lockedUntil == null || !now.isBefore(lockedUntil));
    }
}
