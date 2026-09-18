package com.xupan.server.chat.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessagePage;
import com.xupan.server.chat.domain.ChatRoom;
import com.xupan.server.chat.repository.ChatMessageRepository;
import com.xupan.server.chat.repository.ChatMuteRepository;
import com.xupan.server.chat.repository.ChatOutboxRepository;
import com.xupan.server.chat.repository.ChatReadCursorRepository;
import com.xupan.server.chat.repository.ChatRoomRepository;
import com.xupan.server.chat.realtime.ChatMessageCreatedEvent;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.BetTextParser;
import com.xupan.server.game.service.DemoGameService;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ChatMessageService {

    private static final int MAX_ROBOT_PAYLOAD_LENGTH = 20_000;

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatOutboxRepository outboxRepository;
    private final ChatMuteRepository muteRepository;
    private final ChatReadCursorRepository readCursorRepository;
    private final ChatContentPolicy contentPolicy;
    private final GameDataRepository gameDataRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final DemoGameService gameService;

    @Autowired
    public ChatMessageService(UserRepository userRepository,
                              PermissionService permissionService,
                              ChatRoomRepository roomRepository,
                              ChatMessageRepository messageRepository,
                              ChatOutboxRepository outboxRepository,
                              ChatMuteRepository muteRepository,
                              ChatReadCursorRepository readCursorRepository,
                              ChatContentPolicy contentPolicy,
                              GameDataRepository gameDataRepository,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher,
                              DemoGameService gameService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.roomRepository = roomRepository;
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.muteRepository = muteRepository;
        this.readCursorRepository = readCursorRepository;
        this.contentPolicy = contentPolicy;
        this.gameDataRepository = gameDataRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.gameService = gameService;
    }

    /** Compatibility constructor for focused chat unit tests that do not exercise betting. */
    public ChatMessageService(UserRepository userRepository,
                              PermissionService permissionService,
                              ChatRoomRepository roomRepository,
                              ChatMessageRepository messageRepository,
                              ChatOutboxRepository outboxRepository,
                              ChatMuteRepository muteRepository,
                              ChatReadCursorRepository readCursorRepository,
                              ChatContentPolicy contentPolicy,
                              GameDataRepository gameDataRepository,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher) {
        this(userRepository, permissionService, roomRepository, messageRepository, outboxRepository,
                muteRepository, readCursorRepository, contentPolicy, gameDataRepository, objectMapper,
                eventPublisher, null);
    }

    public ChatRoomView getRoom(long userId, String roomCode, Instant now) {
        requirePositiveUser(userId);
        requireActiveUser(userId);
        ChatRoom room = requireRoom(roomCode);
        String currentIssue = gameDataRepository.findCurrentIssue()
                .map(GameDataRepository.IssueRecord::issueNumber).orElse(null);
        return new ChatRoomView(room, currentIssue, now);
    }

    public ChatMessagePage history(long userId, String roomCode, Long beforeSequence,
                                   Long afterSequence, int limit, Instant now) {
        requirePositiveUser(userId);
        requireActiveUser(userId);
        if (beforeSequence != null && afterSequence != null) {
            throw BusinessException.badRequest("CHAT_CURSOR_INVALID", "不能同时使用前后游标");
        }
        if (beforeSequence != null && beforeSequence < 1 || afterSequence != null && afterSequence < 0) {
            throw BusinessException.badRequest("CHAT_CURSOR_INVALID", "消息游标无效");
        }
        int pageSize = contentPolicy.validateLimit(limit);
        ChatRoom room = requireRoom(roomCode);
        if (beforeSequence != null && beforeSequence > room.nextSequenceNo() + 1
                || afterSequence != null && afterSequence > room.nextSequenceNo()) {
            throw BusinessException.badRequest("CHAT_CURSOR_INVALID", "消息游标超过房间当前序号");
        }

        List<ChatMessage> queried;
        boolean before = beforeSequence != null || afterSequence == null;
        if (afterSequence != null) {
            queried = messageRepository.findAfterSequence(room.id(), afterSequence, pageSize);
        } else {
            long cursor = beforeSequence == null ? room.nextSequenceNo() + 1 : beforeSequence;
            queried = messageRepository.findBeforeSequence(room.id(), cursor, pageSize);
        }
        boolean hasMore = queried.size() > pageSize;
        List<ChatMessage> items = new ArrayList<>(queried.subList(0, Math.min(pageSize, queried.size())));
        if (before) {
            Collections.reverse(items);
        }
        Long nextBefore = items.isEmpty() ? null : items.get(0).sequenceNo();
        Long nextAfter = items.isEmpty() ? null : items.get(items.size() - 1).sequenceNo();
        return new ChatMessagePage(items, nextBefore, nextAfter, hasMore);
    }

    @Transactional
    public ChatMessage sendUserMessage(long userId, String roomCode, String clientMessageId,
                                       String content, Instant now) {
        return sendUserMessageWithOutcome(userId, roomCode, clientMessageId, content, now).message();
    }

    @Transactional
    public ChatMessageSendOutcome sendUserMessageWithOutcome(long userId, String roomCode,
                                                              String clientMessageId, String content,
                                                              Instant now) {
        requirePositiveUser(userId);
        UserAccount user = requireActiveUser(userId);
        String clientId = contentPolicy.requireClientMessageId(clientMessageId);
        String normalizedContent = contentPolicy.normalize(content);
        ChatRoom room = requireRoomForUpdate(roomCode);
        if (!room.isOpen()) {
            throw BusinessException.conflict("CHAT_ROOM_CLOSED", "聊天室当前不接受新消息");
        }
        if (muteRepository.isMuted(room.id(), userId, now)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CHAT_USER_MUTED", "当前用户处于禁言状态");
        }
        Optional<ChatMessage> existing = messageRepository.findByClientMessageId(room.id(), userId, clientId);
        if (existing.isPresent()) {
            if (!Objects.equals(existing.get().content(), normalizedContent)) {
                throw BusinessException.conflict("CHAT_IDEMPOTENCY_CONFLICT", "客户端消息标识对应的正文不一致");
            }
            return new ChatMessageSendOutcome(existing.get(), true);
        }
        BetTextParser.ParseResult parsedBet = BetTextParser.parse(normalizedContent);
        if (parsedBet.status() != BetTextParser.Status.ACCEPTED
                && BetTextParser.looksLikeBet(normalizedContent)) {
            throw BusinessException.badRequest("GAME_BET_TEXT_INVALID", parsedBet.reason());
        }
        BetTextParser.ParsedBet bet = parsedBet.bet();
        if (bet != null) {
            if (gameService == null) {
                throw new IllegalStateException("下注服务未配置");
            }
            DemoGameService.BetView placed = gameService.placeBet(userId, new PlaceBetRequest(
                    1, bet.playType(), bet.parameters(), bet.stake(), "CHAT-" + clientId));
            long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
            String betPayload = betPayload(placed);
            ChatMessage message = messageRepository.insertUserBetMessage(room.id(), sequence, userId,
                    user.displayName(), clientId, placed.issueNumber(), normalizedContent, betPayload, now);
            outboxRepository.insertMessageCreatedOutbox(message.id(), outboxPayload(message), now);
            eventPublisher.publishEvent(new ChatMessageCreatedEvent(message));
            return new ChatMessageSendOutcome(message, false);
        }
        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                user.displayName(), clientId, normalizedContent, now);
        outboxRepository.insertMessageCreatedOutbox(message.id(), outboxPayload(message), now);
        eventPublisher.publishEvent(new ChatMessageCreatedEvent(message));
        return new ChatMessageSendOutcome(message, false);
    }

    private String betPayload(DemoGameService.BetView bet) {
        try {
            return objectMapper.writeValueAsString(new BetMessagePayload(
                    bet.id(), bet.issueNumber(), bet.ballNumber(), bet.playType().name(),
                    bet.parameters(), bet.stake(), bet.odds(), bet.settlementStatus().name()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成下注消息追踪数据失败", exception);
        }
    }

    @Transactional
    public ChatMessage publishRobotMessage(long robotId, String robotName, String roomCode,
                                           String issueNumber, String idempotencyKey, String content,
                                           String payloadJson, Instant createdAt) {
        requirePositiveRobot(robotId);
        String normalizedRobotName = requireRobotName(robotName);
        String normalizedRoomCode = requireRoomCode(roomCode);
        String normalizedIssueNumber = requireRobotText(issueNumber, "CHAT_ROBOT_ISSUE_INVALID",
                "机器人期号无效", 64);
        String normalizedIdempotencyKey = requireRobotText(idempotencyKey,
                "CHAT_ROBOT_IDEMPOTENCY_KEY_INVALID", "机器人幂等键无效", 128);
        String normalizedContent = contentPolicy.normalize(content);
        String normalizedPayloadJson = requirePayloadJson(payloadJson);
        if (createdAt == null) {
            throw BusinessException.badRequest("CHAT_ROBOT_TIME_INVALID", "机器人消息时间不能为空");
        }

        Optional<ChatMessage> existing = messageRepository.findByIdempotencyKey(normalizedIdempotencyKey);
        if (existing.isPresent()) {
            return requireSameRobotMessage(existing.get(), robotId, normalizedRobotName,
                    normalizedRoomCode, normalizedIssueNumber, normalizedContent, normalizedPayloadJson);
        }

        ChatRoom room = requireRoomForUpdate(normalizedRoomCode);
        existing = messageRepository.findByIdempotencyKey(normalizedIdempotencyKey);
        if (existing.isPresent()) {
            return requireSameRobotMessage(existing.get(), robotId, normalizedRobotName,
                    normalizedRoomCode, normalizedIssueNumber, normalizedContent, normalizedPayloadJson);
        }

        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertRobotMessage(room.id(), sequence, robotId,
                normalizedRobotName, normalizedIssueNumber, normalizedIdempotencyKey,
                normalizedContent, normalizedPayloadJson, createdAt);
        outboxRepository.insertMessageCreatedOutbox(message.id(), outboxPayload(message), createdAt);
        eventPublisher.publishEvent(new ChatMessageCreatedEvent(message));
        return message;
    }

    @Transactional
    public void saveReadCursor(long userId, String roomCode, long lastReadSequence, Instant now) {
        requirePositiveUser(userId);
        requireActiveUser(userId);
        if (!permissionService.hasPermission(userId, "CHAT_ROOM_READ")) {
            throw BusinessException.forbidden("AUTH_PERMISSION_DENIED", "当前账号没有聊天室查看权限");
        }
        ChatRoom room = requireRoomForUpdate(roomCode);
        if (lastReadSequence < 0 || lastReadSequence > room.nextSequenceNo()) {
            throw BusinessException.badRequest("CHAT_CURSOR_INVALID", "已读游标超过房间当前序号");
        }
        readCursorRepository.saveReadSequence(room.id(), userId, lastReadSequence, now);
    }

    private String outboxPayload(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(new OutboxMessage(message.id(), message.roomCode(),
                    message.sequenceNo(), message.messageType().name(), message.senderType().name(),
                    message.senderId(), message.senderName(), message.content(), message.createdAt().toString()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成聊天 outbox 事件失败", exception);
        }
    }

    private UserAccount requireActiveUser(long userId) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("CHAT_USER_NOT_FOUND", "用户不存在"));
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CHAT_USER_DISABLED", "当前用户不可使用聊天室");
        }
        return user;
    }

    private ChatRoom requireRoom(String roomCode) {
        String code = requireRoomCode(roomCode);
        return roomRepository.findByCode(code)
                .orElseThrow(() -> BusinessException.notFound("CHAT_ROOM_NOT_FOUND", "聊天室不存在"));
    }

    private ChatRoom requireRoomForUpdate(String roomCode) {
        String code = requireRoomCode(roomCode);
        return roomRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> BusinessException.notFound("CHAT_ROOM_NOT_FOUND", "聊天室不存在"));
    }

    private static String requireRoomCode(String roomCode) {
        if (roomCode == null || roomCode.isBlank() || roomCode.length() > 64
                || !roomCode.matches("[A-Za-z0-9_-]+")) {
            throw BusinessException.badRequest("CHAT_ROOM_CODE_INVALID", "聊天室编码无效");
        }
        return roomCode;
    }

    private String requireRobotName(String robotName) {
        String normalized = contentPolicy.normalize(robotName);
        if (normalized.length() > 128) {
            throw BusinessException.badRequest("CHAT_ROBOT_NAME_INVALID", "机器人名称不能超过 128 个字符");
        }
        return normalized;
    }

    private static String requireRobotText(String value, String code, String message, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw BusinessException.badRequest(code, message);
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw BusinessException.badRequest(code, message);
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private String requirePayloadJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()
                || payloadJson.length() > MAX_ROBOT_PAYLOAD_LENGTH) {
            throw BusinessException.badRequest("CHAT_ROBOT_PAYLOAD_INVALID", "机器人追踪数据无效");
        }
        try {
            objectMapper.readTree(payloadJson);
            return payloadJson;
        } catch (JacksonException exception) {
            throw BusinessException.badRequest("CHAT_ROBOT_PAYLOAD_INVALID", "机器人追踪数据必须是合法 JSON");
        }
    }

    private ChatMessage requireSameRobotMessage(ChatMessage existing, long robotId, String robotName,
                                                String roomCode, String issueNumber, String content,
                                                String payloadJson) {
        if (existing.messageType() != com.xupan.server.chat.domain.ChatMessageType.ROBOT
                || existing.senderType() != com.xupan.server.chat.domain.ChatSenderType.ROBOT
                || !Objects.equals(existing.senderId(), robotId)
                || !Objects.equals(existing.senderName(), robotName)
                || !Objects.equals(existing.roomCode(), roomCode)
                || !Objects.equals(existing.issueNumber(), issueNumber)
                || !Objects.equals(existing.content(), content)
                || !sameJson(existing.payloadJson(), payloadJson)) {
            throw BusinessException.conflict("CHAT_ROBOT_IDEMPOTENCY_CONFLICT",
                    "机器人幂等键对应的消息参数不一致");
        }
        return existing;
    }

    private boolean sameJson(String left, String right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        try {
            return objectMapper.readTree(left).toString().equals(objectMapper.readTree(right).toString());
        } catch (JacksonException exception) {
            return false;
        }
    }

    private static void requirePositiveRobot(long robotId) {
        if (robotId <= 0) {
            throw BusinessException.badRequest("CHAT_ROBOT_INVALID", "机器人标识无效");
        }
    }

    private static void requirePositiveUser(long userId) {
        if (userId <= 0) {
            throw BusinessException.badRequest("CHAT_USER_INVALID", "用户标识无效");
        }
    }

    public record ChatRoomView(ChatRoom room, String currentIssueNumber, Instant serverNow) {
    }

    public record ChatMessageSendOutcome(ChatMessage message, boolean deduplicated) {
    }

    private record OutboxMessage(long messageId, String roomCode, long sequenceNo,
                                 String messageType, String senderType, Long senderId,
                                 String senderName, String content, String createdAt) {
    }

    private record BetMessagePayload(String betId, String issueNumber, int ballNumber,
                                     String playType, List<Integer> parameters,
                                     java.math.BigDecimal stake, java.math.BigDecimal odds,
                                     String settlementStatus) {
    }
}
