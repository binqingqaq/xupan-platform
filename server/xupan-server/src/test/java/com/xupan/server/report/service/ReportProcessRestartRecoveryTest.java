package com.xupan.server.report.service;

import com.xupan.server.XupanServerApplication;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.center.service.CenterOrderService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies persisted pending-report recovery across a forced JVM termination on an isolated MySQL schema. */
class ReportProcessRestartRecoveryTest {

    private static final String PASSWORD = "P4RestartPass123";

    @TempDir
    Path tempDir;

    @Test
    void pendingCenterOrderSurvivesProcessKillAndRecoversAfterRestart() throws Exception {
        String databaseUrl = setting("p4.test.db.url", "P4_TEST_DB_URL");
        String databaseUsername = setting("p4.test.db.username", "P4_TEST_DB_USERNAME");
        boolean localTemporarySchema = databaseUrl != null
                && databaseUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/xupan_p4_recovery_[a-z0-9_]+(?:\\?.*)?");
        Assumptions.assumeTrue(localTemporarySchema,
                "requires a dedicated temporary MySQL schema on localhost:3307; urlPresent="
                        + (databaseUrl != null) + ", urlMatches=" + localTemporarySchema);
        Assumptions.assumeTrue(databaseUsername != null
                        && System.getenv("P4_TEST_DB_PASSWORD") != null,
                "requires explicit temporary-schema database credentials");

        Path seededMarker = tempDir.resolve("seeded.marker");
        Path seedLog = tempDir.resolve("seed-process.log");
        Path recoveryLog = tempDir.resolve("recovery-process.log");

        Process seedProcess = startWorker("seed", databaseUrl, databaseUsername, seededMarker, seedLog);
        try {
            waitForMarker(seedProcess, seededMarker, seedLog);
            seedProcess.destroyForcibly();
            assertThat(seedProcess.waitFor(15, TimeUnit.SECONDS))
                    .as("seed worker must be terminated before restart")
                    .isTrue();
        } finally {
            if (seedProcess.isAlive()) {
                seedProcess.destroyForcibly();
                seedProcess.waitFor(15, TimeUnit.SECONDS);
            }
        }

        Process recoveryProcess = startWorker("recover", databaseUrl, databaseUsername, null, recoveryLog);
        try {
            assertThat(recoveryProcess.waitFor(120, TimeUnit.SECONDS))
                    .as("recovery worker output: %s", readLog(recoveryLog))
                    .isTrue();
            assertThat(recoveryProcess.exitValue())
                    .as("recovery worker output: %s", readLog(recoveryLog))
                    .isZero();
            assertThat(readLog(recoveryLog)).contains("P4_RECOVERY_VERIFIED");
        } finally {
            if (recoveryProcess.isAlive()) {
                recoveryProcess.destroyForcibly();
                recoveryProcess.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    private Process startWorker(String mode, String databaseUrl, String databaseUsername,
                                Path marker, Path output) throws IOException {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        ProcessBuilder builder = new ProcessBuilder(javaExecutable,
                "-Dp4.test.db.url=" + databaseUrl,
                "-Dp4.test.db.username=" + databaseUsername,
                "-cp", classPath,
                Worker.class.getName(), mode, marker == null ? "-" : marker.toAbsolutePath().toString());
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        return builder.start();
    }

    private static void waitForMarker(Process process, Path marker, Path log) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(deadline) && process.isAlive() && !Files.exists(marker)) {
            Thread.sleep(100);
        }
        assertThat(Files.exists(marker))
                .as("seed worker output: %s", readLog(log))
                .isTrue();
    }

    private static String readLog(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "<no worker log>";
        } catch (IOException exception) {
            return "<cannot read worker log: " + exception.getMessage() + ">";
        }
    }

    private static String setting(String property, String environmentVariable) {
        String value = System.getProperty(property);
        return value == null ? System.getenv(environmentVariable) : value;
    }

    public static final class Worker {

