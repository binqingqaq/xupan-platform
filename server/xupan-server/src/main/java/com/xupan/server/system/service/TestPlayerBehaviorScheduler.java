package com.xupan.server.system.service;

import com.xupan.server.config.ScheduledTaskLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class TestPlayerBehaviorScheduler {
    private static final Logger log = LoggerFactory.getLogger(TestPlayerBehaviorScheduler.class);
    private final JdbcTemplate jdbc;
    private final TestPlayerBehaviorService service;
    private final ScheduledTaskLock taskLock;
    private final boolean enabled;

    public TestPlayerBehaviorScheduler(JdbcTemplate jdbc, TestPlayerBehaviorService service,
                                       ScheduledTaskLock taskLock,
                                       @Value("${xupan.test-player.behavior-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.service = service;
        this.taskLock = taskLock;
        this.enabled = enabled;
        log.info("Test-player behavior scheduler initialized: enabled={}", enabled);
    }

    /** 1 秒一次，多进程并存时必须互斥，否则托动作会被重复派发（重复下注）。 */
    @Scheduled(fixedDelayString = "${xupan.test-player.dispatch-interval-ms:1000}")
    public void dispatch() {
        if (!enabled) return;
        try {
            taskLock.runExclusively("xupan:test-player-behavior", this::dispatchAll);
        } catch (RuntimeException exception) {
            log.error("托行为派发失败 operation=dispatch", exception);
        }
    }

    /** BY220 runs a separate weighted bot activity selector about every 15 seconds and samples up to six bots. */
    @Scheduled(fixedDelayString = "${xupan.test-player.activity-interval-ms:15000}")
    public void dispatchBotActivity() {
        if (!enabled) return;
        try {
            taskLock.runExclusively("xupan:test-player-behavior", this::dispatchActivityBatch);
        } catch (RuntimeException exception) {
            log.error("托自动交互派发失败 operation=activity", exception);
        }
    }

    private void dispatchAll() {
        jdbc.query("SELECT DISTINCT u.id FROM sys_user u JOIN demo_user_account a ON a.sys_user_id=u.id "
                        + "JOIN test_player_behavior b ON b.account_id=a.id WHERE a.player_kind='BOT' AND u.status='ACTIVE' AND a.status='ACTIVE'",
                (rs, rowNum) -> rs.getLong(1)).forEach(service::dispatch);
    }

    private void dispatchActivityBatch() {
        List<Long> candidates = jdbc.queryForList("SELECT DISTINCT u.id FROM sys_user u "
                        + "JOIN demo_user_account a ON a.sys_user_id=u.id "
                        + "JOIN test_player_behavior b ON b.account_id=a.id "
                        + "WHERE a.player_kind='BOT' AND a.status='ACTIVE' AND u.status='ACTIVE' "
                        + "AND b.enabled=TRUE AND b.run_mode='AUTOMATIC'", Long.class);
        if (candidates.isEmpty()) return;
        Collections.shuffle(candidates);
        for (Long userId : new ArrayList<>(candidates.subList(0, Math.min(6, candidates.size())))) {
            service.dispatchPeriodicActivity(userId);
        }
    }
}
