package com.xupan.server.chat.domain;

import java.time.Instant;

public record ChatMessage(
        long id,
        long roomId,
        String roomCode,
        long sequenceNo,
        String clientMessageId,
        String idempotencyKey,
        String issueNumber,
        ChatMessageType messageType,
        ChatSenderType senderType,
        Long senderId,
        String senderName,
        String content,
        String payloadJson,
        ChatMessageStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
