package com.xupan.server.robot.web;

import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.ChatRobotDispatch;
import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.robot.service.RobotAdminService;

import java.time.Instant;
import java.util.List;

public final class RobotAdminResponse {

    private RobotAdminResponse() {
    }

    public record RobotPage(List<RobotSummary> items, int page, int pageSize, long total) {
        static RobotPage from(RobotAdminService.RobotPage page) {
            return new RobotPage(page.items().stream().map(RobotSummary::from).toList(),
                    page.page(), page.pageSize(), page.total());
        }
    }

    public record RobotSummary(long id, String robotCode, String displayName, String avatarKey,
                               String status, int weight, int delaySeconds,
                               Instant createdAt, Instant updatedAt) {
        static RobotSummary from(ChatRobot robot) {
            return new RobotSummary(robot.id(), robot.robotCode(), robot.displayName(),
                    robot.avatarKey(), robot.status().name(), robot.weight(), robot.delaySeconds(),
                    robot.createdAt(), robot.updatedAt());
        }
    }

    public record RobotDetail(RobotSummary robot, List<TemplateSummary> templates,
                              DispatchStatistics dispatchStatistics) {
        static RobotDetail from(RobotAdminService.RobotDetail detail) {
            return new RobotDetail(RobotSummary.from(detail.robot()),
                    detail.templates().stream().map(TemplateSummary::from).toList(),
                    DispatchStatistics.from(detail.dispatchStatistics()));
        }
    }

    public record TemplateList(List<TemplateSummary> items) {
        static TemplateList from(List<ChatRobotTemplate> templates) {
            return new TemplateList(templates.stream().map(TemplateSummary::from).toList());
        }
    }

    public record TemplateSummary(long id, long robotId, String eventType, String templateCode,
                                  String templateText, boolean enabled, int version,
                                  Instant createdAt, Instant updatedAt) {
        static TemplateSummary from(ChatRobotTemplate template) {
            return new TemplateSummary(template.id(), template.robotId(), template.eventType(),
                    template.templateCode(), template.templateText(), template.enabled(),
                    template.version(), template.createdAt(), template.updatedAt());
        }
    }

    public record TemplatePreview(String eventType, int version, String templateText,
                                   String renderedText, String issueNumber, String eventMessage) {
        static TemplatePreview from(RobotAdminService.TemplatePreview preview) {
            return new TemplatePreview(preview.eventType(), preview.version(),
                    preview.templateText(), preview.renderedText(), preview.issueNumber(),
                    preview.eventMessage());
        }
    }

    public record DispatchPage(List<DispatchSummary> items, int page, int pageSize, long total) {
        static DispatchPage from(RobotAdminService.DispatchPage page) {
            return new DispatchPage(page.items().stream().map(DispatchSummary::from).toList(),
                    page.page(), page.pageSize(), page.total());
        }
    }

    public record DispatchSummary(long id, long gameEventId, long robotId, String issueNumber,
                                  String eventType, String status, int attemptCount,
                                  Instant nextAttemptAt, Instant lockedUntil, Long messageId,
                                  String lastError, Instant createdAt, Instant updatedAt,
                                  Instant publishedAt) {
        static DispatchSummary from(ChatRobotDispatch dispatch) {
            return new DispatchSummary(dispatch.id(), dispatch.gameEventId(), dispatch.robotId(),
                    dispatch.issueNumber(), dispatch.eventType(), dispatch.status().name(),
                    dispatch.attemptCount(), dispatch.nextAttemptAt(), dispatch.lockedUntil(),
                    dispatch.messageId(), dispatch.lastError(), dispatch.createdAt(),
                    dispatch.updatedAt(), dispatch.publishedAt());
        }
    }

    public record DispatchStatistics(long pending, long processing, long failed,
                                     long published, long skipped) {
        static DispatchStatistics from(RobotAdminService.DispatchStatistics statistics) {
            return new DispatchStatistics(statistics.pending(), statistics.processing(),
                    statistics.failed(), statistics.published(), statistics.skipped());
        }
    }
}
