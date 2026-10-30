package com.xupan.server.robot.service;

import com.xupan.server.config.ScheduledTaskLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class RobotNoticeDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(RobotNoticeDispatchScheduler.class);
    private final RobotNoticeDispatchService service;
    private final ScheduledTaskLock taskLock;
    private final int batchSize;

    public RobotNoticeDispatchScheduler(RobotNoticeDispatchService service, ScheduledTaskLock taskLock,
                                        @Value("${xupan.robot.notice-batch-size:50}") int batchSize) {
        if (service == null || taskLock == null || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("机器人通知调度参数无效");
        }
        this.service = service;
        this.taskLock = taskLock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${xupan.robot.notice-dispatch-interval-ms:1000}")
    public void dispatch() {
        try {
            taskLock.runExclusively("xupan:robot-notice-dispatch",
                    () -> {
                        Instant now = Instant.now();
                        service.scanMachineCloseNotices(now, batchSize);
                        service.dispatchDue(now, batchSize);
                    });
        } catch (RuntimeException exception) {
            log.error("机器人通知派发扫描失败 batchSize={}", batchSize, exception);
        }
    }
}
