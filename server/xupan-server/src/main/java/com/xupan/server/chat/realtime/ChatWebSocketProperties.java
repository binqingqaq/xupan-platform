package com.xupan.server.chat.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "xupan.chat.websocket")
public class ChatWebSocketProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://127.0.0.1:5173", "http://localhost:5173"));
    private int maxConnectionsPerUser = 3;
    private int maxConnections = 5000;
    private Duration heartbeatInterval = Duration.ofSeconds(20);
    private Duration heartbeatTimeout = Duration.ofSeconds(30);
    private int maxPendingMessages = 100;
    private int maxMessageBytes = ChatProtocol.MAX_FRAME_BYTES;
    private int maxOutboundMessageBytes = 64 * 1024;
    private int maxMessagesPerWindow = 10;
    private Duration messageRateWindow = Duration.ofSeconds(10);

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        List<String> values = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .toList();
        if (values.isEmpty() || values.stream().anyMatch("*"::equals)) {
            throw new IllegalArgumentException("allowedOrigins 必须配置明确的来源，不能使用 *");
        }
        this.allowedOrigins = new ArrayList<>(values);
    }

    public int getMaxConnectionsPerUser() {
        return maxConnectionsPerUser;
    }

    public void setMaxConnectionsPerUser(int maxConnectionsPerUser) {
        this.maxConnectionsPerUser = positive(maxConnectionsPerUser, "maxConnectionsPerUser");
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = positive(maxConnections, "maxConnections");
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
    }

    public Duration getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(Duration heartbeatTimeout) {
        this.heartbeatTimeout = positive(heartbeatTimeout, "heartbeatTimeout");
    }

    public int getMaxPendingMessages() {
        return maxPendingMessages;
    }

    public void setMaxPendingMessages(int maxPendingMessages) {
        this.maxPendingMessages = positive(maxPendingMessages, "maxPendingMessages");
    }

    public int getMaxMessageBytes() {
        return maxMessageBytes;
    }

    public void setMaxMessageBytes(int maxMessageBytes) {
        this.maxMessageBytes = positive(maxMessageBytes, "maxMessageBytes");
    }

    public int getMaxOutboundMessageBytes() {
        return maxOutboundMessageBytes;
    }

    public void setMaxOutboundMessageBytes(int maxOutboundMessageBytes) {
        this.maxOutboundMessageBytes = positive(maxOutboundMessageBytes, "maxOutboundMessageBytes");
    }

    public int getMaxMessagesPerWindow() {
        return maxMessagesPerWindow;
    }

    public void setMaxMessagesPerWindow(int maxMessagesPerWindow) {
        this.maxMessagesPerWindow = positive(maxMessagesPerWindow, "maxMessagesPerWindow");
    }

    public Duration getMessageRateWindow() {
        return messageRateWindow;
    }

    public void setMessageRateWindow(Duration messageRateWindow) {
        this.messageRateWindow = positive(messageRateWindow, "messageRateWindow");
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }
}
