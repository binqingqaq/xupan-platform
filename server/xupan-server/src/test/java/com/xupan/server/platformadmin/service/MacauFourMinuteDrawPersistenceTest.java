package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MacauFourMinuteDrawPersistenceTest {

    @Test
    void bootstrapsPresetsDrawsAndCreatesNextPeriodWithoutPublishingPresets() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:macau-four-minute;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try (java.sql.Connection keepAlive = dataSource.getConnection()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            createSchema(jdbc);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.hasPermission(anyLong(), anyString())).thenReturn(true);
            MacauFourMinuteDrawService service = new MacauFourMinuteDrawService(jdbc, permissionService);
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));

            transaction.executeWithoutResult(status -> service.ensureInitialDraw());
            MacauFourMinuteDrawService.Page initial = service.list(1, 20, 1L);
            assertThat(initial.items()).hasSize(1);
            assertThat(initial.items().get(0).issueNumber()).isEqualTo("100");
            assertThat(initial.items().get(0).numbers()).containsOnlyNulls().hasSize(7);

            transaction.executeWithoutResult(status -> service.savePreset(
                    100L, Arrays.asList(8, null, 21, null, null, 40, null), 1L));
            MacauFourMinuteDrawService.DisplayResponse beforeDraw = service.getDisplay();
            assertThat(beforeDraw.latestDraw()).isNull();
            assertThat(beforeDraw.nextDraw().issueNumber()).isEqualTo("100");
            assertThat(Arrays.stream(MacauFourMinuteDrawService.NextDraw.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("numbers");
            transaction.executeWithoutResult(status -> service.savePreset(100L,
                    Arrays.asList(null, null, null, null, null, null, null), 1L));
            assertThat(service.list(1, 20, 1L).items().get(0).numbers()).containsOnlyNulls().hasSize(7);
            transaction.executeWithoutResult(status -> service.savePreset(
                    100L, Arrays.asList(8, null, 21, null, null, 40, null), 1L));
            Instant yesterday = Instant.now().minusSeconds(86_400)
                    .atZone(ZoneId.of("Asia/Shanghai")).withHour(20).withMinute(30)
                    .withSecond(0).withNano(0).toInstant();
            jdbc.update("UPDATE macau_four_minute_draw SET scheduled_at = ? WHERE issue_number = 100",
                    Timestamp.from(yesterday));

            Boolean firstDraw = transaction.execute(status -> service.processOneDueDraw());
            Boolean secondDraw = transaction.execute(status -> service.processOneDueDraw());
            assertThat(Boolean.TRUE.equals(firstDraw)).isTrue();
            assertThat(Boolean.TRUE.equals(secondDraw)).isFalse();

            MacauFourMinuteDrawService.Page page = service.list(1, 20, 1L);
            assertThat(page.items()).hasSize(2);
            assertThat(page.items().get(0).issueNumber()).isEqualTo("101");
            assertThat(page.items().get(0).status()).isEqualTo("PENDING");
            assertThat(page.items().get(0).numbers()).containsOnlyNulls().hasSize(7);
            assertThat(page.items().get(1).issueNumber()).isEqualTo("100");
            assertThat(page.items().get(1).numbers()).hasSize(7).doesNotContainNull();
            assertThat(page.items().get(1).numbers().get(0)).isEqualTo(8);
            assertThat(page.items().get(1).numbers().get(2)).isEqualTo(21);
            assertThat(page.items().get(1).numbers().get(5)).isEqualTo(40);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Integer.class)).isEqualTo(3);

            MacauFourMinuteDrawService.DisplayResponse display = service.getDisplay();
            assertThat(display.latestDraw()).isNotNull();
            assertThat(display.latestDraw().issueNumber()).isEqualTo("100");
            assertThat(display.latestDraw().numbers()).hasSize(7).doesNotContainNull();
            assertThat(display.nextDraw().issueNumber()).isEqualTo("101");
            assertThat(display.nextDraw().status()).isEqualTo("PENDING");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not connect to the isolated H2 test database", exception);
        }
    }

    @Test
    void concurrentSchedulerTicksDrawOneResultAndCreateOneNextPeriod() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:macau-four-minute-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try (java.sql.Connection keepAlive = dataSource.getConnection()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            createSchema(jdbc);
            PermissionService permissionService = mock(PermissionService.class);
            MacauFourMinuteDrawService service = new MacauFourMinuteDrawService(jdbc, permissionService);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.executeWithoutResult(status -> service.ensureInitialDraw());
            Instant yesterday = Instant.now().minusSeconds(86_400)
                    .atZone(ZoneId.of("Asia/Shanghai")).withHour(20).withMinute(30)
                    .withSecond(0).withNano(0).toInstant();
            jdbc.update("UPDATE macau_four_minute_draw SET scheduled_at = ? WHERE issue_number = 100",
                    Timestamp.from(yesterday));

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> first = executor.submit(() -> {
                    start.await();
                    return Boolean.TRUE.equals(transaction.execute(status -> service.processOneDueDraw()));
                });
                Future<Boolean> second = executor.submit(() -> {
                    start.await();
                    return Boolean.TRUE.equals(transaction.execute(status -> service.processOneDueDraw()));
                });
                start.countDown();
                assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            } finally {
                executor.shutdownNow();
            }

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM macau_four_minute_draw WHERE status = 'DRAWN'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM macau_four_minute_draw WHERE status = 'PENDING'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM macau_four_minute_draw WHERE issue_number = 101", Integer.class)).isEqualTo(1);
        }
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE macau_four_minute_draw (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    issue_number BIGINT NOT NULL UNIQUE,
                    scheduled_at TIMESTAMP(6) NOT NULL UNIQUE,
                    drawn_at TIMESTAMP(6) NULL,
                    status VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                )
                """);
        jdbc.execute("""
                CREATE TABLE macau_four_minute_draw_ball (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    draw_id BIGINT NOT NULL,
                    ball_position TINYINT NOT NULL,
                    number_value TINYINT NULL,
                    UNIQUE (draw_id, ball_position),
                    UNIQUE (draw_id, number_value),
                    FOREIGN KEY (draw_id) REFERENCES macau_four_minute_draw (id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE sys_operation_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    operator_user_id BIGINT NOT NULL,
                    permission_code VARCHAR(100) NOT NULL,
                    http_method VARCHAR(8) NOT NULL,
                    request_path VARCHAR(255) NOT NULL,
                    resource_id VARCHAR(255) NOT NULL,
                    result VARCHAR(16) NOT NULL,
                    error_code VARCHAR(100) NULL,
                    request_summary VARCHAR(1000) NOT NULL,
                    ip_digest VARCHAR(255) NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """);
    }
}
