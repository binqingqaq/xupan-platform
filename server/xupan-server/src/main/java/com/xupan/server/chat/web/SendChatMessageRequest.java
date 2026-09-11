package com.xupan.server.chat.web;

public record SendChatMessageRequest(String clientMessageId, String content) {
}
