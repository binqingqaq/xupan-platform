package com.xupan.server.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountTest {

    @Test
    void activeUserCanLoginWhenThereIsNoCurrentLock() {
        UserAccount user = new UserAccount(1L, "alice", "Alice", null, "hash", "ACTIVE", 0,
                null, 0L, null, null);

        assertThat(user.canLogin(Instant.parse("2026-09-10T10:00:00Z"))).isTrue();
    }

    @Test
    void activeUserCannotLoginDuringLockButCanTryAfterItExpires() {
        Instant lockedUntil = Instant.parse("2026-09-10T10:15:00Z");
        UserAccount user = new UserAccount(1L, "alice", "Alice", null, "hash", "ACTIVE", 5,
                lockedUntil, 0L, null, null);

        assertThat(user.canLogin(Instant.parse("2026-09-10T10:14:59Z"))).isFalse();
        assertThat(user.canLogin(Instant.parse("2026-09-10T10:15:00Z"))).isTrue();
    }

    @Test
    void disabledAndDeletedUsersCannotLogin() {
        assertThat(userWithStatus("DISABLED").canLogin(Instant.now())).isFalse();
        assertThat(userWithStatus("DELETED").canLogin(Instant.now())).isFalse();
    }

    private static UserAccount userWithStatus(String status) {
        return new UserAccount(1L, "alice", "Alice", null, "hash", status, 0,
                null, 0L, null, null);
    }
}
