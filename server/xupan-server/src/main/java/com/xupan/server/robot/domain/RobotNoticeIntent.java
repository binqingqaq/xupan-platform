package com.xupan.server.robot.domain;

import com.xupan.server.chat.domain.ChatMessageVisibility;

import java.time.Instant;

public record RobotNoticeIntent(long id, String eventCode, String sourceType, String sourceId,
                                String gameCode, String issueNumber,
                                ChatMessageVisibility visibility, Long agentId,
                                String content, String payloadJson, String idempotencyKey,
                                String status, int attemptCount, Instant nextAttemptAt,
                                Instant lockedUntil, Long messageId, String lastError,
                                Instant createdAt, Instant updatedAt) {
}
