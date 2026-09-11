package com.xupan.server.chat.domain;

public record ChatRoom(
        long id,
        String roomCode,
        String displayName,
        String status,
        int messageRetentionDays,
        long nextSequenceNo
) {

    public boolean isOpen() {
        return "OPEN".equals(status);
    }
}
