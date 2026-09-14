package com.xupan.server.robot.service;

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
        byte[] digest = sha256(("robot|" + gameEventId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
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
