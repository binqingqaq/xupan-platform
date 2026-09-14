package com.xupan.server.robot.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RobotRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private final RobotRetryPolicy policy = new RobotRetryPolicy(8, Duration.ofSeconds(30));

    @Test
    void usesCappedExponentialBackoffAndLease() {
        assertThat(policy.nextAttemptAt(NOW, 1)).isEqualTo(NOW.plusSeconds(1));
        assertThat(policy.nextAttemptAt(NOW, 3)).isEqualTo(NOW.plusSeconds(4));
        assertThat(policy.nextAttemptAt(NOW, 20)).isEqualTo(NOW.plusSeconds(300));
        assertThat(policy.lockedUntil(NOW)).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void stopsAutomaticRetryAfterConfiguredMaximum() {
        assertThat(policy.canRetry(0)).isTrue();
        assertThat(policy.canRetry(7)).isTrue();
        assertThat(policy.canRetry(8)).isFalse();
        assertThat(policy.canRetry(-1)).isFalse();
    }
}
