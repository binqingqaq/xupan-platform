package com.xupan.server.chat.web;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatSenderType;
import org.springframework.stereotype.Component;

@Component
public class ChatAvatarResolver {

    private final UserRepository userRepository;
    public ChatAvatarResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ChatMessageResponse toResponse(ChatMessage message) {
        String avatarKey = switch (message.senderType()) {
            case USER, ADMIN -> message.senderId() == null ? null
                    : userRepository.findById(message.senderId()).map(user -> user.avatarKey()).orElse(null);
            case ROBOT -> "robot-default.png";
            default -> null;
        };
        return ChatMessageResponse.from(message, avatarKey);
    }
}
