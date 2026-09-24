package com.xupan.server.robot.service;

import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.robot.domain.RobotDrawComponent;
import com.xupan.server.robot.domain.RobotDrawComponentConfig;
import com.xupan.server.robot.repository.RobotDrawComponentRepository;
import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.ChatRobotDispatch;
import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.domain.RobotEventType;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import com.xupan.server.robot.repository.RobotRepository;
import com.xupan.server.robot.repository.RobotTemplateRepository;
import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RobotDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RobotDispatchService.class);
    private static final String ROOM_CODE = "main";

    private final RobotDispatchRepository dispatchRepository;
    private final RobotRepository robotRepository;
    private final RobotTemplateRepository templateRepository;
    private final RobotSelectionService selectionService;
    private final RobotTemplateRenderer templateRenderer;
    private final RobotMessageIdempotency idempotency;
    private final RobotGameEventAdapter eventAdapter;
    private final RobotRetryPolicy retryPolicy;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final RobotDrawComponentRepository drawComponentRepository;
    private final GameDataRepository gameDataRepository;

    public RobotDispatchService(RobotDispatchRepository dispatchRepository,
                                RobotRepository robotRepository,
                                RobotTemplateRepository templateRepository,
                                RobotSelectionService selectionService,
                                RobotTemplateRenderer templateRenderer,
                                RobotMessageIdempotency idempotency,
                                RobotGameEventAdapter eventAdapter,
                                RobotRetryPolicy retryPolicy,
                                ChatMessageService chatMessageService,
                                ObjectMapper objectMapper,
                                TransactionTemplate transactionTemplate,
                                RobotDrawComponentRepository drawComponentRepository,
                                GameDataRepository gameDataRepository) {
        this.dispatchRepository = dispatchRepository;
        this.robotRepository = robotRepository;
        this.templateRepository = templateRepository;
        this.selectionService = selectionService;
        this.templateRenderer = templateRenderer;
        this.idempotency = idempotency;
        this.eventAdapter = eventAdapter;
        this.retryPolicy = retryPolicy;
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.drawComponentRepository = drawComponentRepository;
        this.gameDataRepository = gameDataRepository;
    }

    public int scanPendingEvents(Instant now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("机器人扫描参数无效");
        }
        List<RobotDispatchRepository.UnscheduledGameEvent> events =
                dispatchRepository.findUnscheduledEvents(batchSize);
        int discovered = 0;
        for (RobotDispatchRepository.UnscheduledGameEvent event : events) {
            Boolean created = transactionTemplate.execute(status -> discover(event, now));
            if (Boolean.TRUE.equals(created)) {
                discovered++;
            }
        }

        List<ChatRobotDispatch> due = dispatchRepository.findDue(batchSize, now);
        for (ChatRobotDispatch dispatch : due) {
            dispatchGameEvent(dispatch.id(), now);
        }
        return discovered;
    }

    public DispatchResult dispatchGameEvent(long dispatchId, Instant now) {
        if (dispatchId <= 0 || now == null) {
            throw new IllegalArgumentException("机器人投递参数无效");
        }
        DispatchResult result = transactionTemplate.execute(status -> processOne(dispatchId, now));
        if (result == null) {
            throw new IllegalStateException("机器人投递事务未返回结果");
        }
        return result;
    }

    private Boolean discover(RobotDispatchRepository.UnscheduledGameEvent event, Instant now) {
        List<ChatRobot> allRobots = robotRepository.findAll();
        List<ChatRobot> enabledRobots = allRobots.stream().filter(ChatRobot::isEnabled).toList();
        Optional<ChatRobot> selected = selectionService.select(enabledRobots,
                event.issueNumber(), event.eventType());
        if (selected.isEmpty()) {
            ChatRobot fallback = allRobots.stream().findFirst().orElse(null);
            if (fallback == null) {
                log.warn("机器人事件没有可记录的机器人，eventId={}", event.id());
                return false;
            }
            ChatRobotDispatch dispatch = dispatchRepository.insertPendingIfAbsent(event.id(),
                    fallback.id(), event.issueNumber(), eventAdapter.eventType(event.eventType()),
                    event.createdAt());
            dispatchRepository.markSkipped(dispatch.id(), "NO_ENABLED_ROBOT", now);
            return true;
        }

        ChatRobot robot = selected.get();
        if (now.isBefore(event.createdAt().plusSeconds(robot.delaySeconds()))) {
            return false;
        }
        dispatchRepository.insertPendingIfAbsent(event.id(), robot.id(), event.issueNumber(),
                eventAdapter.eventType(event.eventType()), event.createdAt());
        return true;
    }

    private DispatchResult processOne(long dispatchId, Instant now) {
        ChatRobotDispatch dispatch = dispatchRepository.findByIdForUpdate(dispatchId)
                .orElseThrow(() -> BusinessException.notFound("ROBOT_DISPATCH_NOT_FOUND",
                        "机器人投递任务不存在"));
        if (dispatch.status() == RobotDispatchStatus.PUBLISHED
                || dispatch.status() == RobotDispatchStatus.SKIPPED) {
            return result(dispatch, false);
        }
        if (dispatch.status() == RobotDispatchStatus.PROCESSING) {
            if (dispatch.lockedUntil() != null && dispatch.lockedUntil().isAfter(now)) {
                return result(dispatch, false);
            }
            dispatchRepository.resetExpiredProcessing(dispatch.id(), now);
            dispatch = dispatchRepository.findByIdForUpdate(dispatch.id()).orElseThrow();
        }
        if (!retryPolicy.canRetry(dispatch.attemptCount())) {
            return result(dispatch, false);
        }
        if (dispatchRepository.markProcessing(dispatch.id(), retryPolicy.lockedUntil(now), now) != 1) {
            return result(dispatchRepository.findById(dispatch.id()).orElse(dispatch), false);
        }

        try {
            RobotEventType eventType = eventAdapter.eventType(dispatch.eventType());
            ChatRobot robot = robotRepository.findById(dispatch.robotId())
                    .orElseThrow(() -> BusinessException.notFound("ROBOT_NOT_FOUND", "机器人不存在"));
            if (!robot.isEnabled()) {
                return skip(dispatch, "ROBOT_DISABLED", now);
            }
            ChatRobotTemplate template = templateRepository.findActive(robot.id(), eventType)
                    .orElse(null);
            if (template == null) {
                return skip(dispatch, "TEMPLATE_NOT_FOUND", now);
            }
            RobotDispatchRepository.UnscheduledGameEvent event =
                    dispatchRepository.findGameEventById(dispatch.gameEventId()).orElse(null);
            if (event == null) {
                return skip(dispatch, "GAME_EVENT_NOT_FOUND", now);
            }
            RobotEventType checkedEventType = eventAdapter.eventType(event.eventType());
            ChatMessage message;
            if (checkedEventType == RobotEventType.BETTING_CLOSED) {
                message = publishBettingClosedMessages(dispatch, robot, template, event, now);
            } else if (checkedEventType == RobotEventType.DRAW_RESULT) {
                Optional<ChatMessage> drawMessage = publishDrawMessages(dispatch, robot, template,
                        event, now);
                if (drawMessage.isEmpty()) {
                    return skip(dispatch, "DRAW_COMPONENTS_DISABLED", now);
                }
                message = drawMessage.get();
            } else {
                String content = templateRenderer.render(template,
                        eventAdapter.renderContext(event, robot));
                String payload = payload(dispatch, robot, template, event);
                message = chatMessageService.publishRobotMessage(robot.id(),
                        robot.displayName(), ROOM_CODE, event.issueNumber(),
                        idempotency.idempotencyKey(event.id()), content, payload, now);
            }
            if (dispatchRepository.markPublished(dispatch.id(), message.id(), now) != 1) {
                throw new IllegalStateException("机器人投递状态更新失败");
            }
            return new DispatchResult(dispatch.id(), RobotDispatchStatus.PUBLISHED,
                    message.id(), true);
        } catch (BusinessException exception) {
            if (isPermanentTemplateError(exception)) {
                return skip(dispatch, exception.code(), now);
            }
            return fail(dispatch, exception.code() + ":" + exception.publicMessage(), now);
        } catch (RuntimeException exception) {
            return fail(dispatch, safeError(exception), now);
        }
    }

    private ChatMessage publishBettingClosedMessages(ChatRobotDispatch dispatch, ChatRobot robot,
                                                     ChatRobotTemplate template,
                                                     RobotDispatchRepository.UnscheduledGameEvent event,
                                                     Instant now) {
        String stopContent = templateRenderer.render(template, eventAdapter.renderContext(event, robot));
        chatMessageService.publishRobotMessage(robot.id(), robot.displayName(), ROOM_CODE,
                event.issueNumber(), idempotency.idempotencyKey(event.id()), stopContent,
                payload(dispatch, robot, template, event), now);

        String auditKey = idempotency.idempotencyKey(event.id()) + "-audit";
        return chatMessageService.publishRobotMessage(robot.id(), robot.displayName(), ROOM_CODE,
                event.issueNumber(), auditKey, bettingAuditText(event.issueNumber()),
                payload(dispatch, robot, template, event), now);
    }

    private String bettingAuditText(String issueNumber) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (GameDataRepository.BetAuditRecord bet : gameDataRepository.findBetAuditByIssue(issueNumber)) {
            grouped.computeIfAbsent(normalizeDisplayName(bet.displayName()), ignored -> new ArrayList<>())
                    .add(formatBetText(bet.playType(), bet.parameters(), bet.stake()));
        }
        StringBuilder content = new StringBuilder("-----------\n")
                .append(issueNumber).append('\n')
                .append("核对列表:(").append(String.format("%04d", Math.floorMod(issueNumber.hashCode(), 10000)))
                .append(")\n");
        grouped.forEach((name, bets) -> content.append('(').append(name).append(") \"")
                .append(String.join("，", bets)).append("\"\n"));
        return content.append("-----------\n")
                .append("不在核对列表无效,在核对列表的以开奖前是否公告退单为准!")
                .toString();
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "匿名用户";
        }
        return displayName.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String formatBetText(PlayType playType, List<Integer> parameters, BigDecimal stake) {
        String amount = stake.stripTrailingZeros().toPlainString();
        String values = parameters.stream().map(String::valueOf).collect(Collectors.joining());
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
                    .collect(Collectors.joining("/")) + "特" + amount;
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

    private DispatchResult skip(ChatRobotDispatch dispatch, String reason, Instant now) {
        dispatchRepository.markSkipped(dispatch.id(), reason, now);
        return new DispatchResult(dispatch.id(), RobotDispatchStatus.SKIPPED, null, true);
    }

    private DispatchResult fail(ChatRobotDispatch dispatch, String error, Instant now) {
        int nextAttempt = dispatch.attemptCount() + 1;
        dispatchRepository.markFailed(dispatch.id(), nextAttempt,
                retryPolicy.nextAttemptAt(now, nextAttempt), error, now);
        return new DispatchResult(dispatch.id(), RobotDispatchStatus.FAILED, null, true);
    }

    private String payload(ChatRobotDispatch dispatch, ChatRobot robot,
                           ChatRobotTemplate template,
                           RobotDispatchRepository.UnscheduledGameEvent event) {
        try {
            return objectMapper.writeValueAsString(new RobotMessagePayload(
                    dispatch.gameEventId(), dispatch.id(), robot.robotCode(),
                    template.version(), event.eventType()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成机器人追踪数据失败", exception);
        }
    }

    private Optional<ChatMessage> publishDrawMessages(ChatRobotDispatch dispatch, ChatRobot robot,
                                                       ChatRobotTemplate template,
                                                       RobotDispatchRepository.UnscheduledGameEvent event,
                                                       Instant now) {
        GameDataRepository.IssueRecord issue = gameDataRepository
                .findIssueByIssueNumber(event.issueNumber())
                .filter(value -> value.numbers().size() == 8
                        && value.numbers().stream().allMatch(java.util.Objects::nonNull))
                .orElseThrow(() -> BusinessException.badRequest("ROBOT_DRAW_DATA_NOT_FOUND",
                        "开奖事件对应的期号数据不存在或不完整"));
        String content = templateRenderer.render(template, eventAdapter.renderContext(event, robot));
        ChatMessage latest = null;
        for (RobotDrawComponentConfig config : drawComponentRepository.findEnabledOrdered(robot.id())) {
            String component = config.component().name();
            String componentPayload = structuredPayload(component, event.issueNumber(), issue);
            latest = chatMessageService.publishRobotMessage(robot.id(), robot.displayName(), ROOM_CODE,
                    event.issueNumber(), idempotency.idempotencyKey(event.id(), config.component()),
                    content, componentPayload, now);
        }
        return Optional.ofNullable(latest);
    }

    private String structuredPayload(String component, String issueNumber,
                                     GameDataRepository.IssueRecord currentIssue) {
        RobotDrawComponent checkedComponent = RobotDrawComponent.valueOf(component);
        Object data = switch (checkedComponent) {
            case DRAW_SUMMARY -> new DrawSummaryData(currentIssue.numbers(), currentIssue.settledAt());
            case DRAW_HISTORY -> new DrawHistoryData(
                    historyItems(gameDataRepository.findSettledIssues(15)),
                    historyItems(gameDataRepository.findLatestSettledBlock(60)));
            case WINNER_LIST -> winnerData(issueNumber, currentIssue);
        };
        try {
            return objectMapper.writeValueAsString(new StructuredPayload(
                    "xupan.chat-payload.v1", checkedComponent.name(), issueNumber, data));
        } catch (JacksonException exception) {
            throw new IllegalStateException("生成开奖结构化消息失败", exception);
        }
    }

    private WinnerListData winnerData(String issueNumber, GameDataRepository.IssueRecord issue) {
        List<WinnerItem> items = new ArrayList<>();
        Map<Long, GameDataRepository.BetRecord> betsById = gameDataRepository.findBetsByIssue(issueNumber)
                .stream().collect(Collectors.toMap(GameDataRepository.BetRecord::id, value -> value));
        for (GameDataRepository.WinnerRecord winner : gameDataRepository.findWinningBets(issueNumber)) {
            GameDataRepository.BetRecord bet = betsById.get(winner.betId());
            String betText = bet == null ? winner.playType() : formatBetText(bet.playType(), bet.parameters(), bet.stake());
            items.add(new WinnerItem(normalizeDisplayName(winner.displayName()), winner.ballNumber(),
                    winner.playType(), betText, winner.stake(), winner.netProfit()));
        }
        return new WinnerListData(items, items.isEmpty() ? "暂无获胜记录" : null,
                issue.numbers(), issue.settledAt());
    }

    private static List<DrawHistoryItem> historyItems(List<GameDataRepository.IssueRecord> issues) {
        return issues.stream()
                .map(issue -> new DrawHistoryItem(issue.issueNumber(), issue.numbers(), issue.settledAt()))
                .toList();
    }

    private static boolean isPermanentTemplateError(BusinessException exception) {
        return exception.code().startsWith("ROBOT_TEMPLATE")
                || exception.code().startsWith("ROBOT_RENDER")
                || exception.code().startsWith("ROBOT_DRAW")
                || exception.code().equals("ROBOT_EVENT_TYPE_INVALID")
                || exception.code().equals("ROBOT_EVENT_CONTEXT_INVALID");
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ":" + message;
    }

    private static DispatchResult result(ChatRobotDispatch dispatch, boolean changed) {
        return new DispatchResult(dispatch.id(), dispatch.status(), dispatch.messageId(), changed);
    }

    public record DispatchResult(long dispatchId, RobotDispatchStatus status,
                                 Long messageId, boolean changed) {
    }

    private record RobotMessagePayload(long gameEventId, long dispatchId, String robotCode,
                                       int templateVersion, String eventType) {
    }

    private record StructuredPayload(String schema, String component, String issueNumber, Object data) {
    }

    private record DrawSummaryData(List<Integer> numbers, Instant settledAt) {
    }

    private record DrawHistoryData(List<DrawHistoryItem> items, List<DrawHistoryItem> routeItems) {
    }

    private record DrawHistoryItem(String issueNumber, List<Integer> numbers, Instant settledAt) {
    }

    private record WinnerListData(List<WinnerItem> items, String emptyMessage,
                                  List<Integer> numbers, Instant settledAt) {
    }

    private record WinnerItem(String maskedUser, int ballNumber, String playType,
                              String betText, BigDecimal stake, BigDecimal netProfit) {
    }
}
