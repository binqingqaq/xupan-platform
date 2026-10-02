package com.xupan.server.chat.web;

public record SendChatMessageRequest(String clientMessageId, String content, String gameCode) {
    public SendChatMessageRequest(String clientMessageId, String content) {
        this(clientMessageId, content, null);
    }

    public String normalizedGameCode() {
        return gameCode == null || gameCode.isBlank() ? "AU8" : gameCode.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
