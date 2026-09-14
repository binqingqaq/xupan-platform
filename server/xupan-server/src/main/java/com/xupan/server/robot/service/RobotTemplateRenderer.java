package com.xupan.server.robot.service;

import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the small, deliberately non-executable template language used by a chat robot.
 *
 * <p>The renderer consumes the shared robot domain model and keeps template parsing
 * independent from database access.</p>
 */
@Component
public final class RobotTemplateRenderer {

    private static final int MAX_TEMPLATE_CODE_POINTS = 1_000;
    private static final int MAX_RENDERED_CODE_POINTS = 2_000;
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}" );
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    private static final Pattern SCRIPT_URI = Pattern.compile(
            "(?i)(?:javascript|data|vbscript)\\s*:");
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "ISSUE_STARTED", "BETTING_WARNING", "BETTING_CLOSED", "DRAW_RESULT");
    private static final Set<String> COMMON_VARIABLES = Set.of(
            "issueNumber", "eventType", "eventMessage", "robotName", "createdAt");

    public String render(ChatRobotTemplate template, RobotRenderContext context) {
        if (template == null) {
            throw invalidTemplate("机器人模板不能为空");
        }
        if (context == null) {
            throw BusinessException.badRequest("ROBOT_RENDER_CONTEXT_INVALID", "机器人渲染上下文不能为空");
        }
        if (!template.enabled()) {
            throw invalidTemplate("机器人模板未启用");
        }
        requireSupportedEvent(template.eventType(), "机器人模板事件类型不支持");
        requireSupportedEvent(context.eventType(), "机器人渲染事件类型不支持");
        if (!template.eventType().equals(context.eventType())) {
            throw invalidTemplate("机器人模板事件类型与渲染事件不一致");
        }
        if (template.version() < 1 || isBlank(template.templateCode())) {
            throw invalidTemplate("机器人模板版本或编码无效");
        }
        requireContextText(context.issueNumber(), "期号");
        requireContextText(context.robotName(), "机器人名称");
        if (context.createdAt() == null) {
            throw BusinessException.badRequest("ROBOT_RENDER_CONTEXT_INVALID", "创建时间不能为空");
        }

        String templateText = normalizeAndValidate(template.templateText(),
                MAX_TEMPLATE_CODE_POINTS, "机器人模板不能为空或超过 1000 个字符");
        Matcher matcher = VARIABLE.matcher(templateText);
        StringBuilder rendered = new StringBuilder(templateText.length());
        int cursor = 0;
        while (matcher.find()) {
            rejectUnmatchedBraces(templateText.substring(cursor, matcher.start()));
            String variableName = matcher.group(1);
            if (!COMMON_VARIABLES.contains(variableName) && !"resultNumbers".equals(variableName)) {
                throw invalidTemplate("机器人模板包含未知变量: " + variableName);
            }
            if ("resultNumbers".equals(variableName) && !"DRAW_RESULT".equals(context.eventType())) {
                throw invalidTemplate("resultNumbers 只能用于 DRAW_RESULT 事件");
            }
            rendered.append(templateText, cursor, matcher.start());
            rendered.append(valueOf(variableName, context));
            cursor = matcher.end();
        }
        String tail = templateText.substring(cursor);
        rejectUnmatchedBraces(tail);
        rendered.append(tail);

        return normalizeAndValidate(rendered.toString(), MAX_RENDERED_CODE_POINTS,
                "机器人渲染结果不能为空或超过 2000 个字符");
    }

    private static String valueOf(String variableName, RobotRenderContext context) {
        return switch (variableName) {
            case "issueNumber" -> context.issueNumber();
            case "eventType" -> context.eventType();
            case "eventMessage" -> nullToEmpty(context.eventMessage());
            case "robotName" -> context.robotName();
            case "createdAt" -> context.createdAt().toString();
            case "resultNumbers" -> nullToEmpty(context.resultNumbers());
            default -> throw invalidTemplate("机器人模板包含未知变量: " + variableName);
        };
    }

    private static String normalizeAndValidate(String value, int maxCodePoints, String lengthMessage) {
        if (value == null) {
            throw invalidTemplate(lengthMessage);
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
            throw invalidTemplate(lengthMessage);
        }
        if (HTML_TAG.matcher(normalized).find()) {
            throw invalidTemplate("机器人文本不能包含 HTML 标签");
        }
        if (SCRIPT_URI.matcher(normalized).find()) {
            throw invalidTemplate("机器人文本不能包含脚本或数据协议");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (codePoint == 0 || (Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
                throw invalidTemplate("机器人文本包含不允许的控制字符");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    private static void rejectUnmatchedBraces(String value) {
        if (value.contains("{{") || value.contains("}}")) {
            throw invalidTemplate("机器人模板变量格式无效");
        }
    }

    private static void requireSupportedEvent(String eventType, String message) {
        if (eventType == null || !SUPPORTED_EVENTS.contains(eventType)) {
            throw invalidTemplate(message);
        }
    }

    private static void requireContextText(String value, String field) {
        if (isBlank(value)) {
            throw BusinessException.badRequest("ROBOT_RENDER_CONTEXT_INVALID", field + "不能为空");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static BusinessException invalidTemplate(String message) {
        return BusinessException.badRequest("ROBOT_TEMPLATE_INVALID", message);
    }
}
