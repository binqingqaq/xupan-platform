package com.xupan.server.robot.domain;

import java.time.Instant;

public record ChatRobot(
        long id,
        String robotCode,
        String displayName,
        String avatarKey,
        RobotStatus status,
        int weight,
        int delaySeconds,
        Instant createdAt,
        Instant updatedAt
) {

    public boolean isEnabled() {
        return status == RobotStatus.ENABLED;
    }
}
