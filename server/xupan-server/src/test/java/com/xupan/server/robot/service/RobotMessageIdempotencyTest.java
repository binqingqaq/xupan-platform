package com.xupan.server.robot.service;

import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RobotMessageIdempotencyTest {

    private final RobotMessageIdempotency idempotency = new RobotMessageIdempotency();

    @Test
    void createsTheExpectedLowercaseSha256Key() {
        assertThat(idempotency.idempotencyKey(42L))
                .isEqualTo("405a2cea290d7a9ed50754bd9bdf31886228559dee8cd2741a56b92731c972cf")
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void sameEventIsIdempotentAndDifferentEventsAreDifferent() {
        assertThat(idempotency.idempotencyKey(1001L))
                .isEqualTo(idempotency.idempotencyKey(1001L));
        assertThat(idempotency.idempotencyKey(1001L))
                .isNotEqualTo(idempotency.idempotencyKey(1002L));
    }

    @Test
    void rejectsNonPositiveGameEventIds() {
        assertThatThrownBy(() -> idempotency.idempotencyKey(0L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROBOT_GAME_EVENT_ID_INVALID");
        assertThatThrownBy(() -> idempotency.idempotencyKey(-1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROBOT_GAME_EVENT_ID_INVALID");
    }
}
