package com.xupan.server.robot.service;

import java.time.Instant;

public record RobotRenderContext(
        String issueNumber,
        String eventType,
        String eventMessage,
        String robotName,
        Instant createdAt,
        String resultNumbers
) {
}
