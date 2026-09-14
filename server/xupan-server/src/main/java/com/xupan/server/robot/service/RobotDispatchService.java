package com.xupan.server.robot.service;

import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.service.ChatMessageService;
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
import java.util.List;
import java.util.Optional;

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
                                TransactionTemplate transactionTemplate) {
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
            String content = templateRenderer.render(template,
                    eventAdapter.renderContext(event, robot));
            String payload = payload(dispatch, robot, template, event);
            ChatMessage message = chatMessageService.publishRobotMessage(robot.id(),
                    robot.displayName(), ROOM_CODE, event.issueNumber(),
                    idempotency.idempotencyKey(event.id()), content, payload, now);
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

    private static boolean isPermanentTemplateError(BusinessException exception) {
        return exception.code().startsWith("ROBOT_TEMPLATE")
                || exception.code().startsWith("ROBOT_RENDER")
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
}
