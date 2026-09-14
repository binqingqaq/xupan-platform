package com.xupan.server.robot.service;

import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotStatus;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RobotSelectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    private final RobotSelectionService service = new RobotSelectionService();

    @Test
    void ignoresDisabledRobotsAndUsesConfiguredWeight() {
        ChatRobot disabled = robot(1L, "disabled", RobotStatus.DISABLED, 100);
        ChatRobot first = robot(2L, "first", RobotStatus.ENABLED, 1);
        ChatRobot second = robot(3L, "second", RobotStatus.ENABLED, 3);
        List<ChatRobot> robots = List.of(second, disabled, first);

        Optional<ChatRobot> selected = service.select(robots, "1001", "ISSUE_STARTED");

        assertThat(selected).isPresent();
        assertThat(selected.get().id()).isEqualTo(expectedId(robots, "1001", "ISSUE_STARTED"));
        assertThat(selected.get().status()).isEqualTo(RobotStatus.ENABLED);
    }

    @Test
    void sameEventAlwaysSelectsTheSameRobotEvenWhenRetriedOrInputOrderChanges() {
        ChatRobot first = robot(11L, "first", RobotStatus.ENABLED, 2);
        ChatRobot second = robot(12L, "second", RobotStatus.ENABLED, 5);
        ChatRobot third = robot(13L, "third", RobotStatus.ENABLED, 3);

        ChatRobot selected = service.select(List.of(first, second, third), "1001", "DRAW_RESULT").orElseThrow();

        assertThat(service.select(List.of(first, second, third), "1001", "DRAW_RESULT"))
                .contains(selected);
        assertThat(service.select(List.of(third, first, second), "1001", "DRAW_RESULT"))
                .contains(selected);
        assertThat(service.select(List.of(first, second, third), "1002", "DRAW_RESULT"))
                .isNotEmpty();
    }

    @Test
    void returnsEmptyWhenAllRobotsAreDisabledOrListIsEmpty() {
        assertThat(service.select(List.of(
                robot(1L, "one", RobotStatus.DISABLED, 1),
                robot(2L, "two", RobotStatus.DISABLED, 100)), "1001", "ISSUE_STARTED"))
                .isEmpty();
        assertThat(service.select(List.of(), "1001", "ISSUE_STARTED")).isEmpty();
    }

    @Test
    void rejectsInvalidSelectionInputAndEnabledWeight() {
        assertThatThrownBy(() -> service.select(List.of(), "1001|unsafe", "ISSUE_STARTED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROBOT_SELECTION_INPUT_INVALID");
        assertThatThrownBy(() -> service.select(List.of(
                robot(1L, "bad", RobotStatus.ENABLED, 0)), "1001", "ISSUE_STARTED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROBOT_WEIGHT_INVALID");
    }

    private static long expectedId(List<ChatRobot> robots, String issueNumber, String eventType) {
        List<ChatRobot> candidates = robots.stream()
                .filter(robot -> robot != null && robot.status() == RobotStatus.ENABLED)
                .sorted(java.util.Comparator.comparingLong(ChatRobot::id)
                        .thenComparing(ChatRobot::robotCode))
                .toList();
        long total = candidates.stream().mapToLong(ChatRobot::weight).sum();
        CRC32 crc32 = new CRC32();
        crc32.update((issueNumber + "|" + eventType).getBytes(StandardCharsets.UTF_8));
        long slot = crc32.getValue() % total;
        long cumulative = 0;
        for (ChatRobot robot : candidates) {
            cumulative += robot.weight();
            if (slot < cumulative) {
                return robot.id();
            }
        }
        throw new AssertionError("no expected robot");
    }

    private static ChatRobot robot(long id, String code, RobotStatus status, int weight) {
        return new ChatRobot(id, code, code, "robot-default", status, weight, 0, NOW, NOW);
    }
}
