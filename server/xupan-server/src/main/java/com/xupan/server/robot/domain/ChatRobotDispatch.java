package com.xupan.server.robot.domain;

import java.time.Instant;

public record ChatRobotDispatch(
        long id,
        long gameEventId,
        long robotId,
        String issueNumber,
        String eventType,
        RobotDispatchStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant lockedUntil,
        Long messageId,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt
) {
}
