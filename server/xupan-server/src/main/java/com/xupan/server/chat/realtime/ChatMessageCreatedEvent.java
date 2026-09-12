package com.xupan.server.chat.realtime;

import com.xupan.server.chat.domain.ChatMessage;

import java.util.Objects;

public record ChatMessageCreatedEvent(ChatMessage message) {

    public ChatMessageCreatedEvent {
        Objects.requireNonNull(message, "message");
    }
}
