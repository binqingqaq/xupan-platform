package com.xupan.server.chat.domain;

import java.util.List;

public record ChatMessagePage(
        List<ChatMessage> items,
        Long nextBeforeSequence,
        Long nextAfterSequence,
        boolean hasMore
) {

    public ChatMessagePage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