        private Worker() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length != 2) {
                throw new IllegalArgumentException("mode and marker path are required");
            }
            String mode = args[0];
            Path marker = "-".equals(args[1]) ? null : Path.of(args[1]);
            String databaseUrl = setting("p4.test.db.url", "P4_TEST_DB_URL");
            String databaseUsername = setting("p4.test.db.username", "P4_TEST_DB_USERNAME");
            String databasePassword = System.getenv("P4_TEST_DB_PASSWORD");
            if (databaseUrl == null || databaseUsername == null || databasePassword == null) {
                throw new IllegalStateException("isolated MySQL process-test credentials are required");
            }

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(XupanServerApplication.class)
                    .profiles("test")
                    .web(WebApplicationType.SERVLET)
                    .run("--spring.datasource.url=" + databaseUrl,
                            "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                            "--spring.datasource.username=" + databaseUsername,
                            "--spring.datasource.password=" + databasePassword,
                            "--server.port=0",
                            "--server.address=127.0.0.1",
                            "--spring.task.scheduling.enabled=false",
                            "--xupan.automation.enabled=false",
                            "--xupan.report.dispatch-interval-ms=3600000")) {
                if ("seed".equals(mode)) {
                    seedPendingCenterOrder(context);
                    Files.writeString(marker, "seed committed");
                    Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                    throw new IllegalStateException("seed worker was expected to be forcefully terminated");
                }
                if ("recover".equals(mode)) {
                    recoverPendingCenterOrder(context);
                    System.out.println("P4_RECOVERY_VERIFIED");
                    return;
                }
                throw new IllegalArgumentException("unknown worker mode: " + mode);
            }
        }

        private static void seedPendingCenterOrder(ConfigurableApplicationContext context) {
            UserRepository users = context.getBean(UserRepository.class);
            PasswordPolicyService passwords = context.getBean(PasswordPolicyService.class);
            AgentAdminService agents = context.getBean(AgentAdminService.class);
            CenterUpstreamAccountService centerAccounts = context.getBean(CenterUpstreamAccountService.class);
            VirtualWalletService wallets = context.getBean(VirtualWalletService.class);
            ReportService reports = context.getBean(ReportService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            long adminId = users.insert("p4-restart-admin", "P4 restart admin",
                    passwords.encode(PASSWORD), "ACTIVE");
            users.assignRole(adminId, "ADMIN");
            long groupId = agents.createGroup("P4_RESTART_GROUP", "P4 restart group", adminId).id();
            var machine = agents.createAgent("P4_RESTART_MACHINE", "p4-restart-machine",
                    "P4 restart machine", PASSWORD, groupId, adminId);
            long centerId = centerAccounts.createAccount(groupId, "P4_RESTART_CENTER", PASSWORD,
                    new BigDecimal("5000.00"), null, adminId).account().accountId();
            jdbc.update("UPDATE agent SET report_auto = TRUE, report_withdraw = TRUE WHERE id = ?", machine.id());
            reports.login(machine.accountUserId(), "P4_RESTART_CENTER", PASSWORD, "127.0.0.1");

            long playerId = users.insert("p4-restart-player", "P4 restart player",
                    passwords.encode(PASSWORD), "ACTIVE");
            users.assignRole(playerId, "USER");
            long walletId = wallets.ensureWalletForUser(playerId, "P4 restart player");
            jdbc.update("UPDATE demo_user_account SET agent_id = ?, balance = 100.00 WHERE id = ?",
                    machine.id(), walletId);
            jdbc.update("INSERT INTO game_issue (game_code, issue_number, status, phase) "
                    + "VALUES ('AU8', 'P4RESTART001', 'OPEN', 'BETTING')");
            jdbc.update("""
                    INSERT INTO game_bet (game_code, user_id, bet_code, request_idempotency_key, issue_number,
                                          ball_number, play_type, parameters_text, stake, odds_snapshot,
                                          settlement_status)
                    VALUES ('AU8', ?, 'P4-RESTART-BET', 'P4-RESTART-BET', 'P4RESTART001', 1,
                            'FAN', '1', 100.00, 3.850, 'PENDING')
                    """, walletId);
            long betId = jdbc.queryForObject(
                    "SELECT id FROM game_bet WHERE bet_code = 'P4-RESTART-BET'", Long.class);

            jdbc.update("""
                    INSERT INTO center_order (center_agent_id, agent_order_no, local_bet_id, local_agent_id,
                                              game_code, issue_number, ball_number, play_type, play_text, odds,
                                              amount, status, frozen_amount)
                    VALUES (?, ?, ?, ?, 'AU8', 'P4RESTART001', 1, 'FAN', 'params=1', 3.8500,
                            100.00, 'PENDING', 0.00)
                    """, centerId, "BET-" + betId, betId, machine.id());
            jdbc.update("""
                    INSERT INTO report_upstream_order (bet_id, agent_id, center_agent_id, attempt_no, status,
                                                       report_type, idempotency_key, response_code, response_message,
                                                       sent_at, next_retry_at)
                    VALUES (?, ?, ?, 1, 'PENDING', 'CENTER', ?, 'PENDING', 'process interruption fixture',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, betId, machine.id(), centerId, "REPORT-" + betId + "-1");
        }

        private static void recoverPendingCenterOrder(ConfigurableApplicationContext context)
                throws InterruptedException {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            CenterOrderService centerOrders = context.getBean(CenterOrderService.class);
            ReportService reports = context.getBean(ReportService.class);

            long centerOrderId = jdbc.queryForObject(
                    "SELECT id FROM center_order WHERE local_bet_id = "
                            + "(SELECT id FROM game_bet WHERE bet_code = 'P4-RESTART-BET')", Long.class);
            long betId = jdbc.queryForObject(
                    "SELECT id FROM game_bet WHERE bet_code = 'P4-RESTART-BET'", Long.class);
            long centerId = jdbc.queryForObject(
                    "SELECT id FROM center_agent WHERE agent_code = 'P4_RESTART_CENTER'", Long.class);
            long machineId = jdbc.queryForObject(
                    "SELECT id FROM agent WHERE agent_code = 'P4_RESTART_MACHINE'", Long.class);

            waitForScheduledRecovery(jdbc, betId);
            assertThat(jdbc.queryForObject("SELECT status FROM report_upstream_order WHERE bet_id = ?",
                    String.class, betId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?",
                    Long.class, betId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT status FROM center_order WHERE id = ?",
                    String.class, centerOrderId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("""
                    SELECT u.balance FROM demo_user_account u JOIN game_bet b ON b.user_id = u.id WHERE b.id = ?
                    """, BigDecimal.class, betId)).isEqualByComparingTo("100.00");
            assertThat(jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                    BigDecimal.class, centerId)).isEqualByComparingTo("5000.00");
            assertThat(jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                    BigDecimal.class, centerId)).isEqualByComparingTo("0.00");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?",
                    Long.class, centerId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ?",
                    Long.class, machineId)).isZero();

            centerOrders.dispose(centerOrderId, "CONFIRM_ACCEPTED", "restart recovery verification",
                    "P4-RESTART-CONFIRM-" + betId, jdbc.queryForObject(
                            "SELECT id FROM sys_user WHERE username = 'p4-restart-admin'", Long.class));
            BigDecimal playerBalanceBeforeRecovery = jdbc.queryForObject(
                    "SELECT balance FROM demo_user_account WHERE id = (SELECT user_id FROM game_bet WHERE id = ?)",
                    BigDecimal.class, betId);
            BigDecimal centerAvailableAfterConfirmation = jdbc.queryForObject(
                    "SELECT available_balance FROM center_agent WHERE id = ?", BigDecimal.class, centerId);
            BigDecimal centerFrozenAfterConfirmation = jdbc.queryForObject(
                    "SELECT frozen_balance FROM center_agent WHERE id = ?", BigDecimal.class, centerId);
            BigDecimal machineScoreAfterConfirmation = jdbc.queryForObject(
                    "SELECT score FROM agent WHERE id = ?", BigDecimal.class, machineId);
            long centerLedgerAfterConfirmation = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?", Long.class, centerId);
            long machineLedgerAfterConfirmation = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ?", Long.class, machineId);

            jdbc.update("UPDATE report_upstream_order SET next_retry_at = CURRENT_TIMESTAMP WHERE bet_id = ?", betId);
            ReportService.DispatchResult result = reports.dispatchPending(true, 50);

            assertThat(result.scanned()).isEqualTo(1);
            assertThat(result.submitted()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM report_upstream_order WHERE bet_id = ?",
                    String.class, betId)).isEqualTo("ACCEPTED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_upstream_order WHERE bet_id = ?",
                    Long.class, betId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_order WHERE center_agent_id = ?",
                    Long.class, centerId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT status FROM center_order WHERE id = ?",
                    String.class, centerOrderId)).isEqualTo("ACCEPTED");
            assertThat(jdbc.queryForObject(
                    "SELECT balance FROM demo_user_account WHERE id = (SELECT user_id FROM game_bet WHERE id = ?)",
                    BigDecimal.class, betId)).isEqualByComparingTo(playerBalanceBeforeRecovery);
            assertThat(jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                    BigDecimal.class, centerId)).isEqualByComparingTo(centerAvailableAfterConfirmation);
            assertThat(jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                    BigDecimal.class, centerId)).isEqualByComparingTo(centerFrozenAfterConfirmation);
            assertThat(jdbc.queryForObject("SELECT score FROM agent WHERE id = ?",
                    BigDecimal.class, machineId)).isEqualByComparingTo(machineScoreAfterConfirmation);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent_ledger WHERE center_agent_id = ?",
                    Long.class, centerId)).isEqualTo(centerLedgerAfterConfirmation);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_ledger WHERE agent_id = ?",
                    Long.class, machineId)).isEqualTo(machineLedgerAfterConfirmation);
        }

        private static void waitForScheduledRecovery(JdbcTemplate jdbc, long betId) throws InterruptedException {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            Integer attemptCount = 0;
            while (attemptCount == 0 && Instant.now().isBefore(deadline)) {
                attemptCount = jdbc.queryForObject(
                        "SELECT attempt_count FROM report_upstream_order WHERE bet_id = ?", Integer.class, betId);
                if (attemptCount == 0) {
                    Thread.sleep(100);
                }
            }
            assertThat(attemptCount).as("the restarted process scheduler should replay the persisted attempt")
                    .isGreaterThan(0);
        }
    }
}
