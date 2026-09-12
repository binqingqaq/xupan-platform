package com.xupan.server.chat.realtime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ChatHeartbeatScheduler {

    private final ChatConnectionRegistry connectionRegistry;

    public ChatHeartbeatScheduler(ChatConnectionRegistry connectionRegistry) {
        this.connectionRegistry = connectionRegistry;
    }

    @Scheduled(fixedDelayString = "${xupan.chat.websocket.heartbeat-interval:20s}")
    public void heartbeat() {
        connectionRegistry.heartbeat(Instant.now());
    }
}
