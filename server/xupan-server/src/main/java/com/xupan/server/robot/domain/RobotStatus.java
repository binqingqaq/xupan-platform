package com.xupan.server.robot.domain;

public enum RobotStatus {
    ENABLED,
    DISABLED;

    public static RobotStatus fromDatabaseValue(String value) {
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
        return new IllegalArgumentException("ROBOT_STATUS_INVALID: 数据库字段 status 的值无效: " + value);
    }
}
