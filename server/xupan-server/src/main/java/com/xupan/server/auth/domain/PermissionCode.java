package com.xupan.server.auth.domain;

import java.util.Arrays;

public enum PermissionCode {
    CHAT_ROOM_READ,
    CHAT_MESSAGE_SEND,
    GAME_CURRENT_READ,
    GAME_BET_PLACE,
    CHAT_MESSAGE_REVIEW,
    CHAT_MESSAGE_RECALL,
    CHAT_USER_MUTE,
    CHAT_USER_KICK,
    GAME_ODDS_READ,
    GAME_ODDS_WRITE,
    ROBOT_READ,
    ROBOT_WRITE,
    ROBOT_TEMPLATE_WRITE,
    USER_MANAGE,
    ROLE_MANAGE,
    PERMISSION_MANAGE,
    AUDIT_READ;

    public String code() {
        return name();
    }

    public String authority() {
        return "PERM_" + name();
    }

    public static PermissionCode fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知权限编码: " + code));
    }
}
