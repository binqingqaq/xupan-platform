package com.xupan.server.robot.service;

import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RobotTemplateRendererTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-14T08:00:00Z");
    private final RobotTemplateRenderer renderer = new RobotTemplateRenderer();

    @Test
    void rendersAllowedVariablesForAllSupportedEvents() {
        Stream.of(
                new Case("ISSUE_STARTED", "{{issueNumber}}期开始，{{robotName}}在线。", "1001期开始，开奖助手在线。"),
                new Case("BETTING_WARNING", "提醒：{{eventMessage}}", "提醒：距离截止还有 30 秒"),
                new Case("BETTING_CLOSED", "{{eventType}}：{{eventMessage}}", "BETTING_CLOSED：本期停止下注"),
                new Case("DRAW_RESULT", "{{issueNumber}}期 {{resultNumbers}}，{{eventMessage}}", "1001期 1,2,3，开奖结果已发布")
        ).forEach(testCase -> {
            ChatRobotTemplate template = template(testCase.eventType(), testCase.templateText());
            RobotRenderContext context = context(testCase.eventType());

            assertThat(renderer.render(template, context)).isEqualTo(testCase.expected());
        });
    }

    @Test
    void preservesOrdinaryNewlinesAndNormalizesUnicode() {
        ChatRobotTemplate template = template("ISSUE_STARTED", "  {{issueNumber}}\n\te\u0301  ");

        assertThat(renderer.render(template, context("ISSUE_STARTED")))
                .isEqualTo("1001\n\té");
    }

    @Test
    void rejectsUnknownAndUnclosedVariables() {
        assertInvalid(template("ISSUE_STARTED", "{{unknown}}"));
        assertInvalid(template("ISSUE_STARTED", "{{issueNumber"));
        assertInvalid(template("ISSUE_STARTED", "issueNumber}}"));
        assertInvalid(template("ISSUE_STARTED", "{{ issueNumber }}"));
    }

    @Test
    void rejectsUnsafeTextAndLengthOverflow() {
        assertInvalid(template("ISSUE_STARTED", "<b>{{issueNumber}}</b>"));
        assertInvalid(template("ISSUE_STARTED", "javascript:alert(1)"));
        assertInvalid(template("ISSUE_STARTED", "data:text/html,hello"));
        assertInvalid(template("ISSUE_STARTED", "hello\u0000"));
        assertInvalid(template("ISSUE_STARTED", "x".repeat(1_001)));

        RobotRenderContext longMessage = new RobotRenderContext("1001", "ISSUE_STARTED",
                "x".repeat(2_001), "开奖助手", CREATED_AT, null);
        assertThatThrownBy(() -> renderer.render(template("ISSUE_STARTED", "{{eventMessage}}"), longMessage))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROBOT_TEMPLATE_INVALID");
    }

    @Test
    void onlyDrawResultMayUseResultNumbers() {
        assertInvalid(template("ISSUE_STARTED", "号码：{{resultNumbers}}"));
        assertThat(renderer.render(template("DRAW_RESULT", "号码：{{resultNumbers}}"), context("DRAW_RESULT")))
                .isEqualTo("号码：1,2,3");
    }

    @Test
    void rejectsDisabledOrMismatchedTemplates() {
        ChatRobotTemplate disabled = new ChatRobotTemplate(1L, 1L, "ISSUE_STARTED", "default",
                "{{issueNumber}}", false, 1, CREATED_AT, CREATED_AT);
        assertInvalid(disabled);

        assertInvalid(template("DRAW_RESULT", "{{issueNumber}}"), context("ISSUE_STARTED"));
    }

    private void assertInvalid(ChatRobotTemplate template) {
        assertInvalid(template, context(template.eventType()));
    }

    private void assertInvalid(ChatRobotTemplate template, RobotRenderContext context) {
        assertThatThrownBy(() -> renderer.render(template, context))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROBOT_TEMPLATE_INVALID");
    }

    private static ChatRobotTemplate template(String eventType, String text) {
        return new ChatRobotTemplate(1L, 1L, eventType, "default", text,
                true, 1, CREATED_AT, CREATED_AT);
    }

    private static RobotRenderContext context(String eventType) {
        return new RobotRenderContext("1001", eventType, eventMessage(eventType),
                "开奖助手", CREATED_AT, "1,2,3");
    }

    private static String eventMessage(String eventType) {
        return switch (eventType) {
            case "BETTING_WARNING" -> "距离截止还有 30 秒";
            case "BETTING_CLOSED" -> "本期停止下注";
            case "DRAW_RESULT" -> "开奖结果已发布";
            default -> "";
        };
    }

    private record Case(String eventType, String templateText, String expected) {
    }
}
