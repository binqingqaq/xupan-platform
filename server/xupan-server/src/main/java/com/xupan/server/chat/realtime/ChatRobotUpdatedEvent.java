package com.xupan.server.chat.realtime;

import java.util.Objects;

public record ChatRobotUpdatedEvent(long robotId, String displayName, String roomCode) {

    public ChatRobotUpdatedEvent {
        if (robotId <= 0) {
            throw new IllegalArgumentException("robotId 必须为正数");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName 不能为空");
        }
        Objects.requireNonNull(roomCode, "roomCode");
    }
}
