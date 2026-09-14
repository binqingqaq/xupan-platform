package com.xupan.server.robot.domain;

import java.time.Instant;

public record ChatRobotTemplate(
        long id,
        long robotId,
        String eventType,
        String templateCode,
        String templateText,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
