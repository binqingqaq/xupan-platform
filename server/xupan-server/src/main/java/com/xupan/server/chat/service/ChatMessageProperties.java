package com.xupan.server.chat.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xupan.chat.message")
public class ChatMessageProperties {

    private int maxRobotPayloadBytes = 64 * 1024;

    public int getMaxRobotPayloadBytes() {
        return maxRobotPayloadBytes;
    }

    public void setMaxRobotPayloadBytes(int maxRobotPayloadBytes) {
        if (maxRobotPayloadBytes < 1024 || maxRobotPayloadBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maxRobotPayloadBytes 必须在 1024 到 1048576 之间");
        }
        this.maxRobotPayloadBytes = maxRobotPayloadBytes;
    }
}
