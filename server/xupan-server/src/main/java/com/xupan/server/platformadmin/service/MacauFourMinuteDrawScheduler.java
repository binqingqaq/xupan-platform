package com.xupan.server.platformadmin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class MacauFourMinuteDrawScheduler {

    private static final Logger log = LoggerFactory.getLogger(MacauFourMinuteDrawScheduler.class);
    private final MacauFourMinuteDrawService service;
    private final AtomicLong lastFailureLogAt = new AtomicLong();

    public MacauFourMinuteDrawScheduler(MacauFourMinuteDrawService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        try {
            service.ensureInitialDraw();
            service.processOneDueDraw();
        } catch (RuntimeException exception) {
            long now = System.currentTimeMillis();
            long previous = lastFailureLogAt.get();
            if (now - previous >= 30_000 && lastFailureLogAt.compareAndSet(previous, now)) {
                log.error("澳门四分彩开奖调度失败，将在后续周期重试", exception);
            }
        }
    }
}
