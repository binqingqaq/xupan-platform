package com.xupan.server.robot.service;

import com.xupan.server.chat.domain.ChatMessageVisibility;
import com.xupan.server.chat.service.ChatContentPolicy;
import com.xupan.server.robot.repository.RobotNoticeIntentRepository;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class RobotNoticeIntentService {

    private final RobotNoticeIntentRepository repository;
    private final ChatContentPolicy contentPolicy;
    private final ObjectMapper objectMapper;

    public RobotNoticeIntentService(RobotNoticeIntentRepository repository,
                                    ChatContentPolicy contentPolicy,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.contentPolicy = contentPolicy;
        this.objectMapper = objectMapper;
    }

    public boolean enqueue(String eventCode, String sourceType, String sourceId,
                           String gameCode, String issueNumber, ChatMessageVisibility visibility,
                           Long agentId, String feedbackType, String content, String idempotencyKey,
                           Instant now) {
        return enqueue(eventCode, sourceType, sourceId, gameCode, issueNumber, visibility,
                agentId, feedbackType, null, content, idempotencyKey, now);
    }

    public boolean enqueue(String eventCode, String sourceType, String sourceId,
                           String gameCode, String issueNumber, ChatMessageVisibility visibility,
                           Long agentId, String feedbackType, Long sourceMessageId,
                           String content, String idempotencyKey, Instant now) {
        String normalized = contentPolicy.normalize(content);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new BusinessNoticePayload(
                    "BUSINESS_NOTICE", eventCode, sourceType, sourceId, feedbackType, sourceMessageId));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成机器人业务通知快照失败", exception);
        }
        return repository.enqueue(eventCode, sourceType, sourceId, gameCode, issueNumber,
                visibility, agentId, normalized, payload, idempotencyKey, now);
    }

    public boolean enqueueStructured(String eventCode, String sourceType, String sourceId,
                                     String gameCode, String issueNumber,
                                     ChatMessageVisibility visibility, Long agentId,
                                     String content, String idempotencyKey,
                                     Object payloadData, Instant now) {
        String normalized = contentPolicy.normalize(content);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadData);
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成机器人结构化通知快照失败", exception);
        }
        return repository.enqueue(eventCode, sourceType, sourceId, gameCode, issueNumber,
                visibility, agentId, normalized, payload, idempotencyKey, now);
    }

    public static String stableKey(String prefix, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(prefix).append('-');
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("机器人通知幂等键算法不可用", exception);
        }
    }

    public boolean exists(String idempotencyKey) {
        return repository.existsByKey(idempotencyKey);
    }

    public record BusinessNoticePayload(String eventType, String eventCode,
                                        String sourceType, String sourceId,
                                        String feedbackType, Long sourceMessageId) {
    }
}
