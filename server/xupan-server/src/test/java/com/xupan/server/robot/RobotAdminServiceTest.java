package com.xupan.server.robot;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.realtime.ChatRobotUpdatedEvent;
import com.xupan.server.chat.repository.ChatMessageRepository;
import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.ChatRobotDispatch;
import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.domain.RobotEventType;
import com.xupan.server.robot.domain.RobotStatus;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import com.xupan.server.robot.repository.RobotDrawComponentRepository;
import com.xupan.server.robot.repository.RobotRepository;
import com.xupan.server.robot.repository.RobotTemplateRepository;
import com.xupan.server.robot.service.RobotAdminService;
import com.xupan.server.robot.service.RobotTemplateRenderer;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RobotAdminServiceTest {

    private static final long OPERATOR_ID = 7L;

    @Mock
    private RobotRepository robotRepository;
    @Mock
    private RobotTemplateRepository templateRepository;
    @Mock
    private RobotDispatchRepository dispatchRepository;
    @Mock
    private RobotTemplateRenderer templateRenderer;
    @Mock
    private PermissionService permissionService;
    @Mock
    private OperationAuditRepository auditRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private RobotDrawComponentRepository drawComponentRepository;

    private RobotAdminService service;

    @BeforeEach
    void setUp() {
        service = new RobotAdminService(robotRepository, templateRepository, dispatchRepository,
                templateRenderer, permissionService, auditRepository, chatMessageRepository,
                eventPublisher, drawComponentRepository);
    }

    @Test
    void deniesWithoutPermissionAndAuditsFailure() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_READ")).thenReturn(false);

        assertThatThrownBy(() -> service.listRobots(OPERATOR_ID, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ROBOT_OPERATION_FORBIDDEN");

        verify(auditRepository).record(eq(OPERATOR_ID), eq("ROBOT_READ"), eq("GET"),
                eq("/api/admin/robots"), eq(null), eq("FAILURE"),
                eq("AUTH_PERMISSION_DENIED"), eq("action=permission-check"), eq(null), any());
    }

    @Test
    void createsRobotWithDefaultEnabledStatusAndAuditsAction() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_WRITE")).thenReturn(true);
        when(robotRepository.findByCode("notice_bot")).thenReturn(Optional.empty());
        when(robotRepository.insert("notice_bot", "通知机器人", "robot-default",
                RobotStatus.ENABLED, 30, 5)).thenReturn(11L);
        ChatRobot robot = robot(11L, "notice_bot", "通知机器人", RobotStatus.ENABLED, 30, 5);
        when(robotRepository.findById(11L)).thenReturn(Optional.of(robot));
        stubDispatchStatistics(11L);
        when(templateRepository.findAll(11L)).thenReturn(List.of());

        RobotAdminService.RobotDetail result = service.createRobot(OPERATOR_ID, "notice_bot",
                "通知机器人", "robot-default", 30, 5);

        assertThat(result.robot()).isEqualTo(robot);
        verify(robotRepository).insert("notice_bot", "通知机器人", "robot-default",
                RobotStatus.ENABLED, 30, 5);
        verify(auditRepository).record(eq(OPERATOR_ID), eq("ROBOT_WRITE"), eq("POST"),
                eq("/api/admin/robots"), eq(null), eq("SUCCESS"), eq(null),
                eq("action=create"), eq(null), any());
    }

    @Test
    void updatesRobotNameAndSynchronizesHistoricalMessages() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_WRITE")).thenReturn(true);
        ChatRobot previous = robot(11L, "notice_bot", "旧名称", RobotStatus.ENABLED, 30, 5);
        ChatRobot updated = robot(11L, "notice_bot", "新名称", RobotStatus.ENABLED, 30, 5);
        when(robotRepository.findById(11L)).thenReturn(Optional.of(previous), Optional.of(updated));
        when(robotRepository.update(11L, "新名称", "robot-default", 30, 5)).thenReturn(1);
        when(chatMessageRepository.updateRobotSenderName(11L, "新名称")).thenReturn(3);
        stubDispatchStatistics(11L);
        when(templateRepository.findAll(11L)).thenReturn(List.of());

        RobotAdminService.RobotDetail result = service.updateRobot(OPERATOR_ID, 11L,
                "新名称", "robot-default", 30, 5);

        assertThat(result.robot()).isEqualTo(updated);
        verify(chatMessageRepository).updateRobotSenderName(11L, "新名称");
        verify(eventPublisher).publishEvent(any(ChatRobotUpdatedEvent.class));
    }

    @Test
    void repairsHistoricalMessagesEvenWhenRobotNameIsAlreadyCurrent() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_WRITE")).thenReturn(true);
        ChatRobot robot = robot(11L, "notice_bot", "机器人", RobotStatus.ENABLED, 30, 5);
        when(robotRepository.findById(11L)).thenReturn(Optional.of(robot), Optional.of(robot));
        when(robotRepository.update(11L, "机器人", "robot-default", 30, 5)).thenReturn(1);
        when(chatMessageRepository.updateRobotSenderName(11L, "机器人")).thenReturn(7784);
        stubDispatchStatistics(11L);
        when(templateRepository.findAll(11L)).thenReturn(List.of());

        service.updateRobot(OPERATOR_ID, 11L, "机器人", "robot-default", 30, 5);

        verify(chatMessageRepository).updateRobotSenderName(11L, "机器人");
        verify(eventPublisher).publishEvent(any(ChatRobotUpdatedEvent.class));
    }

    @Test
    void updatesTemplateByDisablingPreviousVersionAndUsingNextVersion() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_TEMPLATE_WRITE")).thenReturn(true);
        ChatRobot robot = robot(11L, "notice_bot", "通知机器人", RobotStatus.ENABLED, 30, 5);
        ChatRobotTemplate previous = template(4L, 11L, RobotEventType.ISSUE_STARTED, "旧模板", true, 3);
        ChatRobotTemplate current = template(5L, 11L, RobotEventType.ISSUE_STARTED, "新模板", true, 4);
        when(robotRepository.findById(11L)).thenReturn(Optional.of(robot));
        when(templateRepository.findLatestVersionForUpdate(11L, RobotEventType.ISSUE_STARTED))
                .thenReturn(Optional.of(previous));
        when(templateRepository.findActive(11L, RobotEventType.ISSUE_STARTED))
                .thenReturn(Optional.of(current));

        ChatRobotTemplate result = service.updateTemplate(OPERATOR_ID, 11L, "ISSUE_STARTED",
                "新模板");

        assertThat(result).isEqualTo(current);
        verify(templateRepository).disableVersions(11L, RobotEventType.ISSUE_STARTED);
        verify(templateRepository).insertVersion(11L, RobotEventType.ISSUE_STARTED, "default",
                "新模板", true, 4);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditRepository).record(eq(OPERATOR_ID), eq("ROBOT_TEMPLATE_WRITE"), eq("PUT"),
                eq("/api/admin/robots/11/templates/ISSUE_STARTED"), eq("11"), eq("SUCCESS"),
                eq(null), summary.capture(), eq(null), any());
        assertThat(summary.getValue()).doesNotContain("新模板");
    }

    @Test
    void previewOnlyRendersCandidateAndDoesNotWriteTemplate() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_TEMPLATE_WRITE")).thenReturn(true);
        ChatRobot robot = robot(11L, "notice_bot", "通知机器人", RobotStatus.ENABLED, 30, 5);
        when(robotRepository.findById(11L)).thenReturn(Optional.of(robot));
        when(templateRepository.findActive(11L, RobotEventType.DRAW_RESULT)).thenReturn(Optional.empty());
        when(templateRenderer.render(any(), any())).thenReturn("预览结果");

        RobotAdminService.TemplatePreview result = service.previewTemplate(OPERATOR_ID, 11L,
                "DRAW_RESULT", "{{issueNumber}}：{{eventMessage}}");

        assertThat(result.renderedText()).isEqualTo("预览结果");
        verify(templateRepository).findActive(11L, RobotEventType.DRAW_RESULT);
        verify(auditRepository).record(eq(OPERATOR_ID), eq("ROBOT_TEMPLATE_WRITE"), eq("POST"),
                eq("/api/admin/robots/11/templates/DRAW_RESULT/preview"), eq("11"),
                eq("SUCCESS"), eq(null), any(), eq(null), any());
    }

    @Test
    void retriesOnlyFailedDispatch() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_WRITE")).thenReturn(true);
        ChatRobotDispatch failed = dispatch(21L, RobotDispatchStatus.FAILED);
        ChatRobotDispatch pending = dispatch(21L, RobotDispatchStatus.PENDING);
        when(dispatchRepository.findById(21L)).thenReturn(Optional.of(failed), Optional.of(pending));
        when(dispatchRepository.resetFailedForRetry(eq(21L), any())).thenReturn(1);

        ChatRobotDispatch result = service.retryDispatch(OPERATOR_ID, 21L);

        assertThat(result.status()).isEqualTo(RobotDispatchStatus.PENDING);
        verify(dispatchRepository).resetFailedForRetry(eq(21L), any());
    }

    @Test
    void rejectsRetryForPublishedDispatch() {
        when(permissionService.hasPermission(OPERATOR_ID, "ROBOT_WRITE")).thenReturn(true);
        when(dispatchRepository.findById(21L))
                .thenReturn(Optional.of(dispatch(21L, RobotDispatchStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.retryDispatch(OPERATOR_ID, 21L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ROBOT_DISPATCH_RETRY_INVALID");
    }

    private void stubDispatchStatistics(long robotId) {
        when(dispatchRepository.countByRobotIdAndStatus(robotId, RobotDispatchStatus.PENDING))
                .thenReturn(0L);
        when(dispatchRepository.countByRobotIdAndStatus(robotId, RobotDispatchStatus.PROCESSING))
                .thenReturn(0L);
        when(dispatchRepository.countByRobotIdAndStatus(robotId, RobotDispatchStatus.FAILED))
                .thenReturn(0L);
        when(dispatchRepository.countByRobotIdAndStatus(robotId, RobotDispatchStatus.PUBLISHED))
                .thenReturn(0L);
        when(dispatchRepository.countByRobotIdAndStatus(robotId, RobotDispatchStatus.SKIPPED))
                .thenReturn(0L);
    }

    private static ChatRobot robot(long id, String code, String name, RobotStatus status,
                                   int weight, int delaySeconds) {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        return new ChatRobot(id, code, name, "robot-default", status, weight, delaySeconds, now, now);
    }

    private static ChatRobotTemplate template(long id, long robotId, RobotEventType eventType,
                                              String text, boolean enabled, int version) {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        return new ChatRobotTemplate(id, robotId, eventType.name(), "default", text, enabled,
                version, now, now);
    }

    private static ChatRobotDispatch dispatch(long id, RobotDispatchStatus status) {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        return new ChatRobotDispatch(id, 100L, 11L, "20260913001", "DRAW_RESULT", status,
                1, now, null, null, "temporary", now, now, null);
    }
}
