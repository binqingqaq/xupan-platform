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
        Instant updatedAt,
        ChatMessageVisibility visibility,
        Long agentId
) {
    public ChatMessage(long id, long roomId, String roomCode, long sequenceNo,
                       String clientMessageId, String idempotencyKey, String issueNumber,
                       ChatMessageType messageType, ChatSenderType senderType, Long senderId,
                       String senderName, String content, String payloadJson, ChatMessageStatus status,
                       Instant createdAt, Instant updatedAt) {
        this(id, roomId, roomCode, sequenceNo, clientMessageId, idempotencyKey, issueNumber,
                messageType, senderType, senderId, senderName, content, payloadJson, status,
                createdAt, updatedAt, ChatMessageVisibility.PUBLIC, null);
    }
}
