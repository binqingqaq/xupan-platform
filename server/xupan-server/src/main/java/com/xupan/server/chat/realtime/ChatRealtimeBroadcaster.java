package com.xupan.server.chat.realtime;

import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.web.ChatMessageResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Broadcasts only server-persisted message projections to connections in the same room. */
@Component
public class ChatRealtimeBroadcaster {

    private final ChatConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;

    public ChatRealtimeBroadcaster(ChatConnectionRegistry connectionRegistry, ObjectMapper objectMapper) {
        this.connectionRegistry = connectionRegistry;
        this.objectMapper = objectMapper;
    }

    public int broadcast(ChatMessage message) {
        ChatMessageResponse response = ChatMessageResponse.from(message);
        String payload = ChatProtocol.encode(objectMapper, ChatProtocol.messageCreated(response));
        return connectionRegistry.broadcast(message.roomCode(), payload);
    }
}
