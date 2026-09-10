package com.xupan.server.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRecordTest {

    @Test
    void accessAndRefreshValidityRequireExpiryAndNotRevoked() {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        SessionRecord session = new SessionRecord(1L, "session-1", 2L, "access-hash",
                now.plusSeconds(60), "refresh-hash", now.plusSeconds(3600), "Chrome", "ip", "ua",
                now, null, now, 3L);

        assertThat(session.isAccessTokenValid(now, 3L)).isTrue();
        assertThat(session.isAccessTokenValid(now.plusSeconds(60), 3L)).isFalse();
        assertThat(session.isRefreshTokenValid(now.plusSeconds(3599))).isTrue();
        assertThat(session.isRefreshTokenValid(now.plusSeconds(3600))).isFalse();
        assertThat(session.withRevokedAt(now).isAccessTokenValid(now, 3L)).isFalse();
    }

    @Test
    void changedSecurityVersionInvalidatesAccessSession() {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        SessionRecord session = new SessionRecord(1L, "session-1", 2L, "access-hash",
                now.plusSeconds(60), "refresh-hash", now.plusSeconds(3600), null, null, null,
                null, null, now, 3L);

        assertThat(session.isAccessTokenValid(now, 4L)).isFalse();
    }
}
