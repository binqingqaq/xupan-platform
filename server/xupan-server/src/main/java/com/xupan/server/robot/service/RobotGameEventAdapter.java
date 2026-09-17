package com.xupan.server.robot.service;

import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotEventType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class RobotGameEventAdapter {

    private final GameDataRepository gameDataRepository;

    public RobotGameEventAdapter(GameDataRepository gameDataRepository) {
        this.gameDataRepository = gameDataRepository;
    }

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
                robot.displayName(), event.createdAt(), resultNumbers(type, event.issueNumber()));
    }

    private String resultNumbers(RobotEventType type, String issueNumber) {
        if (type != RobotEventType.DRAW_RESULT) {
            return null;
        }
        return gameDataRepository.findIssueByIssueNumber(issueNumber)
                .filter(issue -> issue.numbers().stream().allMatch(java.util.Objects::nonNull))
                .map(issue -> issue.numbers().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")))
                .orElse(null);
    }
}
