package com.xupan.server.robot.domain;

public enum RobotEventType {
    ISSUE_STARTED,
    BETTING_WARNING,
    BETTING_CLOSED,
    DRAW_RESULT;

    public static RobotEventType fromDatabaseValue(String value) {
        if (value == null) {
            throw invalid(value);
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(value);
        }
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException(
                "ROBOT_EVENT_TYPE_INVALID: 数据库字段 event_type 的值无效: " + value);
    }
}
