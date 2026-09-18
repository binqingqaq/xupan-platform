package com.xupan.server.chat.realtime;

import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.web.ChatMessageResponse;
import com.xupan.server.chat.web.ChatAvatarResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Broadcasts only server-persisted message projections to connections in the same room. */
@Component
public class ChatRealtimeBroadcaster {

    private final ChatConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;
    private final ChatAvatarResolver avatarResolver;
    private final ChatWebSocketProperties properties;

    public ChatRealtimeBroadcaster(ChatConnectionRegistry connectionRegistry, ObjectMapper objectMapper,
                                   ChatAvatarResolver avatarResolver, ChatWebSocketProperties properties) {
        this.connectionRegistry = connectionRegistry;
        this.objectMapper = objectMapper;
        this.avatarResolver = avatarResolver;
        this.properties = properties;
    }

    public int broadcast(ChatMessage message) {
        ChatMessageResponse response = avatarResolver.toResponse(message);
        String payload = ChatProtocol.encode(objectMapper, ChatProtocol.messageCreated(response),
                properties.getMaxOutboundMessageBytes());
        return connectionRegistry.broadcast(message.roomCode(), payload);
    }

    public int broadcastRobotUpdated(String roomCode, long robotId, String displayName) {
        String payload = ChatProtocol.encode(objectMapper,
                ChatProtocol.robotUpdated(robotId, displayName), properties.getMaxOutboundMessageBytes());
        return connectionRegistry.broadcast(roomCode, payload);
    }
}
