package com.xupan.server.chat.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChatMessageCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageCreatedListener.class);

    private final ChatRealtimeBroadcaster broadcaster;

    public ChatMessageCreatedListener(ChatRealtimeBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(ChatMessageCreatedEvent event) {
        try {
            broadcaster.broadcast(event.message());
        } catch (RuntimeException exception) {
            log.error("聊天室消息提交后广播失败 stage=afterCommitBroadcast messageId={} roomCode={}",
                    event.message().id(), event.message().roomCode(), exception);
        }
    }
}
