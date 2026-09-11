package com.xupan.server.chat.web;

import com.xupan.server.chat.service.ChatMessageService;

import java.time.Instant;

public record ChatRoomResponse(
        String roomCode,
        String displayName,
        String status,
        int messageRetentionDays,
        long nextSequenceNo,
        String currentIssueNumber,
        Instant serverNow
) {

    public static ChatRoomResponse from(ChatMessageService.ChatRoomView view) {
        return new ChatRoomResponse(view.room().roomCode(), view.room().displayName(),
                view.room().status(), view.room().messageRetentionDays(), view.room().nextSequenceNo(),
                view.currentIssueNumber(), view.serverNow());
    }
}
