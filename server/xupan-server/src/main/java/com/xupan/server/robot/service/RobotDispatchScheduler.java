package com.xupan.server.robot.service;

import com.xupan.server.config.ScheduledTaskLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public final class RobotDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(RobotDispatchScheduler.class);

    private final RobotDispatchService dispatchService;
    private final ScheduledTaskLock taskLock;
    private final int batchSize;
    private final Clock clock;

    public RobotDispatchScheduler(
            RobotDispatchService dispatchService,
            ScheduledTaskLock taskLock,
            @Value("${xupan.robot.dispatch-batch-size:50}") int batchSize) {
        if (dispatchService == null || taskLock == null || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("机器人调度器参数无效");
        }
        this.dispatchService = dispatchService;
        this.taskLock = taskLock;
        this.batchSize = batchSize;
        this.clock = Clock.systemUTC();
    }

    /** 1 秒一次，多进程并存时必须互斥，否则机器人事件会被重复派发。 */
    @Scheduled(fixedDelayString = "${xupan.robot.dispatch-interval-ms:1000}")
    public void scan() {
        try {
            taskLock.runExclusively("xupan:robot-dispatch",
                    () -> dispatchService.scanPendingEvents(clock.instant(), batchSize));
        } catch (RuntimeException exception) {
            log.error("机器人事件扫描失败 operation=scanPendingEvents batchSize={}", batchSize, exception);
        }
    }
}
