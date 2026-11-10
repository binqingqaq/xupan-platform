package com.xupan.server.platformadmin.service;

import com.xupan.server.web.BusinessException;
import com.xupan.server.auth.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MacauFourMinuteDrawServiceTest {

    @Test
    void acceptsSevenOptionalDistinctNumbersInRange() {
        MacauFourMinuteDrawService.validatePreset(List.of(1, 40, 7, 19, 28, 3, 22));
        MacauFourMinuteDrawService.validatePreset(java.util.Arrays.asList(1, null, 7, null, null, 3, 22));
    }

    @Test
    void rejectsInvalidLengthRangeAndDuplicatePresets() {
        assertThatThrownBy(() -> MacauFourMinuteDrawService.validatePreset(List.of(1, 2)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> MacauFourMinuteDrawService.validatePreset(
                java.util.Arrays.asList(1, null, 41, null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> MacauFourMinuteDrawService.validatePreset(
                java.util.Arrays.asList(1, null, 1, null, null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void fillsEmptyPositionsWithoutReplacingPresetsOrRepeatingNumbers() {
        List<Integer> numbers = MacauFourMinuteDrawService.completeNumbers(
                java.util.Arrays.asList(8, null, 21, null, null, 40, null), new SecureRandom());

        assertThat(numbers).hasSize(7).doesNotContainNull();
        assertThat(numbers.get(0)).isEqualTo(8);
        assertThat(numbers.get(2)).isEqualTo(21);
        assertThat(numbers.get(5)).isEqualTo(40);
        assertThat(new HashSet<>(numbers)).hasSize(7);
        assertThat(numbers).allSatisfy(number -> assertThat(number).isBetween(1, 40));
    }

    @Test
    void schedulesAtEightThirtyInBeijingAndMovesToTomorrowAtTheBoundary() {
        Instant beforeDraw = Instant.parse("2026-11-10T12:29:59Z");
        Instant atDraw = Instant.parse("2026-11-10T12:30:00Z");

        assertThat(MacauFourMinuteDrawService.nextDailyDraw(beforeDraw))
                .isEqualTo(Instant.parse("2026-11-10T12:30:00Z"));
        assertThat(MacauFourMinuteDrawService.nextDailyDraw(atDraw))
                .isEqualTo(Instant.parse("2026-11-11T12:30:00Z"));
    }

    @Test
    void requiresDedicatedAdminPermissionBeforeReadingOrSaving() {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasPermission(12L, MacauFourMinuteDrawService.READ_PERMISSION)).thenReturn(false);
        when(permissions.hasPermission(12L, MacauFourMinuteDrawService.PRESET_PERMISSION)).thenReturn(false);
        MacauFourMinuteDrawService service = new MacauFourMinuteDrawService((JdbcTemplate) null, permissions);

        assertThatThrownBy(() -> service.list(1, 20, 12L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.savePreset(100, List.of(1, 2, 3, 4, 5, 6, 7), 12L))
                .isInstanceOf(BusinessException.class);
    }
}
