package com.xupan.server.robot.service;

import com.xupan.server.robot.domain.RobotDrawComponent;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates the stable database idempotency key for one source game event. */
@Component
public final class RobotMessageIdempotency {

    public String idempotencyKey(long gameEventId) {
        if (gameEventId <= 0) {
            throw BusinessException.badRequest("ROBOT_GAME_EVENT_ID_INVALID", "游戏事件标识必须为正数");
        }
        return hash("robot|" + gameEventId);
    }

    public String idempotencyKey(long gameEventId, RobotDrawComponent component) {
        if (gameEventId <= 0) {
            throw BusinessException.badRequest("ROBOT_GAME_EVENT_ID_INVALID", "游戏事件标识必须为正数");
        }
        if (component == null) {
            throw BusinessException.badRequest("ROBOT_DRAW_COMPONENT_INVALID", "开奖消息组件不能为空");
        }
        return hash("robot|" + gameEventId + "|" + component.name());
    }

    private static String hash(String value) {
        byte[] digest = sha256(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte part : digest) {
            hex.append(Character.forDigit((part >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(part & 0x0f, 16));
        }
        return hex.toString();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 必须支持 SHA-256", exception);
        }
    }
}
