package com.xupan.server.chat.realtime;

import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Validates a WebSocket subscription and freezes the REST compensation cursor. */
@Component
public class ChatRealtimeSyncService {

    private final ChatMessageService chatMessageService;

    public ChatRealtimeSyncService(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    public SyncPlan prepare(long userId, String roomCode, Long requestedAfterSequence, Instant now) {
        long afterSequence = requestedAfterSequence == null ? 0L : requestedAfterSequence;
        if (afterSequence < 0) {
            throw BusinessException.badRequest("CHAT_CURSOR_INVALID", "消息同步游标无效");
        }
        ChatMessageService.ChatRoomView roomView = chatMessageService.getRoom(userId, roomCode, now);
        long latestSequence = roomView.room().nextSequenceNo();
        if (afterSequence > latestSequence) {
            throw BusinessException.badRequest("CHAT_CURSOR_INVALID", "消息同步游标超过房间当前序号");
        }
        return new SyncPlan(afterSequence, latestSequence);
    }

    public record SyncPlan(long afterSequence, long latestSequence) {
    }
}
