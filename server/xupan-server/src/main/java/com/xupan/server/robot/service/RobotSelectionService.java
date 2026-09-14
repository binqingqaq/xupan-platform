package com.xupan.server.robot.service;

import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotStatus;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/** Selects one enabled robot deterministically for a game event. */
@Component
public final class RobotSelectionService {

    public Optional<ChatRobot> select(List<ChatRobot> enabledRobots, String issueNumber, String eventType) {
        if (enabledRobots == null) {
            throw BusinessException.badRequest("ROBOT_SELECTION_INPUT_INVALID", "机器人列表不能为空");
        }
        requireIdentifier(issueNumber, "期号");
        requireIdentifier(eventType, "事件类型");

        List<ChatRobot> candidates = new ArrayList<>();
        for (ChatRobot robot : enabledRobots) {
            if (robot != null && robot.status() == RobotStatus.ENABLED) {
                if (robot.weight() < 1 || robot.weight() > 100) {
                    throw BusinessException.badRequest("ROBOT_WEIGHT_INVALID", "机器人权重必须在 1 到 100 之间");
                }
                candidates.add(robot);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort(Comparator.comparingLong(ChatRobot::id)
                .thenComparing(ChatRobot::robotCode, Comparator.nullsFirst(String::compareTo)));
        long totalWeight = candidates.stream().mapToLong(ChatRobot::weight).sum();
        CRC32 crc32 = new CRC32();
        crc32.update((issueNumber + "|" + eventType).getBytes(StandardCharsets.UTF_8));
        long slot = crc32.getValue() % totalWeight;
        long cumulative = 0;
        for (ChatRobot robot : candidates) {
            cumulative += robot.weight();
            if (slot < cumulative) {
                return Optional.of(robot);
            }
        }
        throw new IllegalStateException("机器人权重选择未命中候选项");
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('|') >= 0 || containsControl(value)) {
            throw BusinessException.badRequest("ROBOT_SELECTION_INPUT_INVALID", field + "无效");
        }
    }

    private static boolean containsControl(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }
}
