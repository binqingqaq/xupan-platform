package com.xupan.server.chat.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChatRobotUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(ChatRobotUpdatedListener.class);

    private final ChatRealtimeBroadcaster broadcaster;

    public ChatRobotUpdatedListener(ChatRealtimeBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRobotUpdated(ChatRobotUpdatedEvent event) {
        try {
            broadcaster.broadcastRobotUpdated(event.roomCode(), event.robotId(), event.displayName());
        } catch (RuntimeException exception) {
            log.error("机器人名称更新提交后广播失败 robotId={} roomCode={}",
                    event.robotId(), event.roomCode(), exception);
        }
    }
}
