package com.xupan.server.chat.service;

import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Component;

import java.text.Normalizer;

@Component
public class ChatContentPolicy {

    private static final int MAX_CODE_POINTS = 500;
    private static final java.util.regex.Pattern HTML_TAG =
            java.util.regex.Pattern.compile("<\\s*/?\\s*[A-Za-z][^>]*>");

    public String normalize(String value) {
        if (value == null) {
            throw BusinessException.badRequest("CHAT_MESSAGE_EMPTY", "消息内容不能为空");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.isEmpty()) {
            throw BusinessException.badRequest("CHAT_MESSAGE_EMPTY", "消息内容不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) {
            throw BusinessException.badRequest("CHAT_MESSAGE_TOO_LONG", "消息内容不能超过 500 个字符");
        }
        if (HTML_TAG.matcher(normalized).find()) {
            throw BusinessException.badRequest("CHAT_MESSAGE_HTML_FORBIDDEN", "消息不能包含 HTML 标签");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (codePoint == 0 || (Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
                throw BusinessException.badRequest("CHAT_MESSAGE_CONTROL_CHAR", "消息包含不允许的控制字符");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    public String requireClientMessageId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw BusinessException.badRequest("CHAT_CLIENT_MESSAGE_ID_INVALID", "客户端消息标识无效");
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw BusinessException.badRequest("CHAT_CLIENT_MESSAGE_ID_INVALID", "客户端消息标识无效");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    public int validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw BusinessException.badRequest("CHAT_MESSAGE_LIMIT_INVALID", "消息数量必须在 1 到 100 之间");
        }
        return limit;
    }
}
