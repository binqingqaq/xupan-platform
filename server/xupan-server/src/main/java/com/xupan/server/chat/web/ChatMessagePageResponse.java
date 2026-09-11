package com.xupan.server.chat.web;

import com.xupan.server.chat.domain.ChatMessagePage;

import java.util.List;

public record ChatMessagePageResponse(
        String roomCode,
        List<ChatMessageResponse> items,
        Long nextBeforeSequence,
        Long nextAfterSequence,
        boolean hasMore
) {

    public static ChatMessagePageResponse from(String roomCode, ChatMessagePage page) {
        return new ChatMessagePageResponse(roomCode,
                page.items().stream().map(ChatMessageResponse::from).toList(),
                page.nextBeforeSequence(), page.nextAfterSequence(), page.hasMore());
    }
}
