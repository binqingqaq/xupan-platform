package com.xupan.server.game.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DemoGameServiceBusinessDayTest {

    @Test
    void businessDayRollsAtSixInShanghai() {
        assertThat(DemoGameService.businessDay(Instant.parse("2026-09-21T21:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(DemoGameService.businessDay(Instant.parse("2026-09-21T22:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 22));
    }
}
