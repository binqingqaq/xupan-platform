package com.xupan.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class LoggingConfigurationTest {

    private static final Path LOGBACK_CONFIG = Path.of(
            "src/main/resources/logback-spring.xml");
    private static final Path PRODUCTION_CONFIG = Path.of(
            "src/main/resources/application-prod.yaml");

    @Test
    void testProfileLoadsConsoleOnlyLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        assertTrue(rootLogger.getAppender("CONSOLE") != null,
                "test profile must keep console logging enabled");
        assertFalse(rootLogger.getAppender("ROLLING_FILE") != null,
                "test profile must not create a repository log file");
    }

    @Test
    void logbackConfigDefinesRequestIdAndSensitiveValueMasking() throws IOException {
        String config = Files.readString(LOGBACK_CONFIG, StandardCharsets.UTF_8);

        assertTrue(config.contains("requestId=%X{requestId:-}"));
        assertTrue(config.contains("password|passwd|access_token|refresh_token|authorization|cookie|ticket"));
        assertTrue(config.contains("<springProfile name=\"prod\">")
                && config.contains("<springProfile name=\"!prod\">")
                && config.contains("<appender name=\"ROLLING_FILE\""));
        assertFalse(config.contains("%requestHeader") || config.contains("%mdc{Authorization}"));
    }

    @Test
    void productionConfigDefinesBoundedRollingRetention() throws IOException {
        String config = Files.readString(PRODUCTION_CONFIG, StandardCharsets.UTF_8);

        assertTrue(config.contains("XUPAN_LOG_FILE:/opt/xupan-platform/logs/xupan-server.log"));
        assertTrue(config.contains("XUPAN_LOG_MAX_FILE_SIZE:50MB"));
        assertTrue(config.contains("XUPAN_LOG_MAX_HISTORY_DAYS:30"));
        assertTrue(config.contains("XUPAN_LOG_TOTAL_SIZE_CAP:2GB"));
        assertFalse(config.matches("(?is).*password\\s*[:=].*"));
    }
}
