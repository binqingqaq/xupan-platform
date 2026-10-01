package com.xupan.server.agent.domain;

import java.util.Arrays;

public enum AgentStatus {
    ACTIVE,
    DISABLED;

    public static AgentStatus from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("代理状态不能为空");
        }
        return Arrays.stream(values())
                .filter(status -> status.name().equals(value.trim().toUpperCase()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("代理状态无效"));
    }
}
