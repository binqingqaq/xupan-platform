package com.xupan.server.chat.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessageVisibility;
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
import com.xupan.server.game.repository.PlayerBetSummaryRepository;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.service.BetTextParser;
import com.xupan.server.game.service.BetSettlementCompletedEvent;
import com.xupan.server.game.service.BetLimitExceededException;
import com.xupan.server.game.service.DemoGameService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotDrawComponent;
import com.xupan.server.robot.repository.RobotRepository;
import com.xupan.server.robot.service.RobotNoticeIntentService;
import com.xupan.server.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatMessageService {

    private static final Pattern SELECTED_BALL_PREFIX = Pattern.compile("^第\\s*([1-8])\\s*球\\s*/\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern POINT_REQUEST_SHAPE = Pattern.compile("^[上下][0-9]+(?:\\.[0-9]+)?$");

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
    private PlayerBetSummaryRepository playerBetSummaryRepository;
    private RobotNoticeIntentService noticeIntentService;

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
        if (transactionTemplate == null) {
            this.transactionTemplate = null;
        } else {
            this.transactionTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
            this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
    }

    @Autowired(required = false)
    public void setPointRequestRepository(PlayerPointRequestRepository pointRequestRepository) {
        this.pointRequestRepository = pointRequestRepository;
    }

    @Autowired(required = false)
    public void setPlayerBetSummaryRepository(PlayerBetSummaryRepository repository) {
        this.playerBetSummaryRepository = repository;
    }

    @Autowired(required = false)
    public void setRobotNoticeIntentService(RobotNoticeIntentService service) {
        this.noticeIntentService = service;
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
        Long agentId = userRepository.findPlayerAgentId(userId).orElse(null);
        if (beforeSequence != null && beforeSequence > room.nextSequenceNo() + 1
                || afterSequence != null && afterSequence > room.nextSequenceNo()) {
            throw BusinessException.badRequest("CHAT_CURSOR_INVALID", "消息游标超过房间当前序号");
        }

        List<ChatMessage> queried;
        boolean before = beforeSequence != null || afterSequence == null;
        if (afterSequence != null) {
            queried = messageRepository.findAfterSequence(room.id(), afterSequence, pageSize, agentId);
        } else {
            long cursor = beforeSequence == null ? room.nextSequenceNo() + 1 : beforeSequence;
            queried = messageRepository.findBeforeSequence(room.id(), cursor, pageSize, agentId);
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
        return sendUserMessage(userId, roomCode, clientMessageId, content, "AU8", now);
    }

    public ChatMessage sendUserMessage(long userId, String roomCode, String clientMessageId,
                                       String content, String gameCode, Instant now) {
        return sendUserMessageWithOutcome(userId, roomCode, clientMessageId, content, gameCode, now).message();
    }

    public ChatMessageSendOutcome sendUserMessageWithOutcome(long userId, String roomCode,
                                                              String clientMessageId, String content,
                                                              Instant now) {
        return sendUserMessageWithOutcome(userId, roomCode, clientMessageId, content, "AU8", now);
    }

    public ChatMessageSendOutcome sendUserMessageWithOutcome(long userId, String roomCode,
                                                              String clientMessageId, String content,
                                                              String gameCode, Instant now) {
        try {
            return inTransaction(() -> processNewUserMessage(userId, roomCode, clientMessageId, content, gameCode, now));
        } catch (BetRejectedException rejected) {
            // DemoGameService deliberately rolls back its bet/wallet transaction on a business rejection.
            // Persist the original input and its public feedback in a fresh transaction afterwards.
            return inTransaction(() -> persistRejectedBet(userId, roomCode, clientMessageId, content, gameCode, now,
                    rejected.cause()));
        } catch (PointRequestRejectedException rejected) {
            inTransaction(() -> persistRejectedPointRequest(userId, roomCode, clientMessageId,
                    content, now, rejected));
            String action = rejected.commandType() == ChatCommandParser.Type.DOWN ? "下分" : "上分";
            throw BusinessException.badRequest("WALLET_INSUFFICIENT_BALANCE", "您当前积分不足"
                    + formatMoney(rejected.amount()));
        }
    }

    private ChatMessageSendOutcome processNewUserMessage(long userId, String roomCode,
                                                         String clientMessageId, String content, String gameCode,
                                                         Instant now) {
        requirePositiveUser(userId);
        UserAccount user = requireActiveUser(userId);
        long agentId = requireMachineAgentId(userId);
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
        Matcher selectedBallMatcher = SELECTED_BALL_PREFIX.matcher(normalizedContent);
        int selectedBallNumber = selectedBallMatcher.matches()
                ? Integer.parseInt(selectedBallMatcher.group(1)) : 1;
        String commandContent = selectedBallMatcher.matches()
                ? selectedBallMatcher.group(2).trim() : normalizedContent;
        Optional<ChatCommandParser.Command> command = ChatCommandParser.parse(commandContent);
        if (command.isPresent()) {
            ChatMessage message = messageRepository.insertUserMessage(room.id(),
                    roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo()), userId,
                    user.displayName(), clientId, agentId, normalizedContent, now);
            publishUserMessage(message, now);
            return processCommand(userId, roomCode, clientId, user, message, command.get(), now);
        }

        if (looksLikeInvalidPointRequest(commandContent)) {
            long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
            ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                    user.displayName(), clientId, agentId, normalizedContent, now);
            publishUserMessage(message, now);
            String action = commandContent.startsWith("上") ? "上分" : "下分";
            publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(),
                    "POINT_REQUEST_INVALID", "@" + user.displayName() + "，" + action
                            + "金额格式无效，请输入大于零且最多两位小数的积分。", now);
            return new ChatMessageSendOutcome(message, false);
        }

        BetTextParser.ParseResult parsedBet = BetTextParser.parse(commandContent, selectedBallNumber);
        List<BetTextParser.ParsedBet> bets = parsedBet.bets();
        if (!bets.isEmpty()) {
            if (gameService == null) {
                throw new IllegalStateException("下注服务未配置");
            }
            List<DemoGameService.BetInstruction> requests = bets.stream().map(bet -> new DemoGameService.BetInstruction(
                    new PlaceBetRequest(bet.ballNumber(), bet.playType(), bet.parameters(), bet.stake(),
                            "CHAT-" + clientId, gameCode),
                    bet.quotaSelectionKey() == null
                            ? com.xupan.server.game.domain.BetQuotaBucket.ruleKey(bet.playType(), bet.parameters())
                            : bet.quotaSelectionKey())).toList();
            List<DemoGameService.BetView> placedBets;
            List<BetLimitExceededException> rejectedBets = List.of();
            try {
                placedBets = gameService.placeBetBatchInstructionsForUser(userId, requests, "CHAT-" + clientId);
            } catch (BetLimitExceededException rejection) {
                placedBets = List.of();
                rejectedBets = List.of(rejection);
            } catch (BusinessException exception) {
                throw new BetRejectedException(exception);
            }
            long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
            if (placedBets.isEmpty()) {
                ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                        user.displayName(), clientId, agentId, normalizedContent, now);
                publishUserMessage(message, now);
                publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(),
                        "BET_LIMIT_REJECTED", "@" + user.displayName() + "  下注未成功"
                                + limitRejectionLines(user.displayName(), rejectedBets), now);
                return new ChatMessageSendOutcome(message, false);
            }
            DemoGameService.BetView firstBet = placedBets.get(0);
            String betPayload = betPayload(placedBets);
            ChatMessage message = messageRepository.insertUserBetMessage(room.id(), sequence, userId,
                    user.displayName(), clientId, firstBet.issueNumber(), normalizedContent, betPayload,
                    agentId, now);
            publishUserMessage(message, now);
            if (walletService == null || robotRepository == null) {
                throw new IllegalStateException("下注反馈服务未配置");
            }
            java.math.BigDecimal totalStake = placedBets.stream()
                    .map(DemoGameService.BetView::stake)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            String remainingBalance = formatMoney(walletService.getForCurrentUser(userId).balance());
            publishFeedback(roomCode, firstBet.issueNumber(), clientId, message.id(),
                    "BET_ACCEPTED", "@" + user.displayName() + "，第" + firstBet.issueNumber()
                            + "期攻击成功，使用积分" + formatMoney(totalStake) + "，剩余积分：" + remainingBalance
                            + limitRejectionLines(user.displayName(), rejectedBets), now);
            return new ChatMessageSendOutcome(message, false);
        }
        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                user.displayName(), clientId, agentId, normalizedContent, now);
        publishUserMessage(message, now);
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(),
                "INVALID_COMMAND", "@" + user.displayName() + ", 指令格式不正确!", now);
        return new ChatMessageSendOutcome(message, false);
    }

    private static boolean looksLikeInvalidPointRequest(String content) {
        return content != null && POINT_REQUEST_SHAPE.matcher(content.trim()).matches()
                && ChatCommandParser.parse(content.trim()).isEmpty();
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
                        "@" + user.displayName() + "，剩余积分：" + formatMoney(balance.balance()), now);
            }
            case DAILY_SUMMARY -> {
                VirtualWallet balance = currentBalance(userId);
                DayRange range = currentBusinessDay(now);
                if (playerBetSummaryRepository == null) {
                    throw new IllegalStateException("玩家流水汇总查询未配置");
                }
                GameDataRepository.BetDayStatistics statistics = playerBetSummaryRepository.findBetDayStatistics(
                        balance.accountId(), range.fromInclusive(), range.toExclusive());
                long issues = gameDataRepository.countBetIssues(balance.accountId(),
                        range.fromInclusive(), range.toExclusive());
                publishFeedback(roomCode, issueNumber, clientId, source.id(), "DAILY_SUMMARY",
                        "@" + user.displayName() + "，今日累计下注积分：" + formatMoney(statistics.turnover())
                                + "\n今日已结盈亏：" + formatMoney(statistics.netProfit())
                                + "\n下注期数：" + issues, now);
            }
            case HISTORY -> {
                publishDrawHistoryFeedback(roomCode, issueNumber, clientId, source.id(),
                        user.displayName(), false, now);
            }
            case TREND -> {
                publishDrawHistoryFeedback(roomCode, issueNumber, clientId, source.id(),
                        user.displayName(), true, now);
            }
            case TOP_UP, DOWN -> processPointRequest(userId, roomCode, clientId, user, source, command, now);
            case CANCEL -> processCancellation(userId, roomCode, clientId, user, source, now);
        }
        return new ChatMessageSendOutcome(source, false);
    }

    /**
     * 聊天室上下分申请（BY220 玩家发「上100 / 下100」→ {@code x_folder} 建单待审批）。
     *
     * <p><b>下分创建即扣</b>（BY220 {@code application/common.php:1874-1876}）：
     * BY220 在 {@code addJifen()} 里对 {@code $type == 1}（下分）先
     * {@code db('rbuser')->...->setDec('score', $last)} 再写申请单，
     * 即「钱在申请创建时就离开玩家余额」；同意后归机器池，拒绝则原路退回
     * （{@code application/rbt/controller/Index.php:280-286} 同意、{@code :307-315} 拒绝）。</p>
     *
     * <p><b>余额不足拒绝创建</b>：BY220 不校验余额（可扣成负数）。我方刻意偏离——余额不足直接 400
     * （WS 通道为 error 帧），复用既有「您当前积分不足X」文案，且余额一个字节都不动。
     * 允许负余额会破坏钱包口径与报表，该偏离已在计划 §3.2 登记。</p>
     *
     * <p><b>竞态（已评估、刻意不消除）</b>：这里的余额预检与下面
     * {@code holdForPointRequest} 的写入之间存在竞态窗口（例如同一瞬间的下注把余额花掉）。
     * 竞态下的行为是确定的：钱包层抛**同一个 code** {@code WALLET_INSUFFICIENT_BALANCE}
     * （HTTP 409，因为写入阶段只认钱包层错误），**事务整体回滚**——申请行、预扣流水、
     * 余额变动一个都不留，不变量（不允许负余额）仍然成立。刻意不把校验并进写入路径：
     * 那会把一次数据库写变成两次，收益不抵成本。</p>
     */
    private void processPointRequest(long userId, String roomCode, String clientId, UserAccount user,
                                     ChatMessage source, ChatCommandParser.Command command, Instant now) {
        if (pointRequestRepository == null) {
            throw new IllegalStateException("上下分申请服务未配置");
        }
        boolean downRequest = command.type() == ChatCommandParser.Type.DOWN;
        try {
            java.math.BigDecimal balanceBefore = currentBalance(userId).balance();
            if (downRequest && balanceBefore.compareTo(command.amount()) < 0) {
                throw BusinessException.badRequest("WALLET_INSUFFICIENT_BALANCE", "下分申请积分不足");
            }
            PlayerPointRequestRepository.Request request = pointRequestRepository.insertPending(userId,
                    command.type().name(), command.amount(), clientId, source.id(), now);
            java.math.BigDecimal balanceAfter = balanceBefore;
            if (downRequest) {
                balanceAfter = walletService.holdForPointRequest(userId, command.amount(),
                        "下分申请预扣（聊天指令）：" + user.displayName(),
                        PlayerPointRequestRepository.holdIdempotencyKey(request.id())).wallet().balance();
            }
            String action = downRequest ? "下分" : "上分";
            String content = "@" + user.displayName() + "，" + action + "申请"
                    + formatMoney(command.amount()) + "积分已提交，待审批。";
            if (downRequest) content += "本次已扣除申请积分，剩余" + formatMoney(balanceAfter) + "积分。";
            publishFeedback(roomCode, feedbackIssueNumber(), clientId, source.id(), "POINT_REQUEST_CREATED",
                    content, now);
        } catch (BusinessException exception) {
            if (!"WALLET_INSUFFICIENT_BALANCE".equals(exception.code())) throw exception;
            throw new PointRequestRejectedException(command.type(), command.amount());
        }
    }

    private void processCancellation(long userId, String roomCode, String clientId, UserAccount user,
                                     ChatMessage source, Instant now) {
        if (gameService == null) {
            throw new IllegalStateException("撤单服务未配置");
        }
        DemoGameService.CancellationResult result = gameService.cancelCurrentBets(userId, now);
        String content = switch (result.status()) {
            case STOPPED -> "@" + user.displayName() + " 本期已停止，禁止取消！";
            case CANCELLATION_DISABLED -> "@" + user.displayName() + " 禁止取消！";
            case NO_ELIGIBLE_BETS -> "@" + user.displayName() + " " + result.windowSeconds() + "秒内无有效指令！";
            case CANCELED -> cancellationContent(user.displayName(), result);
        };
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, source.id(), "CANCEL_RESULT", content, now);
    }

    private static String cancellationContent(String displayName, DemoGameService.CancellationResult result) {
        StringBuilder content = new StringBuilder("@").append(displayName).append(' ')
                .append(result.windowSeconds()).append("秒内有效指令已全部取消");
        for (DemoGameService.CancellationItem item : result.items()) {
            GameDataRepository.BetRecord bet = item.bet();
            content.append('\n').append('@').append(displayName).append(" 取消 ")
                    .append(formatBetText(bet.playType(), bet.parameters(), bet.stake()))
                    .append("，返还积分").append(formatMoney(bet.stake()))
                    .append("，剩余积分").append(formatMoney(item.balanceAfter()));
        }
        return content.toString();
    }

    private ChatMessageSendOutcome persistRejectedBet(long userId, String roomCode,
                                                         String clientMessageId, String content,
                                                         String gameCode, Instant now,
                                                         BusinessException rejection) {
        requirePositiveUser(userId);
        UserAccount user = requireActiveUser(userId);
        long agentId = requireMachineAgentId(userId);
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
                user.displayName(), clientId, agentId, normalizedContent, now);
        publishUserMessage(message, now);
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(),
                "BET_REJECTED", rejectedFeedback(user.displayName(), rejection), now);
        return new ChatMessageSendOutcome(message, false);
    }

    private ChatMessageSendOutcome persistRejectedPointRequest(long userId, String roomCode,
                                                               String clientMessageId, String content,
                                                               Instant now, PointRequestRejectedException rejected) {
        requirePositiveUser(userId);
        UserAccount user = requireActiveUser(userId);
        long agentId = requireMachineAgentId(userId);
        String clientId = contentPolicy.requireClientMessageId(clientMessageId);
        String normalizedContent = contentPolicy.normalize(content);
        ChatRoom room = requireRoomForUpdate(roomCode);
        Optional<ChatMessage> existing = messageRepository.findByClientMessageId(room.id(), userId, clientId);
        if (existing.isPresent()) return new ChatMessageSendOutcome(existing.get(), true);
        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertUserMessage(room.id(), sequence, userId,
                user.displayName(), clientId, agentId, normalizedContent, now);
        publishUserMessage(message, now);
        String action = rejected.commandType() == ChatCommandParser.Type.DOWN ? "下分" : "上分";
        publishFeedback(roomCode, feedbackIssueNumber(), clientId, message.id(), "POINT_REQUEST_REJECTED",
                "@" + user.displayName() + "，" + action + "申请未提交：积分不足，本次需要"
                        + formatMoney(rejected.amount()) + "积分。", now);
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
        long agentId = messageRepository.findMachineAgentId(sourceMessageId)
                .orElseThrow(() -> new IllegalStateException("聊天反馈来源消息缺少机器归属"));
        publishRobotMessage(robot.id(), robot.displayName(), roomCode, issueNumber,
                feedbackIdempotencyKey(roomCode, sourceMessageId, clientMessageId), content,
                feedbackPayload(sourceMessageId, feedbackType), ChatMessageVisibility.MACHINE,
                agentId, createdAt);
    }

    private void publishDrawHistoryFeedback(String roomCode, String issueNumber, String clientMessageId,
                                            long sourceMessageId, String displayName, boolean trend,
                                            Instant now) {
        int activeBallCount = gameDataRepository.findCurrentIssue()
                .or(() -> gameDataRepository.findLatestIssue())
                .map(GameDataRepository.IssueRecord::ballCount)
                .orElse(8);
        List<GameDataRepository.IssueRecord> issues = gameDataRepository.findSettledIssues("AU8", 15).stream()
                .filter(issue -> issue.ballCount() == activeBallCount
                        && issue.numbers().size() == activeBallCount)
                .toList();
        List<GameDataRepository.IssueRecord> routes = gameDataRepository.findLatestSettledBlock("AU8", 60).stream()
                .filter(issue -> issue.ballCount() == activeBallCount
                        && issue.numbers().size() == activeBallCount)
                .toList();
        String component = RobotDrawComponent.DRAW_HISTORY.name();
        String feedbackType = trend ? "TREND" : "HISTORY";
        String queryIssue = issueNumber == null || issueNumber.isBlank() ? "UNKNOWN" : issueNumber;
        try {
            String payload = objectMapper.writeValueAsString(new StructuredRobotDrawPayload(
                    "xupan.chat-payload.v1", component, queryIssue,
                    new RobotDrawHistoryData(issues.stream().map(issue -> new RobotDrawHistoryItem(
                            issue.issueNumber(), issue.numbers(), issue.settledAt().toString())).toList(),
                            routes.stream().map(issue -> new RobotDrawHistoryItem(
                                    issue.issueNumber(), issue.numbers(), issue.settledAt().toString())).toList())));
            ChatRobot robot = robotRepository.findByCode(FEEDBACK_ROBOT_CODE)
                    .orElseThrow(() -> new IllegalStateException("反馈机器人未配置"));
            long agentId = messageRepository.findMachineAgentId(sourceMessageId)
                    .orElseThrow(() -> new IllegalStateException("查询反馈来源消息缺少机器归属"));
            String content = issues.isEmpty() ? "@" + displayName + "，暂无已开奖历史。"
                    : "@" + displayName + "，" + (trend ? "近期开奖走势" : "最近开奖历史") + "。";
            String key = feedbackIdempotencyKey(roomCode, sourceMessageId,
                    clientMessageId + "-" + feedbackType);
            publishRobotMessage(robot.id(), robot.displayName(), roomCode, queryIssue, key,
                    content, payload, ChatMessageVisibility.MACHINE, agentId, now);
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成机器人查询卡片失败", exception);
        }
    }

    /** Adds the approval message intent to the same transaction as the request and wallet changes. */
    @Transactional
    public void enqueuePointRequestApproved(long requestId, long sourceMessageId,
                                            String displayName, String requestType,
                                            java.math.BigDecimal amount,
                                            java.math.BigDecimal balanceAfter, Instant createdAt) {
        if (requestId <= 0 || sourceMessageId <= 0 || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("积分申请反馈参数无效");
        }
        String action = switch (requestType) {
            case "TOP_UP" -> "上分";
            case "DOWN" -> "下分";
            default -> throw new IllegalArgumentException("积分申请类型无效");
        };
        long agentId = messageRepository.findMachineAgentId(sourceMessageId)
                .orElseThrow(() -> new IllegalStateException("积分申请来源消息缺少机器归属"));
        String content = "@" + requireRobotName(displayName) + "，成功" + action
                + formatMoney(amount) + "积分";
        if ("TOP_UP".equals(requestType) && balanceAfter != null) {
            content += "，剩余积分" + formatMoney(balanceAfter);
        }
        enqueueBusinessNotice("POINT_REQUEST_APPROVED", "POINT_REQUEST", Long.toString(requestId),
                feedbackIssueNumber(), agentId, "POINT_REQUEST_APPROVED", sourceMessageId, content,
                "POINT-REQUEST-APPROVED:" + requestId, createdAt);
    }

    @Transactional
    public void enqueuePointRequestRejected(long requestId, long sourceMessageId, String displayName,
                                            String requestType, java.math.BigDecimal amount,
                                            boolean downAmountRefunded,
                                            java.math.BigDecimal balanceAfter, Instant createdAt) {
        if (requestId <= 0 || sourceMessageId <= 0 || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("积分申请反馈参数无效");
        }
        String action = switch (requestType) {
            case "TOP_UP" -> "上分";
            case "DOWN" -> "下分";
            default -> throw new IllegalArgumentException("积分申请类型无效");
        };
        long agentId = messageRepository.findMachineAgentId(sourceMessageId)
                .orElseThrow(() -> new IllegalStateException("积分申请来源消息缺少机器归属"));
        String content = "@" + requireRobotName(displayName) + "，" + action + "申请"
                + formatMoney(amount) + "积分未通过";
        String feedbackType = "POINT_REQUEST_REJECTED";
        if ("DOWN".equals(requestType) && downAmountRefunded) {
            content += "，已退回预扣积分" + formatMoney(amount);
            if (balanceAfter != null) content += "，当前剩余积分" + formatMoney(balanceAfter);
            feedbackType = "POINT_REQUEST_REJECTED_REFUNDED";
        }
        enqueueBusinessNotice(feedbackType, "POINT_REQUEST", Long.toString(requestId),
                feedbackIssueNumber(), agentId, feedbackType, sourceMessageId,
                content + "。", "POINT-REQUEST-REJECTED:" + requestId, createdAt);
    }

    public void enqueueMachineBusinessNotice(String eventCode, String sourceType, String sourceId,
                                             String issueNumber, long agentId, String feedbackType,
                                             String content, String idempotencyKey, Instant createdAt) {
        enqueueBusinessNotice(eventCode, sourceType, sourceId, issueNumber, agentId, feedbackType,
                null, content, idempotencyKey, createdAt);
    }

    private void enqueueBusinessNotice(String eventCode, String sourceType, String sourceId,
                                       String issueNumber, long agentId, String feedbackType,
                                       Long sourceMessageId, String content, String idempotencyKey,
                                       Instant createdAt) {
        if (noticeIntentService == null) {
            throw new IllegalStateException("机器人通知意图服务未配置");
        }
        noticeIntentService.enqueue(eventCode, sourceType, sourceId, "AU8", issueNumber,
                ChatMessageVisibility.MACHINE, agentId, feedbackType, sourceMessageId,
                content, idempotencyKey, createdAt == null ? Instant.now() : createdAt);
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
            case "WALLET_INSUFFICIENT_BALANCE" -> prefix + "积分不足，本次下注未提交!";
            case "GAME_MACHINE_MANUALLY_CLOSED" -> prefix + "本机已停止下注!";
            case "GAME_MACHINE_CLOSED" -> prefix + "本机已到封盘时间，下注无效!";
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
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private record DayRange(Instant fromInclusive, Instant toExclusive) {
    }

    private static String settlementFeedback(String displayName, BetSettlementCompletedEvent event) {
        String prefix = "@" + displayName + "，";
        String currentBalance = formatMoney(event.balanceAfter());
        return switch (event.status()) {
            case WIN -> prefix + "第" + event.issueNumber() + "期中奖，返还积分"
                    + formatMoney(event.returnAmount()) + "，结算后余额" + currentBalance + "积分。";
            case DRAW -> prefix + "第" + event.issueNumber() + "期和局，返还积分"
                    + formatMoney(event.returnAmount()) + "，结算后余额" + currentBalance + "积分。";
            case LOSE -> prefix + "第" + event.issueNumber() + "期未中奖，本注已扣积分"
                    + formatMoney(event.stake()) + "，结算后余额" + currentBalance + "积分。";
            case PENDING -> prefix + "第" + event.issueNumber() + "期结算处理中。";
            case CANCELED -> prefix + "第" + event.issueNumber() + "期注单已取消，返还积分"
                    + formatMoney(event.returnAmount()) + "，结算后余额" + currentBalance + "积分。";
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
                                           String payloadJson, ChatMessageVisibility visibility,
                                           Long agentId, Instant createdAt) {
        requirePositiveRobot(robotId);
        String normalizedRobotName = "机器人";
        String normalizedRoomCode = requireRoomCode(roomCode);
        String normalizedIssueNumber = requireRobotText(issueNumber, "CHAT_ROBOT_ISSUE_INVALID",
                "机器人期号无效", 64);
        String normalizedIdempotencyKey = requireRobotText(idempotencyKey,
                "CHAT_ROBOT_IDEMPOTENCY_KEY_INVALID", "机器人幂等键无效", 128);
        String normalizedContent = contentPolicy.normalize(content);
        String normalizedPayloadJson = requirePayloadJson(payloadJson);
        requireVisibility(visibility, agentId);
        if (createdAt == null) {
            throw BusinessException.badRequest("CHAT_ROBOT_TIME_INVALID", "机器人消息时间不能为空");
        }

        Optional<ChatMessage> existing = messageRepository.findByIdempotencyKey(normalizedIdempotencyKey);
        if (existing.isPresent()) {
            return requireSameRobotMessage(existing.get(), robotId, normalizedRobotName,
                    normalizedRoomCode, normalizedIssueNumber, normalizedContent, normalizedPayloadJson,
                    visibility, agentId);
        }

        ChatRoom room = requireRoomForUpdate(normalizedRoomCode);
        existing = messageRepository.findByIdempotencyKey(normalizedIdempotencyKey);
        if (existing.isPresent()) {
            return requireSameRobotMessage(existing.get(), robotId, normalizedRobotName,
                    normalizedRoomCode, normalizedIssueNumber, normalizedContent, normalizedPayloadJson,
                    visibility, agentId);
        }

        long sequence = roomRepository.allocateNextSequence(room.id(), room.nextSequenceNo());
        ChatMessage message = messageRepository.insertRobotMessage(room.id(), sequence, robotId,
                normalizedRobotName, normalizedIssueNumber, normalizedIdempotencyKey,
                normalizedContent, normalizedPayloadJson, visibility, agentId, createdAt);
        outboxRepository.insertMessageCreatedOutbox(message.id(), outboxPayload(message), createdAt);
        eventPublisher.publishEvent(new ChatMessageCreatedEvent(message));
        return message;
    }

    /** Publishes a machine-scoped operational notice under the sole chat robot identity. */
    @Transactional
    public ChatMessage publishRobotNotice(String roomCode, String issueNumber, String idempotencyKey,
                                          String content, long agentId, Instant createdAt) {
        ChatRobot robot = robotRepository.findByCode(FEEDBACK_ROBOT_CODE)
                .orElseThrow(() -> new IllegalStateException("唯一聊天室机器人未配置"));
        return publishRobotMessage(robot.id(), "机器人", roomCode, issueNumber, idempotencyKey,
                content, "{}", ChatMessageVisibility.MACHINE, agentId,
                createdAt == null ? Instant.now() : createdAt);
    }

    @Transactional
    public void enqueueSettlementFeedback(BetSettlementCompletedEvent event) {
        if (event == null || walletService == null || noticeIntentService == null) {
            throw new IllegalArgumentException("结算反馈参数或服务未配置");
        }
        var wallet = walletService.getByAccountId(event.accountId());
        long agentId = userRepository.findPlayerAgentIdByAccountId(event.accountId())
                .orElseThrow(() -> new IllegalStateException("结算反馈玩家缺少机器归属"));
        String content = settlementFeedback(wallet.displayName(), event);
        String key = "CHAT-SETTLEMENT-" + event.betId() + "-" + event.status().name();
        noticeIntentService.enqueue("BET_SETTLEMENT_" + event.status().name(), "GAME_BET",
                Long.toString(event.betId()), "AU8", event.issueNumber(), ChatMessageVisibility.MACHINE,
                agentId, "BET_SETTLEMENT_" + event.status().name(), null, content, key, Instant.now());
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

    public Long machineAgentId(long userId) {
        requirePositiveUser(userId);
        requireActiveUser(userId);
        return userRepository.findPlayerAgentId(userId).orElse(null);
    }

    public List<Long> activeMachineIds() {
        return messageRepository.findActiveMachineIds();
    }

    private long requireMachineAgentId(long userId) {
        return userRepository.findPlayerAgentId(userId).orElseThrow(() ->
                BusinessException.forbidden("CHAT_MACHINE_SCOPE_REQUIRED", "当前账号没有有效机器归属"));
    }

    private static void requireVisibility(ChatMessageVisibility visibility, Long agentId) {
        if (visibility == null
                || (visibility == ChatMessageVisibility.PUBLIC && agentId != null)
                || (visibility == ChatMessageVisibility.MACHINE && (agentId == null || agentId <= 0))) {
            throw BusinessException.badRequest("CHAT_VISIBILITY_INVALID", "聊天消息可见范围无效");
        }
    }

    private String outboxPayload(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(new OutboxMessage(message.id(), message.roomCode(),
                    message.sequenceNo(), message.messageType().name(), message.senderType().name(),
                    message.senderId(), message.senderName(), message.content(), message.createdAt().toString(),
                    message.visibility().name(), message.agentId()));
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
                                                String payloadJson, ChatMessageVisibility visibility,
                                                Long agentId) {
        if (existing.messageType() != com.xupan.server.chat.domain.ChatMessageType.ROBOT
                || existing.senderType() != com.xupan.server.chat.domain.ChatSenderType.ROBOT
                || !Objects.equals(existing.senderId(), robotId)
                || !Objects.equals(existing.senderName(), robotName)
                || !Objects.equals(existing.roomCode(), roomCode)
                || !Objects.equals(existing.issueNumber(), issueNumber)
                || !Objects.equals(existing.content(), content)
                || existing.visibility() != visibility || !Objects.equals(existing.agentId(), agentId)
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
                                 String senderName, String content, String createdAt,
                                 String visibility, Long agentId) {
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

    private record StructuredRobotDrawPayload(String schema, String component, String issueNumber,
                                              Object data) {
    }

    private record RobotDrawHistoryData(List<RobotDrawHistoryItem> items,
                                        List<RobotDrawHistoryItem> routeItems) {
    }

    private record RobotDrawHistoryItem(String issueNumber, List<Integer> numbers, String settledAt) {
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

    private static final class PointRequestRejectedException extends RuntimeException {
        private final ChatCommandParser.Type commandType;
        private final java.math.BigDecimal amount;

        private PointRequestRejectedException(ChatCommandParser.Type commandType,
                                              java.math.BigDecimal amount) {
            super("WALLET_INSUFFICIENT_BALANCE");
            this.commandType = commandType;
            this.amount = amount;
        }

        private ChatCommandParser.Type commandType() {
            return commandType;
        }

        private java.math.BigDecimal amount() {
            return amount;
        }
    }
}
