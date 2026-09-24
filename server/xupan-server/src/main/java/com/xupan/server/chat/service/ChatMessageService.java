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
import com.xupan.server.chat.repository.PlayerPointRequestRepository;
import com.xupan.server.chat.repository.ChatReadCursorRepository;
import com.xupan.server.chat.repository.ChatRoomRepository;
import com.xupan.server.chat.realtime.ChatMessageCreatedEvent;
import com.xupan.server.chat.realtime.ChatProtocol;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.service.BetTextParser;
import com.xupan.server.game.service.BetSettlementCompletedEvent;
import com.xupan.server.game.service.BetLimitExceededException;
import com.xupan.server.game.service.DemoGameService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.repository.RobotRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ChatMessageService {

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
    private final ChatMessageProperties messageProperties;
    private final RobotRepository robotRepository;
    private final VirtualWalletService walletService;
    private final TransactionTemplate transactionTemplate;
    private PlayerPointRequestRepository pointRequestRepository;

    private static final String FEEDBACK_ROBOT_CODE = "issue-helper";
    private static final String ROOM_CODE = "main";

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
                              DemoGameService gameService,
                              ChatMessageProperties messageProperties,
                              RobotRepository robotRepository,
                              VirtualWalletService walletService,
                              TransactionTemplate transactionTemplate) {
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
        this.messageProperties = messageProperties;
        this.robotRepository = robotRepository;
        this.walletService = walletService;
        this.transactionTemplate = transactionTemplate;
    }

    @Autowired(required = false)
    public void setPointRequestRepository(PlayerPointRequestRepository pointRequestRepository) {
        this.pointRequestRepository = pointRequestRepository;
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
                              ApplicationEventPublisher eventPublisher,
                              DemoGameService gameService) {
        this(userRepository, permissionService, roomRepository, messageRepository, outboxRepository,
                muteRepository, readCursorRepository, contentPolicy, gameDataRepository, objectMapper,
                eventPublisher, gameService, new ChatMessageProperties(), null, null, null);
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
                eventPublisher, null, new ChatMessageProperties(), null, null, null);
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

    public ChatMessage sendUserMessage(long userId, String roomCode, String clientMessageId,
                                       String content, Instant now) {
        return sendUserMessageWithOutcome(userId, roomCode, clientMessageId, content, now).message();
    }

    public ChatMessageSendOutcome sendUserMessageWithOutcome(long userId, String roomCode,
                                                              String clientMessageId, String content,
                                                              Instant now) {
        try {
            return inTransaction(() -> processNewUserMessage(userId, roomCode, clientMessageId, content, now));
        } catch (BetRejectedException rejected) {
            // DemoGameService deliberately rolls back its bet/wallet transaction on a business rejection.
            // Persist the original input and its public feedback in a fresh transaction afterwards.
            return inTransaction(() -> persistRejectedBet(userId, roomCode, clientMessageId, content, now,
                    rejected.cause()));
        }
    }

    private ChatMessageSendOutcome processNewUserMessage(long userId, String roomCode,
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
        Optional<ChatCommandParser.Command> command = ChatCommandParser.parse(normalizedContent);
        if (command.isPresent()) {
            ChatMessage message = messageRepository.insertUserMessage(room.id(),
                    roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo()), userId,
                    user.displayName(), clientId, normalizedContent, now);
            publishUserMessage(message, now);
            return processCommand(userId, roomCode, clientId, user, message, command.get(), now);
        }

        BetTextParser.ParseResult parsedBet = BetTextParser.parse(normalizedContent);
        List<BetTextParser.ParsedBet> bets = parsedBet.bets();
        if (!bets.isEmpty()) {
            if (gameService == null) {
                throw new IllegalStateException("下注服务未配置");
            }
            List<DemoGameService.BetView> placedBets = new ArrayList<>(bets.size());
            List<BetLimitExceededException> rejectedBets = new ArrayList<>();
            for (int index = 0; index < bets.size(); index++) {
                BetTextParser.ParsedBet bet = bets.get(index);
                try {
                    String idempotencyKey = bets.size() == 1
                            ? "CHAT-" + clientId
                            : "CHAT-" + clientId + "-" + (index + 1);
                    placedBets.add(gameService.placeBet(userId, new PlaceBetRequest(
                            1, bet.playType(), bet.parameters(), bet.stake(), idempotencyKey)));
                } catch (BetLimitExceededException rejection) {
                    // Only the offending item is dropped; the remaining items keep their normal path.
                    rejectedBets.add(rejection);
                } catch (BusinessException exception) {
                    throw new BetRejectedException(exception);
                }
            }
            long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
            if (placedBets.isEmpty()) {
                ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                        user.displayName(), clientId, normalizedContent, now);
                publishUserMessage(message, now);
                publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(),
                        "BET_LIMIT_REJECTED", "@" + user.displayName() + "  下注未成功"
                                + limitRejectionLines(user.displayName(), rejectedBets), now);
                return new ChatMessageSendOutcome(message, false);
            }
            DemoGameService.BetView firstBet = placedBets.get(0);
            String betPayload = betPayload(placedBets);
            ChatMessage message = messageRepository.insertUserBetMessage(room.id(), sequence, userId,
                    user.displayName(), clientId, firstBet.issueNumber(), normalizedContent, betPayload, now);
            publishUserMessage(message, now);
            if (walletService == null || robotRepository == null) {
                throw new IllegalStateException("下注反馈服务未配置");
            }
            java.math.BigDecimal totalStake = placedBets.stream()
                    .map(DemoGameService.BetView::stake)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            String remainingBalance = formatMoney(walletService.getForCurrentUser(userId).balance());
            publishFeedback(roomCode, firstBet.issueNumber(), clientId, message.id(),
                    "BET_ACCEPTED", "@" + user.displayName() + "  攻击成功，使用粮草"
                            + formatMoney(totalStake) + ", 剩余粮草：" + remainingBalance
                            + limitRejectionLines(user.displayName(), rejectedBets), now);
            return new ChatMessageSendOutcome(message, false);
        }
        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                user.displayName(), clientId, normalizedContent, now);
        publishUserMessage(message, now);
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(),
                "INVALID_COMMAND", "@" + user.displayName() + ", 指令格式不正确!", now);
        return new ChatMessageSendOutcome(message, false);
    }

    private ChatMessageSendOutcome processCommand(long userId, String roomCode, String clientId,
                                                  UserAccount user, ChatMessage source,
                                                  ChatCommandParser.Command command, Instant now) {
        String issueNumber = feedbackIssueNumber();
        switch (command.type()) {
            case RULES -> {
                return new ChatMessageSendOutcome(source, false);
            }
            case BALANCE -> {
                VirtualWallet balance = currentBalance(userId);
                publishFeedback(roomCode, issueNumber, clientId, source.id(), "BALANCE",
                        "@" + user.displayName() + "   剩余粮草：" + formatMoney(balance.balance()), now);
            }
            case DAILY_SUMMARY -> {
                VirtualWallet balance = currentBalance(userId);
                DayRange range = currentBusinessDay(now);
                GameDataRepository.BetDayStatistics statistics = gameDataRepository.findBetDayStatistics(
                        balance.accountId(), range.fromInclusive(), range.toExclusive());
                long issues = gameDataRepository.countBetIssues(balance.accountId(),
                        range.fromInclusive(), range.toExclusive());
                publishFeedback(roomCode, issueNumber, clientId, source.id(), "DAILY_SUMMARY",
                        "@" + user.displayName() + " 累计今日流水：" + formatMoney(statistics.turnover())
                                + "，盈亏：" + formatMoney(statistics.netProfit()) + "，期数：" + issues, now);
            }
            case TOP_UP, DOWN -> processPointRequest(userId, roomCode, clientId, user, source, command, now);
            case CANCEL -> processCancellation(userId, roomCode, clientId, user, source, now);
        }
        return new ChatMessageSendOutcome(source, false);
    }

    private void processPointRequest(long userId, String roomCode, String clientId, UserAccount user,
                                     ChatMessage source, ChatCommandParser.Command command, Instant now) {
        if (pointRequestRepository == null) {
            throw new IllegalStateException("上下分申请服务未配置");
        }
        VirtualWallet balance = currentBalance(userId);
        if (command.type() == ChatCommandParser.Type.DOWN
                && balance.balance().compareTo(command.amount()) < 0) {
            publishFeedback(roomCode, feedbackIssueNumber(), clientId, source.id(), "POINT_REQUEST_REJECTED",
                    "@" + user.displayName() + " 您当前积分不足" + formatMoney(command.amount()), now);
            return;
        }
        pointRequestRepository.insertPending(userId, command.type().name(), command.amount(),
                clientId, source.id(), now);
        String action = command.type() == ChatCommandParser.Type.TOP_UP ? "上分" : "下分";
        String content = "@" + user.displayName() + " " + action + formatMoney(command.amount()) + "，待审批！";
        if (command.type() == ChatCommandParser.Type.DOWN) {
            content += "，剩余" + formatMoney(balance.balance());
        }
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, source.id(), "POINT_REQUEST_CREATED",
                content, now);
    }

    private void processCancellation(long userId, String roomCode, String clientId, UserAccount user,
                                     ChatMessage source, Instant now) {
        if (gameService == null) {
            throw new IllegalStateException("撤单服务未配置");
        }
        DemoGameService.CancellationResult result = gameService.cancelCurrentBets(userId, now);
        String content = switch (result.status()) {
            case STOPPED -> "@" + user.displayName() + " 本期已停止，禁止取消！";
            case LAST_THIRTY_SECONDS -> "@" + user.displayName() + " 30秒以内禁止取消！";
            case NO_ELIGIBLE_BETS -> "@" + user.displayName() + " 10秒内无有效指令！";
            case CANCELED -> cancellationContent(user.displayName(), result);
        };
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, source.id(), "CANCEL_RESULT", content, now);
    }

    private static String cancellationContent(String displayName, DemoGameService.CancellationResult result) {
        StringBuilder content = new StringBuilder("@").append(displayName)
                .append(" 10秒内有效指令已全部取消");
        for (DemoGameService.CancellationItem item : result.items()) {
            GameDataRepository.BetRecord bet = item.bet();
            content.append('\n').append('@').append(displayName).append(" 取消 ")
                    .append(formatBetText(bet.playType(), bet.parameters(), bet.stake()))
                    .append("，返回").append(formatMoney(bet.stake()))
                    .append("，剩余粮草").append(formatMoney(item.balanceAfter()));
        }
        return content.toString();
    }

    private ChatMessageSendOutcome persistRejectedBet(long userId, String roomCode,
                                                       String clientMessageId, String content,
                                                       Instant now, BusinessException rejection) {
        requirePositiveUser(userId);
        UserAccount user = requireActiveUser(userId);
        String clientId = contentPolicy.requireClientMessageId(clientMessageId);
        String normalizedContent = contentPolicy.normalize(content);
        ChatRoom room = requireRoomForUpdate(roomCode);
        if (!room.isOpen()) {
            throw BusinessException.conflict("CHAT_ROOM_CLOSED", "聊天室当前不接受新消息");
        }
        Optional<ChatMessage> existing = messageRepository.findByClientMessageId(room.id(), userId, clientId);
        if (existing.isPresent()) {
            if (!Objects.equals(existing.get().content(), normalizedContent)) {
                throw BusinessException.conflict("CHAT_IDEMPOTENCY_CONFLICT", "客户端消息标识对应的正文不一致");
            }
            return new ChatMessageSendOutcome(existing.get(), true);
        }
        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                user.displayName(), clientId, normalizedContent, now);
        publishUserMessage(message, now);
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(),
                "BET_REJECTED", rejectedFeedback(user.displayName(), rejection), now);
        return new ChatMessageSendOutcome(message, false);
    }

    private void publishUserMessage(ChatMessage message, Instant createdAt) {
        outboxRepository.insertMessageCreatedOutbox(message.id(), outboxPayload(message), createdAt);
        eventPublisher.publishEvent(new ChatMessageCreatedEvent(message));
    }

    private void publishFeedback(String roomCode, String issueNumber, String clientMessageId,
                                 long sourceMessageId, String feedbackType, String content,
                                 Instant createdAt) {
        if (robotRepository == null) {
            throw new IllegalStateException("下注反馈服务未配置");
        }
        ChatRobot robot = robotRepository.findByCode(FEEDBACK_ROBOT_CODE)
                .orElseThrow(() -> new IllegalStateException("反馈机器人未配置"));
        publishRobotMessage(robot.id(), robot.displayName(), roomCode, issueNumber,
                feedbackIdempotencyKey(roomCode, sourceMessageId, clientMessageId), content,
                feedbackPayload(sourceMessageId, feedbackType), createdAt);
    }

    private String feedbackPayload(long sourceMessageId, String feedbackType) {
        try {
            return objectMapper.writeValueAsString(new FeedbackPayload(
                    "USER_INPUT_FEEDBACK", feedbackType, sourceMessageId));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成用户输入反馈追踪数据失败", exception);
        }
    }

    private String settlementFeedbackPayload(BetSettlementCompletedEvent event) {
        try {
            return objectMapper.writeValueAsString(new SettlementFeedbackPayload(
                    "SETTLEMENT_FEEDBACK", event.betId(), event.status().name(),
                    event.issueNumber(), event.returnAmount(), event.netProfit()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成结算反馈追踪数据失败", exception);
        }
    }

    private String feedbackIssueNumber() {
        return gameDataRepository.findCurrentIssue()
                .map(GameDataRepository.IssueRecord::issueNumber)
                .or(() -> gameDataRepository.findLatestIssue().map(GameDataRepository.IssueRecord::issueNumber))
                .orElse("UNKNOWN");
    }

    private VirtualWallet currentBalance(long userId) {
        if (walletService == null) {
            throw new IllegalStateException("钱包服务未配置");
        }
        return walletService.getForCurrentUser(userId);
    }

    private static DayRange currentBusinessDay(Instant now) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime local = now.atZone(zone);
        LocalDate date = local.toLocalTime().isBefore(LocalTime.of(6, 0))
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        ZonedDateTime start = date.atTime(6, 0).atZone(zone);
        return new DayRange(start.toInstant(), start.plusDays(1).toInstant());
    }

    private static String formatBetText(PlayType playType, List<Integer> parameters,
                                        java.math.BigDecimal stake) {
        String amount = formatMoney(stake);
        String values = parameters.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining());
        return switch (playType) {
            case FAN -> values + "番" + amount;
            case ANGLE -> values + "/" + amount;
            case CAR -> formatCar(parameters, amount);
            case STRICT -> values.charAt(0) + "念" + values.charAt(1) + "/" + amount;
            case ADD -> values.charAt(0) + "加" + values.substring(1) + "/" + amount;
            case POSITIVE -> values + "正" + amount;
            case TONG -> values.substring(0, 1) + "通" + values.substring(1) + "/" + amount;
            case NONE -> formatNone(parameters, amount);
            case ODD_EVEN -> (parameters.get(0) == 1 ? "单" : "双") + amount;
            case BIG_SMALL -> (parameters.get(0) == 1 ? "大" : "小") + amount;
            case SPECIAL -> parameters.stream().map(value -> String.format("%02d", value))
                    .collect(java.util.stream.Collectors.joining("/")) + "特" + amount;
        };
    }

    private static String formatCar(List<Integer> parameters, String amount) {
        int missingFan = java.util.stream.IntStream.rangeClosed(1, 4)
                .filter(value -> !parameters.contains(value))
                .findFirst()
                .orElse(0);
        return missingFan + "车" + amount;
    }

    private static String formatNone(List<Integer> parameters, String amount) {
        if (parameters.size() == 2) {
            return parameters.get(0) + "无" + parameters.get(1) + "/" + amount;
        }
        return parameters.get(0) + String.valueOf(parameters.get(1))
                + "无" + parameters.get(2) + "/" + amount;
    }

    private static String rejectedFeedback(String displayName, BusinessException exception) {
        String prefix = "@" + displayName + ", ";
        return switch (exception.code()) {
            case "WALLET_INSUFFICIENT_BALANCE" -> prefix + "余额不足!";
            case "GAME_BETTING_CLOSED" -> exception.publicMessage().contains("正在开奖")
                    ? prefix + "当前正在开奖，下注无效!"
                    : prefix + "当前已封盘，下注无效!";
            default -> prefix + "下注失败：" + exception.publicMessage();
        };
    }

    private static String limitRejectionLines(String displayName,
                                              List<BetLimitExceededException> rejections) {
        StringBuilder content = new StringBuilder();
        for (BetLimitExceededException rejection : rejections) {
            content.append('\n').append('@').append(displayName).append("  下注 ")
                    .append(formatBetText(rejection.playType(), rejection.parameters(), rejection.stake()))
                    .append(" 已拒绝：").append(rejection.reason());
        }
        return content.toString();
    }

    private static String formatMoney(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record DayRange(Instant fromInclusive, Instant toExclusive) {
    }

    private static String settlementFeedback(String displayName,
                                             java.math.BigDecimal balance,
                                             BetSettlementCompletedEvent event) {
        String prefix = "@" + displayName + ", ";
        String currentBalance = formatMoney(balance);
        return switch (event.status()) {
            case WIN -> prefix + "第" + event.issueNumber() + "期中奖，返还粮草"
                    + formatMoney(event.returnAmount()) + ", 当前粮草：" + currentBalance;
            case DRAW -> prefix + "第" + event.issueNumber() + "期和局，返还粮草"
                    + formatMoney(event.returnAmount()) + ", 当前粮草：" + currentBalance;
            case LOSE -> prefix + "第" + event.issueNumber() + "期未中奖，使用粮草"
                    + formatMoney(event.stake()) + ", 当前粮草：" + currentBalance;
            case PENDING -> prefix + "第" + event.issueNumber() + "期结算处理中!";
            case CANCELED -> prefix + "第" + event.issueNumber() + "期注单已撤销，返还粮草"
                    + formatMoney(event.returnAmount()) + ", 当前粮草：" + currentBalance;
        };
    }

    private static String feedbackIdempotencyKey(String roomCode, long sourceMessageId,
                                                 String clientMessageId) {
        String source = roomCode + "\u0000" + sourceMessageId + "\u0000" + clientMessageId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("CHAT-FEEDBACK-");
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("反馈幂等键算法不可用", exception);
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> callback) {
        if (transactionTemplate == null) {
            return callback.get();
        }
        T result = transactionTemplate.execute(status -> callback.get());
        if (result == null) {
            throw new IllegalStateException("聊天消息事务未返回结果");
        }
        return result;
    }

    private String betPayload(DemoGameService.BetView bet) {
        try {
            return objectMapper.writeValueAsString(betMessagePayload(bet));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成下注消息追踪数据失败", exception);
        }
    }

    private String betPayload(List<DemoGameService.BetView> bets) {
        if (bets.size() == 1) {
            return betPayload(bets.get(0));
        }
        try {
            return objectMapper.writeValueAsString(new BatchBetMessagePayload(
                    bets.stream().map(this::betMessagePayload).toList()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成组合下注消息追踪数据失败", exception);
        }
    }

    private BetMessagePayload betMessagePayload(DemoGameService.BetView bet) {
        return new BetMessagePayload(bet.id(), bet.issueNumber(), bet.ballNumber(),
                bet.playType().name(), bet.parameters(), bet.stake(), bet.odds(),
                bet.settlementStatus().name());
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

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void publishSettlementFeedback(BetSettlementCompletedEvent event) {
        if (event == null || walletService == null || robotRepository == null) {
            throw new IllegalArgumentException("结算反馈参数或服务未配置");
        }
        var wallet = walletService.getByAccountId(event.accountId());
        ChatRobot robot = robotRepository.findByCode(FEEDBACK_ROBOT_CODE)
                .orElseThrow(() -> new IllegalStateException("反馈机器人未配置"));
        String content = settlementFeedback(wallet.displayName(), wallet.balance(), event);
        String key = "CHAT-SETTLEMENT-" + event.betId() + "-" + event.status().name();
        publishRobotMessage(robot.id(), robot.displayName(), ROOM_CODE, event.issueNumber(), key,
                content, settlementFeedbackPayload(event), Instant.now());
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
                || ChatProtocol.utf8Bytes(payloadJson) > messageProperties.getMaxRobotPayloadBytes()) {
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

    private record BatchBetMessagePayload(List<BetMessagePayload> bets) {
    }

    private record FeedbackPayload(String eventType, String feedbackType, long sourceMessageId) {
    }

    private record SettlementFeedbackPayload(String eventType, long betId, String status,
                                             String issueNumber, java.math.BigDecimal returnAmount,
                                             java.math.BigDecimal netProfit) {
    }

    private static final class BetRejectedException extends RuntimeException {
        private final BusinessException cause;

        private BetRejectedException(BusinessException cause) {
            super(cause);
            this.cause = cause;
        }

        private BusinessException cause() {
            return cause;
        }
    }
}
