package com.xupan.server.robot.service;

import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotEventType;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class RobotGameEventAdapter {

    public RobotEventType eventType(String value) {
        try {
            return RobotEventType.fromDatabaseValue(value);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("ROBOT_EVENT_TYPE_INVALID", "游戏事件类型不支持");
        }
    }

    public RobotRenderContext renderContext(
            RobotDispatchRepository.UnscheduledGameEvent event,
            ChatRobot robot) {
        if (event == null || robot == null || event.createdAt() == null) {
            throw BusinessException.badRequest("ROBOT_EVENT_CONTEXT_INVALID", "机器人事件上下文无效");
        }
        RobotEventType type = eventType(event.eventType());
        return new RobotRenderContext(event.issueNumber(), type.name(), event.message(),
                robot.displayName(), event.createdAt(), resultNumbers(type, event.message()));
    }

    private static String resultNumbers(RobotEventType type, String message) {
        if (type != RobotEventType.DRAW_RESULT || message == null) {
            return null;
        }
        int marker = message.indexOf("结果:\n");
        if (marker < 0) {
            return null;
        }
        String remainder = message.substring(marker + "结果:\n".length());
        int lineEnd = remainder.indexOf('\n');
        String numbers = lineEnd < 0 ? remainder : remainder.substring(0, lineEnd);
        return numbers.isBlank() ? null : numbers.trim();
    }
}
