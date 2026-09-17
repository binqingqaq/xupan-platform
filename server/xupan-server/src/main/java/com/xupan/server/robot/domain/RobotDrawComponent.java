package com.xupan.server.robot.domain;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public enum RobotDrawComponent {
    DRAW_SUMMARY,
    DRAW_HISTORY,
    WINNER_LIST;

    public static List<RobotDrawComponent> defaults() {
        return Arrays.stream(values()).sorted(Comparator.comparingInt(RobotDrawComponent::defaultOrder))
                .toList();
    }

    public int defaultOrder() {
        return ordinal() + 1;
    }

    public static RobotDrawComponent fromDatabaseValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ROBOT_DRAW_COMPONENT_INVALID: 组件不能为空");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("ROBOT_DRAW_COMPONENT_INVALID: 组件不支持: " + value);
        }
    }
}
