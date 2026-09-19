package com.xupan.server.system.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class TestPlayerBehaviorScheduler {
    private static final Logger log = LoggerFactory.getLogger(TestPlayerBehaviorScheduler.class);
    private final JdbcTemplate jdbc;
    private final TestPlayerBehaviorService service;
    private final boolean enabled;

    public TestPlayerBehaviorScheduler(JdbcTemplate jdbc, TestPlayerBehaviorService service,
                                       @Value("${xupan.test-player.behavior-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.service = service;
        this.enabled = enabled;
        log.info("Test-player behavior scheduler initialized: enabled={}", enabled);
    }

    @Scheduled(fixedDelayString = "${xupan.test-player.dispatch-interval-ms:1000}")
    public void dispatch() {
        if (!enabled) return;
        jdbc.query("SELECT DISTINCT u.id FROM sys_user u JOIN demo_user_account a ON a.sys_user_id=u.id "
                        + "JOIN test_player_behavior b ON b.account_id=a.id WHERE a.player_kind='BOT' AND u.status='ACTIVE' AND a.status='ACTIVE'",
                (rs, rowNum) -> rs.getLong(1)).forEach(service::dispatch);
    }
}
