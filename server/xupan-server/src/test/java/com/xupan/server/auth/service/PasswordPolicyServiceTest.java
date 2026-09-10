package com.xupan.server.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyServiceTest {

    private final PasswordPolicyService service = new PasswordPolicyService();

    @Test
    void acceptsPasswordWithLetterAndDigitBetweenEightAndSeventyTwoCharacters() {
        service.validateForCreation("Password123");

        String encoded = service.encode("Password123");
        assertThat(encoded).startsWith("$2").doesNotContain("Password123");
        assertThat(service.matches("Password123", encoded)).isTrue();
        assertThat(service.matches("WrongPassword123", encoded)).isFalse();
    }

    @Test
    void rejectsInvalidPasswordShapesWithoutTrimming() {
        assertThatThrownBy(() -> service.validateForCreation(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateForCreation("Pass1" )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateForCreation("12345678")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateForCreation("Password")).isInstanceOf(IllegalArgumentException.class);
        service.validateForCreation(" Password123");
        String whitespacePreserved = service.encode(" Password123");
        assertThat(service.matches(" Password123", whitespacePreserved)).isTrue();
        assertThatThrownBy(() -> service.validateForCreation("a1".repeat(37))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locksOnTheFifthFailureForFifteenMinutes() {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");

        assertThat(service.shouldLock(4)).isFalse();
        assertThat(service.shouldLock(5)).isTrue();
        assertThat(service.lockUntil(now)).isEqualTo(Instant.parse("2026-09-10T10:15:00Z"));
    }
}
