package com.xupan.server.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WsTicketTest {

    @Test
    void validityRequiresMatchingUseStateAndStrictExpiry() {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        WsTicket ticket = new WsTicket("hash", 7L, "session", "room", now.plusSeconds(1), null, now);

        assertThat(ticket.belongsTo(7L, "session", "room")).isTrue();
        assertThat(ticket.belongsTo(8L, "session", "room")).isFalse();
        assertThat(ticket.isUsableAt(now)).isTrue();
        assertThat(ticket.isUsableAt(now.plusSeconds(1))).isFalse();
        assertThat(ticket.withUsedAt(now).isUsableAt(now)).isFalse();
    }
}
