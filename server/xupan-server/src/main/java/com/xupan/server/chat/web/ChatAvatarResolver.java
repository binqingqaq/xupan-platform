package com.xupan.server.chat.web;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatSenderType;
import com.xupan.server.robot.repository.RobotRepository;
import org.springframework.stereotype.Component;

@Component
public class ChatAvatarResolver {

    private final UserRepository userRepository;
    private final RobotRepository robotRepository;

    public ChatAvatarResolver(UserRepository userRepository, RobotRepository robotRepository) {
        this.userRepository = userRepository;
        this.robotRepository = robotRepository;
    }

    public ChatMessageResponse toResponse(ChatMessage message) {
        String avatarKey = switch (message.senderType()) {
            case USER, ADMIN -> message.senderId() == null ? null
                    : userRepository.findById(message.senderId()).map(user -> user.avatarKey()).orElse(null);
            case ROBOT -> message.senderId() == null ? null
                    : robotRepository.findById(message.senderId()).map(robot -> robot.avatarKey()).orElse(null);
            default -> null;
        };
        return ChatMessageResponse.from(message, avatarKey);
    }
}
