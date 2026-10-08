package com.xupan.server.chat.realtime;

import com.xupan.server.config.ScheduledTaskLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ChatHeartbeatScheduler {

    private final ChatConnectionRegistry connectionRegistry;
    private final ScheduledTaskLock taskLock;

    public ChatHeartbeatScheduler(ChatConnectionRegistry connectionRegistry, ScheduledTaskLock taskLock) {
        this.connectionRegistry = connectionRegistry;
        this.taskLock = taskLock;
    }

    /**
     * 心跳本身是幂等的（重复清理过期连接无副作用），但多进程同时遍历注册表没有必要，
     * 统一走互斥以保持五个调度器行为一致、便于排查。
     */
    @Scheduled(fixedDelayString = "${xupan.chat.websocket.heartbeat-interval:20s}")
    public void heartbeat() {
        taskLock.runExclusively("xupan:chat-heartbeat", () -> connectionRegistry.heartbeat(Instant.now()));
    }
}
