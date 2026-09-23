package com.xupan.server.system.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.system.repository.PlayerPointsReportRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerPointsReportServiceTest {

    @Mock
    private PlayerPointsReportRepository repository;

    @Mock
    private PermissionService permissionService;

    @Test
    void businessDayStartsAtSixInShanghai() {
        PlayerPointsReportService.BusinessDay day = PlayerPointsReportService.resolveBusinessDay("2026-09-21");

        assertThat(day.businessDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(day.fromInclusive()).isEqualTo(Instant.parse("2026-09-20T22:00:00Z"));
        assertThat(day.toExclusive()).isEqualTo(Instant.parse("2026-09-21T22:00:00Z"));
    }

    @Test
    void reportAggregatesBetsAndAdminPointOperationsForSelectedKind() {
        when(permissionService.hasPermission(7L, "USER_MANAGE")).thenReturn(true);
        Instant createdAt = Instant.parse("2026-09-21T10:00:00Z");
        PlayerPointsReportRepository.PlayerAccount player = new PlayerPointsReportRepository.PlayerAccount(
                7L, 70L, "v1001", "P-001", "普通玩家", "NORMAL", money("40"), money("100"));
        when(repository.findPlayers(eq("NORMAL"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(player));
        when(repository.findBetEvents(eq("NORMAL"), any(Instant.class), any(Instant.class), eq(10)))
                .thenReturn(List.of(
                        new PlayerPointsReportRepository.BetEvent(1L, 70L, "B-1", "20260921", 1,
                                "FAN", "1", money("10"), money("1.95"), "WIN", money("8"),
                                "中奖", createdAt, createdAt),
                        new PlayerPointsReportRepository.BetEvent(2L, 70L, "B-2", "20260921", 1,
                                "FAN", "2", money("20"), money("1.95"), "PENDING", BigDecimal.ZERO,
                                null, createdAt, null)));
        when(repository.findLedgerEvents(eq("NORMAL"), any(Instant.class), any(Instant.class), eq(10)))
                .thenReturn(List.of(
                        new PlayerPointsReportRepository.LedgerEvent(11L, 70L, "ADMIN_GRANT", money("100"),
                                money("0"), money("100"), 9L, "管理员", "G-1", null, null, "上分", createdAt),
                        new PlayerPointsReportRepository.LedgerEvent(12L, 70L, "ADMIN_ADJUST", money("-30"),
                                money("100"), money("70"), 9L, "管理员", "A-1", null, null, "下分", createdAt)));
        when(repository.findActionEvents(eq("NORMAL"), any(Instant.class), any(Instant.class), eq(10)))
                .thenReturn(List.of());

        PlayerPointsReportService.Report report = new PlayerPointsReportService(repository, permissionService)
                .report("normal", "2026-09-21", 10, 7L);

        assertThat(report.playerKind()).isEqualTo("NORMAL");
        assertThat(report.summary().playerCount()).isEqualTo(1);
        assertThat(report.summary().betCount()).isEqualTo(2);
        assertThat(report.summary().settledBetCount()).isEqualTo(1);
        assertThat(report.summary().turnover()).isEqualByComparingTo("30.00");
        assertThat(report.summary().netProfit()).isEqualByComparingTo("8.00");
        assertThat(report.summary().topUp()).isEqualByComparingTo("100.00");
        assertThat(report.summary().down()).isEqualByComparingTo("30.00");
        assertThat(report.summary().openingBalance()).isEqualByComparingTo("40.00");
        assertThat(report.summary().closingBalance()).isEqualByComparingTo("100.00");
        assertThat(report.players()).singleElement().satisfies(card -> {
            assertThat(card.bets()).hasSize(2);
            assertThat(card.pointOperations()).hasSize(2);
            assertThat(card.botActions()).isEmpty();
        });
        verify(repository).findActionEvents(eq("NORMAL"), eq(report.fromInclusive()), eq(report.toExclusive()), eq(10));
    }

    @Test
    void invalidKindAndLimitAreRejectedBeforeRepositoryAccess() {
        when(permissionService.hasPermission(7L, "USER_MANAGE")).thenReturn(true);
        PlayerPointsReportService service = new PlayerPointsReportService(repository, permissionService);

        assertThatThrownBy(() -> service.report("ALL", "2026-09-21", 10, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("POINTS_REPORT_KIND_INVALID");
        assertThatThrownBy(() -> service.report("NORMAL", "2026-09-21", 1001, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("POINTS_REPORT_LIMIT_INVALID");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
