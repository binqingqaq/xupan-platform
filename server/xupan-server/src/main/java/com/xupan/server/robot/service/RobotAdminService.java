package com.xupan.server.robot.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.realtime.ChatRobotUpdatedEvent;
import com.xupan.server.chat.repository.ChatMessageRepository;
import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.ChatRobotDispatch;
import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.domain.RobotDrawComponent;
import com.xupan.server.robot.domain.RobotDrawComponentConfig;
import com.xupan.server.robot.domain.RobotEventType;
import com.xupan.server.robot.domain.RobotStatus;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import com.xupan.server.robot.repository.RobotDrawComponentRepository;
import com.xupan.server.robot.repository.RobotRepository;
import com.xupan.server.robot.repository.RobotTemplateRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Application service for the robot administration API.
 *
 * <p>The service owns permission and audit boundaries so callers cannot bypass
 * them by invoking the service outside of the HTTP controller.</p>
 */
@Service
public class RobotAdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TEMPLATE_LENGTH = 1000;
    private static final String DEFAULT_TEMPLATE_CODE = "default";
    private static final String PERMISSION_READ = "ROBOT_READ";
    private static final String PERMISSION_WRITE = "ROBOT_WRITE";
    private static final String PERMISSION_TEMPLATE_WRITE = "ROBOT_TEMPLATE_WRITE";
    private static final String CHAT_ROOM_CODE = "main";

    private final RobotRepository robotRepository;
    private final RobotTemplateRepository templateRepository;
    private final RobotDispatchRepository dispatchRepository;
    private final RobotTemplateRenderer templateRenderer;
    private final PermissionService permissionService;
    private final OperationAuditRepository auditRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RobotDrawComponentRepository drawComponentRepository;

    public RobotAdminService(RobotRepository robotRepository,
                             RobotTemplateRepository templateRepository,
                             RobotDispatchRepository dispatchRepository,
                             RobotTemplateRenderer templateRenderer,
                             PermissionService permissionService,
                             OperationAuditRepository auditRepository,
                             ChatMessageRepository chatMessageRepository,
                             ApplicationEventPublisher eventPublisher,
                             RobotDrawComponentRepository drawComponentRepository) {
        this.robotRepository = robotRepository;
        this.templateRepository = templateRepository;
        this.dispatchRepository = dispatchRepository;
        this.templateRenderer = templateRenderer;
        this.permissionService = permissionService;
        this.auditRepository = auditRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.eventPublisher = eventPublisher;
        this.drawComponentRepository = drawComponentRepository;
    }

    @Transactional(readOnly = true)
    public RobotPage listRobots(long operatorUserId, int page, int pageSize) {
        String path = "/api/admin/robots";
        requirePermission(operatorUserId, PERMISSION_READ, "GET", path, null);
        validatePage(page, pageSize);
        List<ChatRobot> all = robotRepository.findAll();
        int from = Math.min((page - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new RobotPage(all.subList(from, to), page, pageSize, all.size());
    }

    @Transactional
    public RobotDetail createRobot(long operatorUserId, String robotCode, String displayName,
                                   String avatarKey, int weight, int delaySeconds) {
        String path = "/api/admin/robots";
        requirePermission(operatorUserId, PERMISSION_WRITE, "POST", path, null);
        return audited(operatorUserId, PERMISSION_WRITE, "POST", path, null,
                "action=create", () -> {
                    String code = normalizeRobotCode(robotCode);
                    String name = normalizeText(displayName, "ROBOT_DISPLAY_NAME_INVALID",
                            "机器人显示名称不能为空或过长", 32);
                    String avatar = normalizeText(avatarKey, "ROBOT_AVATAR_INVALID",
                            "机器人头像键不能为空或过长", 64);
                    validateWeight(weight);
                    validateDelay(delaySeconds);
                    if (robotRepository.findByCode(code).isPresent()) {
                        throw BusinessException.conflict("ROBOT_CODE_EXISTS", "机器人编码已存在");
                    }
                    try {
                        long id = robotRepository.insert(code, name, avatar, RobotStatus.ENABLED,
                                weight, delaySeconds);
                        drawComponentRepository.insertDefaults(id);
                        ChatRobot robot = requireRobot(id);
                        return detail(robot);
                    } catch (DataIntegrityViolationException exception) {
                        throw BusinessException.conflict("ROBOT_CODE_EXISTS", "机器人编码已存在");
                    }
                });
    }

    @Transactional(readOnly = true)
    public RobotDetail getRobot(long operatorUserId, long robotId) {
        String path = "/api/admin/robots/" + robotId;
        requirePermission(operatorUserId, PERMISSION_READ, "GET", path, Long.toString(robotId));
        return detail(requireRobot(robotId));
    }

    @Transactional
    public RobotDetail updateRobot(long operatorUserId, long robotId, String displayName,
                                   String avatarKey, int weight, int delaySeconds) {
        String path = "/api/admin/robots/" + robotId;
        requirePermission(operatorUserId, PERMISSION_WRITE, "PUT", path, Long.toString(robotId));
        return audited(operatorUserId, PERMISSION_WRITE, "PUT", path, Long.toString(robotId),
                "action=update", () -> {
                    ChatRobot current = requireRobot(robotId);
                    String name = normalizeText(displayName, "ROBOT_DISPLAY_NAME_INVALID",
                            "机器人显示名称不能为空或过长", 32);
                    String avatar = normalizeText(avatarKey, "ROBOT_AVATAR_INVALID",
                            "机器人头像键不能为空或过长", 64);
                    validateWeight(weight);
                    validateDelay(delaySeconds);
                    if (robotRepository.update(robotId, name, avatar, weight, delaySeconds) != 1) {
                        throw BusinessException.notFound("ROBOT_NOT_FOUND", "机器人不存在");
                    }
                    int synchronizedMessages = chatMessageRepository.updateRobotSenderName(robotId, name);
                    if (!current.displayName().equals(name) || synchronizedMessages > 0) {
                        eventPublisher.publishEvent(new ChatRobotUpdatedEvent(robotId, name,
                                CHAT_ROOM_CODE));
                    }
                    return detail(requireRobot(robotId));
                });
    }

    @Transactional
    public RobotDetail changeStatus(long operatorUserId, long robotId, String status) {
        String path = "/api/admin/robots/" + robotId + "/status";
        requirePermission(operatorUserId, PERMISSION_WRITE, "PATCH", path, Long.toString(robotId));
        return audited(operatorUserId, PERMISSION_WRITE, "PATCH", path, Long.toString(robotId),
                "action=status", () -> {
                    RobotStatus normalizedStatus = parseRobotStatus(status);
                    requireRobot(robotId);
                    if (robotRepository.updateStatus(robotId, normalizedStatus) != 1) {
                        throw BusinessException.notFound("ROBOT_NOT_FOUND", "机器人不存在");
                    }
                    return detail(requireRobot(robotId));
                });
    }

    @Transactional
    public RobotDetail updateAvatar(long operatorUserId, long robotId, String avatarKey) {
        String path = "/api/admin/robots/" + robotId + "/avatar";
        requirePermission(operatorUserId, PERMISSION_WRITE, "PUT", path, Long.toString(robotId));
        return audited(operatorUserId, PERMISSION_WRITE, "PUT", path, Long.toString(robotId),
                "action=avatar-update", () -> {
                    ChatRobot robot = requireRobot(robotId);
                    if (avatarKey == null || avatarKey.isBlank() || avatarKey.length() > 255) {
                        throw BusinessException.badRequest("ROBOT_AVATAR_INVALID", "机器人头像键无效");
                    }
                    if (robotRepository.updateAvatarKey(robotId, avatarKey) != 1) {
                        throw BusinessException.notFound("ROBOT_NOT_FOUND", "机器人不存在");
                    }
                    return detail(robotRepository.findById(robotId).orElseThrow(() ->
                            BusinessException.notFound("ROBOT_NOT_FOUND", "机器人不存在")));
                });
    }

    @Transactional(readOnly = true)
    public List<ChatRobotTemplate> listTemplates(long operatorUserId, long robotId) {
        String path = "/api/admin/robots/" + robotId + "/templates";
        requirePermission(operatorUserId, PERMISSION_READ, "GET", path, Long.toString(robotId));
        requireRobot(robotId);
        return templateRepository.findAll(robotId);
    }

    @Transactional(readOnly = true)
    public List<RobotDrawComponentConfig> listDrawComponents(long operatorUserId, long robotId) {
        String path = "/api/admin/robots/" + robotId + "/draw-components";
        requirePermission(operatorUserId, PERMISSION_READ, "GET", path, Long.toString(robotId));
        requireRobot(robotId);
        return drawComponentRepository.findAll(robotId);
    }

    @Transactional
    public List<RobotDrawComponentConfig> updateDrawComponents(long operatorUserId, long robotId,
                                                                List<DrawComponentUpdate> updates) {
        String path = "/api/admin/robots/" + robotId + "/draw-components";
        requirePermission(operatorUserId, PERMISSION_WRITE, "PUT", path,
                Long.toString(robotId));
        return audited(operatorUserId, PERMISSION_WRITE, "PUT", path,
                Long.toString(robotId), "action=draw-components-update", () -> {
                    requireRobot(robotId);
                    List<RobotDrawComponentConfig> configs = normalizeDrawComponents(updates);
                    drawComponentRepository.replaceAll(robotId, configs);
                    return drawComponentRepository.findAll(robotId);
                });
    }

    @Transactional
    public ChatRobotTemplate updateTemplate(long operatorUserId, long robotId, String eventType,
                                            String templateText) {
        String path = "/api/admin/robots/" + robotId + "/templates/" + eventType;
        requirePermission(operatorUserId, PERMISSION_TEMPLATE_WRITE, "PUT", path,
                Long.toString(robotId));
        RobotEventType normalizedEvent = parseEventType(eventType);
        path = "/api/admin/robots/" + robotId + "/templates/" + normalizedEvent.name();
        return audited(operatorUserId, PERMISSION_TEMPLATE_WRITE, "PUT", path,
                Long.toString(robotId), "action=template-update,eventType=" + normalizedEvent.name()
                        + ",templateHash=" + digest(templateText), () -> {
                    requireRobot(robotId);
                    String normalizedText = normalizeTemplate(templateText);
                    ChatRobotTemplate latest = templateRepository
                            .findLatestVersionForUpdate(robotId, normalizedEvent).orElse(null);
                    int nextVersion = latest == null ? 1 : latest.version() + 1;
                    templateRepository.disableVersions(robotId, normalizedEvent);
                    try {
                        templateRepository.insertVersion(robotId, normalizedEvent,
                                DEFAULT_TEMPLATE_CODE, normalizedText, true, nextVersion);
                    } catch (DataIntegrityViolationException exception) {
                        throw BusinessException.conflict("ROBOT_TEMPLATE_VERSION_CONFLICT",
                                "机器人模板版本冲突，请重试");
                    }
                    return templateRepository.findActive(robotId, normalizedEvent)
                            .orElseThrow(() -> BusinessException.conflict(
                                    "ROBOT_TEMPLATE_SAVE_FAILED", "机器人模板保存失败"));
                });
    }

    @Transactional
    public TemplatePreview previewTemplate(long operatorUserId, long robotId, String eventType,
                                           String templateText) {
        String path = "/api/admin/robots/" + robotId + "/templates/" + eventType + "/preview";
        requirePermission(operatorUserId, PERMISSION_TEMPLATE_WRITE, "POST", path,
                Long.toString(robotId));
        RobotEventType normalizedEvent = parseEventType(eventType);
        path = "/api/admin/robots/" + robotId + "/templates/"
                + normalizedEvent.name() + "/preview";
        return audited(operatorUserId, PERMISSION_TEMPLATE_WRITE, "POST", path,
                Long.toString(robotId), "action=template-preview,eventType=" + normalizedEvent.name()
                        + ",templateHash=" + digest(templateText), () -> {
                    ChatRobot robot = requireRobot(robotId);
                    String normalizedText = normalizeTemplate(templateText);
                    ChatRobotTemplate candidate =
                            new ChatRobotTemplate(0L, robotId,
                            normalizedEvent.name(), DEFAULT_TEMPLATE_CODE, normalizedText, true,
                            currentVersion(robotId, normalizedEvent), Instant.now(), Instant.now());
                    String issueNumber = "PREVIEW-0001";
                    String eventMessage = "示例：机器人事件消息";
                    String rendered = templateRenderer.render(candidate,
                            new RobotRenderContext(issueNumber,
                                    normalizedEvent.name(), eventMessage, robot.displayName(),
                                    Instant.parse("2026-09-13T00:00:00Z"), "1,2,3,4,5,6,7"));
                    return new TemplatePreview(normalizedEvent.name(), candidate.version(),
                            normalizedText, rendered, issueNumber, eventMessage);
                });
    }

    @Transactional(readOnly = true)
    public DispatchPage listDispatches(long operatorUserId, String issueNumber, String eventType,
                                       String status, Instant from, Instant to,
                                       int page, int pageSize) {
        String path = "/api/admin/robots/dispatches";
        requirePermission(operatorUserId, PERMISSION_READ, "GET", path, null);
        validatePage(page, pageSize);
        String normalizedIssue = optionalText(issueNumber, 64);
        RobotEventType normalizedEvent = optionalEventType(eventType);
        RobotDispatchStatus normalizedStatus = optionalDispatchStatus(status);
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.badRequest("ROBOT_QUERY_INVALID", "投递记录时间范围无效");
        }
        List<ChatRobotDispatch> items = dispatchRepository.findPage(normalizedIssue,
                normalizedEvent, normalizedStatus, from, to, page, pageSize);
        long total = dispatchRepository.count(normalizedIssue, normalizedEvent,
                normalizedStatus, from, to);
        return new DispatchPage(items, page, pageSize, total);
    }

    @Transactional
    public ChatRobotDispatch retryDispatch(long operatorUserId, long dispatchId) {
        String path = "/api/admin/robots/dispatches/" + dispatchId + "/retry";
        requirePermission(operatorUserId, PERMISSION_WRITE, "POST", path,
                Long.toString(dispatchId));
        return audited(operatorUserId, PERMISSION_WRITE, "POST", path,
                Long.toString(dispatchId), "action=dispatch-retry", () -> {
                    ChatRobotDispatch dispatch = dispatchRepository.findById(dispatchId)
                            .orElseThrow(() -> BusinessException.notFound(
                                    "ROBOT_DISPATCH_NOT_FOUND", "机器人投递任务不存在"));
                    if (dispatch.status() != RobotDispatchStatus.FAILED) {
                        throw BusinessException.conflict("ROBOT_DISPATCH_RETRY_INVALID",
                                "只有失败的机器人投递任务可以重试");
                    }
                    if (dispatchRepository.resetFailedForRetry(dispatchId, Instant.now()) != 1) {
                        throw BusinessException.conflict("ROBOT_DISPATCH_RETRY_CONFLICT",
                                "机器人投递任务状态已变化，请刷新后重试");
                    }
                    return dispatchRepository.findById(dispatchId)
                            .orElseThrow(() -> BusinessException.notFound(
                                    "ROBOT_DISPATCH_NOT_FOUND", "机器人投递任务不存在"));
                });
    }

    private RobotDetail detail(ChatRobot robot) {
        return new RobotDetail(robot, templateRepository.findAll(robot.id()),
                new DispatchStatistics(
                        dispatchRepository.countByRobotIdAndStatus(robot.id(), RobotDispatchStatus.PENDING),
                        dispatchRepository.countByRobotIdAndStatus(robot.id(), RobotDispatchStatus.PROCESSING),
                        dispatchRepository.countByRobotIdAndStatus(robot.id(), RobotDispatchStatus.FAILED),
                        dispatchRepository.countByRobotIdAndStatus(robot.id(), RobotDispatchStatus.PUBLISHED),
                        dispatchRepository.countByRobotIdAndStatus(robot.id(), RobotDispatchStatus.SKIPPED)));
    }

    private int currentVersion(long robotId, RobotEventType eventType) {
        return templateRepository.findActive(robotId, eventType).map(ChatRobotTemplate::version).orElse(0);
    }

    private ChatRobot requireRobot(long robotId) {
        if (robotId <= 0) {
            throw BusinessException.badRequest("ROBOT_ID_INVALID", "机器人标识无效");
        }
        return robotRepository.findById(robotId)
                .orElseThrow(() -> BusinessException.notFound("ROBOT_NOT_FOUND", "机器人不存在"));
    }

    private void requirePermission(long operatorUserId, String permission, String method,
                                   String path, String resourceId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, permission)) {
            auditFailure(operatorUserId, permission, method, path, resourceId,
                    "AUTH_PERMISSION_DENIED", "action=permission-check");
            throw BusinessException.forbidden("ROBOT_OPERATION_FORBIDDEN", "没有机器人管理权限");
        }
    }

    private <T> T audited(long operatorUserId, String permission, String method, String path,
                           String resourceId, String summary, Supplier<T> action) {
        try {
            T value = action.get();
            auditSuccess(operatorUserId, permission, method, path, resourceId, summary);
            return value;
        } catch (BusinessException exception) {
            auditFailure(operatorUserId, permission, method, path, resourceId,
                    exception.code(), summary);
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(operatorUserId, permission, method, path, resourceId,
                    "ROBOT_INTERNAL_ERROR", summary);
            throw exception;
        }
    }

    private void auditSuccess(long operatorUserId, String permission, String method, String path,
                              String resourceId, String summary) {
        if (operatorUserId > 0) {
            auditRepository.record(operatorUserId, permission, method, path, resourceId,
                    "SUCCESS", null, summary, null, Instant.now());
        }
    }

    private void auditFailure(long operatorUserId, String permission, String method, String path,
                              String resourceId, String errorCode, String summary) {
        if (operatorUserId > 0) {
            auditRepository.record(operatorUserId, permission, method, path, resourceId,
                    "FAILURE", errorCode, summary, null, Instant.now());
        }
    }

    private static String normalizeRobotCode(String value) {
        if (value == null || !value.trim().matches("[a-z0-9_-]{1,32}")) {
            throw BusinessException.badRequest("ROBOT_CODE_INVALID", "机器人编码格式无效");
        }
        return value.trim();
    }

    private static String normalizeTemplate(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEMPLATE_LENGTH) {
            throw BusinessException.badRequest("ROBOT_TEMPLATE_INVALID", "机器人模板不能为空且不能超过 1000 个字符");
        }
        return value.trim();
    }

    private static String normalizeText(String value, String code, String message, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw BusinessException.badRequest(code, message);
        }
        return value.trim();
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw BusinessException.badRequest("ROBOT_QUERY_INVALID", "查询条件过长");
        }
        return normalized;
    }

    private static void validateWeight(int weight) {
        if (weight < 1 || weight > 100) {
            throw BusinessException.badRequest("ROBOT_WEIGHT_INVALID", "机器人权重必须在 1 到 100 之间");
        }
    }

    private static void validateDelay(int delaySeconds) {
        if (delaySeconds < 0 || delaySeconds > 300) {
            throw BusinessException.badRequest("ROBOT_DELAY_INVALID", "机器人延时必须在 0 到 300 秒之间");
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || (long) (page - 1) * pageSize > Integer.MAX_VALUE * 100L) {
            throw BusinessException.badRequest("ROBOT_QUERY_INVALID", "分页参数无效");
        }
    }

    private static RobotStatus parseRobotStatus(String value) {
        try {
            return RobotStatus.valueOf(requiredEnum(value, "ROBOT_STATUS_INVALID"));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("ROBOT_STATUS_INVALID", "机器人状态无效");
        }
    }

    private static RobotEventType parseEventType(String value) {
        try {
            return RobotEventType.valueOf(requiredEnum(value, "ROBOT_EVENT_TYPE_INVALID"));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("ROBOT_EVENT_TYPE_INVALID", "机器人事件类型无效");
        }
    }

    private static List<RobotDrawComponentConfig> normalizeDrawComponents(
            List<DrawComponentUpdate> updates) {
        if (updates == null || updates.size() != RobotDrawComponent.values().length) {
            throw BusinessException.badRequest("ROBOT_DRAW_COMPONENT_INVALID", "必须提供三段配置");
        }
        List<RobotDrawComponentConfig> configs = updates.stream().map(update -> {
            if (update == null || update.component() == null || update.component().isBlank()) {
                throw BusinessException.badRequest("ROBOT_DRAW_COMPONENT_INVALID", "组件不能为空");
            }
            RobotDrawComponent component;
            try {
                component = RobotDrawComponent.fromDatabaseValue(
                        update.component().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw BusinessException.badRequest("ROBOT_DRAW_COMPONENT_INVALID", "组件不支持");
            }
            return new RobotDrawComponentConfig(component, update.enabled(), update.order());
        }).toList();
        if (configs.stream().map(RobotDrawComponentConfig::component).distinct().count()
                != RobotDrawComponent.values().length
                || configs.stream().map(RobotDrawComponentConfig::order).distinct().count()
                != configs.size()
                || configs.stream().anyMatch(config -> config.order() < 1
                || config.order() > configs.size())) {
            throw BusinessException.badRequest("ROBOT_DRAW_COMPONENT_INVALID", "组件或排序配置无效");
        }
        return configs;
    }

    private static RobotEventType optionalEventType(String value) {
        return value == null || value.isBlank() ? null : parseEventType(value);
    }

    private static RobotDispatchStatus optionalDispatchStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RobotDispatchStatus.valueOf(requiredEnum(value, "ROBOT_STATUS_INVALID"));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("ROBOT_QUERY_INVALID", "投递状态无效");
        }
    }

    private static String requiredEnum(String value, String code) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest(code, "枚举参数不能为空");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String digest(String value) {
        if (value == null) {
            return "null";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record RobotPage(List<ChatRobot> items, int page, int pageSize, long total) {
    }

    public record RobotDetail(ChatRobot robot, List<ChatRobotTemplate> templates,
                              DispatchStatistics dispatchStatistics) {
    }

    public record TemplatePreview(String eventType, int version, String templateText,
                                  String renderedText, String issueNumber, String eventMessage) {
    }

    public record DispatchPage(List<ChatRobotDispatch> items, int page, int pageSize, long total) {
    }

    public record DispatchStatistics(long pending, long processing, long failed,
                                     long published, long skipped) {
    }

    public record DrawComponentUpdate(String component, boolean enabled, int order) {
    }
}
