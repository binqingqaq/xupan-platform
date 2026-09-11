package com.xupan.server.chat.web;

import com.xupan.server.chat.domain.ChatMessage;

import java.time.Instant;

public record ChatMessageResponse(
        long id,
        long sequenceNo,
        String clientMessageId,
        String messageType,
        String senderType,
        Long senderId,
        String senderName,
        String content,
        String payloadJson,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.id(), message.sequenceNo(), message.clientMessageId(),
                message.messageType().name(), message.senderType().name(), message.senderId(),
                message.senderName(), message.content(), message.payloadJson(), message.status().name(),
                message.createdAt(), message.updatedAt());
    }
}
