package com.xupan.server.chat.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.chat.service.ChatMessageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/chat/rooms/{roomCode}")
public class ChatController {

    private final ChatMessageService chatMessageService;
    private final ChatAvatarResolver avatarResolver;

    public ChatController(ChatMessageService chatMessageService, ChatAvatarResolver avatarResolver) {
        this.chatMessageService = chatMessageService;
        this.avatarResolver = avatarResolver;
    }

    @GetMapping
    public ChatRoomResponse room(Authentication authentication, @PathVariable String roomCode) {
        AuthenticatedUser user = principal(authentication);
        requireObserverRoom(user, roomCode);
        return ChatRoomResponse.from(chatMessageService.getRoom(user.getUserId(), roomCode, Instant.now()));
    }

    @GetMapping("/messages")
    public ChatMessagePageResponse messages(Authentication authentication,
                                            @PathVariable String roomCode,
                                            @RequestParam(required = false) Long beforeSequence,
                                            @RequestParam(required = false) Long afterSequence,
                                            @RequestParam(defaultValue = "50") int limit) {
        AuthenticatedUser user = principal(authentication);
        requireObserverRoom(user, roomCode);
        return ChatMessagePageResponse.from(roomCode, chatMessageService.history(user.getUserId(), roomCode,
                beforeSequence, afterSequence, limit, Instant.now()), avatarResolver);
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatMessageResponse send(Authentication authentication,
                                    @PathVariable String roomCode,
                                    @RequestBody SendChatMessageRequest request) {
        AuthenticatedUser user = principal(authentication);
        if (user.isDisplayObserver()) {
            throw com.xupan.server.web.BusinessException.forbidden(
                    "DISPLAY_OBSERVER_READ_ONLY", "只读观察入口不能发送聊天消息");
        }
        return avatarResolver.toResponse(chatMessageService.sendUserMessage(user.getUserId(), roomCode,
                request == null ? null : request.clientMessageId(),
                request == null ? null : request.content(),
                request == null ? "AU8" : request.normalizedGameCode(), Instant.now()));
    }

    @PostMapping("/read-cursor")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readCursor(Authentication authentication,
                           @PathVariable String roomCode,
                           @RequestBody ReadCursorRequest request) {
        AuthenticatedUser user = principal(authentication);
        if (user.isDisplayObserver()) {
            throw com.xupan.server.web.BusinessException.forbidden(
                    "DISPLAY_OBSERVER_READ_ONLY", "只读观察入口不能修改聊天已读状态");
        }
        chatMessageService.saveReadCursor(user.getUserId(), roomCode,
                request == null || request.lastReadSequence() == null ? -1 : request.lastReadSequence(),
                Instant.now());
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    private static void requireObserverRoom(AuthenticatedUser user, String roomCode) {
        if (user.isDisplayObserver() && !"main".equals(roomCode)) {
            throw com.xupan.server.web.BusinessException.forbidden(
                    "DISPLAY_OBSERVER_ROOM_FORBIDDEN", "观察入口只能查看绑定机器的公开大厅");
        }
    }
}
