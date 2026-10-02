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
    PLATFORM_HOME_READ,
    AGENT_MANAGE,
    SUB_ACCOUNT_MANAGE,
    MACHINE_MANAGE,
    REPORT_READ,
    DRAW_HISTORY_READ,
    DRAW_HISTORY_FORCE_SETTLE,
    DRAW_HISTORY_SUPPLEMENT,
    UNSETTLED_ORDER_READ,
    UNSETTLED_ORDER_DELETE,
    ORDER_CORRECTION_READ,
    ORDER_CORRECTION_MANAGE,
    ONLINE_PLAYER_READ,
    ONLINE_PLAYER_DISCONNECT,
    ONLINE_PLAYER_MESSAGE,
    PLATFORM_SETTINGS_READ,
    PLATFORM_SETTINGS_WRITE,
    REPORT_NETWORK_READ,
    REPORT_NETWORK_WRITE,
    GAME_SETTINGS_READ,
    GAME_SETTINGS_WRITE,
    PLATFORM_PASSWORD_MANAGE,
    AGENT_CONSOLE_READ,
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
