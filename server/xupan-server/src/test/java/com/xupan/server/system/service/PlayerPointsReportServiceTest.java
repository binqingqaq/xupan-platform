package com.xupan.server.system.service;

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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerPointsReportServiceTest {

    @Mock
    private PlayerPointsReportRepository repository;

    @Mock
    private PlayerDeskScopeService scopeService;

    @Test
    void businessDayStartsAtSixInShanghai() {
        PlayerPointsReportService.BusinessDay day = PlayerPointsReportService.resolveBusinessDay("2026-09-21");

        assertThat(day.businessDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(day.fromInclusive()).isEqualTo(Instant.parse("2026-09-20T22:00:00Z"));
        assertThat(day.toExclusive()).isEqualTo(Instant.parse("2026-09-21T22:00:00Z"));
    }

    @Test
    void reportAggregatesBetsAndAdminPointOperationsForSelectedKind() {
        when(scopeService.requireAccess(7L)).thenReturn(PlayerDeskScopeService.Scope.Global.INSTANCE);
        Instant createdAt = Instant.parse("2026-09-21T10:00:00Z");
        PlayerPointsReportRepository.PlayerAccount player = new PlayerPointsReportRepository.PlayerAccount(
                7L, 70L, 0L, "SINGLE", "v1001", "P-001", "普通玩家", "NORMAL", money("40"), money("100"));
        when(repository.findPlayers(eq("NORMAL"), any(Instant.class), any(Instant.class), any()))
                .thenReturn(List.of(player));
        PlayerPointsReportRepository.BetEvent wonBet = new PlayerPointsReportRepository.BetEvent(
                1L, 70L, "B-1", "20260921", 1, "FAN", "1", money("10"), money("1.95"),
                money("5"), money("0"), "WIN", money("8"), "中奖", createdAt, createdAt);
        PlayerPointsReportRepository.BetEvent pendingBet = new PlayerPointsReportRepository.BetEvent(
                2L, 70L, "B-2", "20260921", 1, "FAN", "2", money("20"), money("1.95"),
                money("5"), money("0"), "PENDING", BigDecimal.ZERO, null, createdAt, null);
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<PlayerPointsReportRepository.BetEvent> consumer = invocation.getArgument(4);
            consumer.accept(wonBet);
            consumer.accept(pendingBet);
            return null;
        }).when(repository).forEachBetEvent(eq("NORMAL"), any(Instant.class), any(Instant.class), any(), any());
        when(repository.findBetEvents(eq("NORMAL"), any(Instant.class), any(Instant.class), eq(1), any()))
                .thenReturn(List.of(wonBet));
        when(repository.findLedgerTotals(eq("NORMAL"), any(Instant.class), any(Instant.class), any()))
                .thenReturn(List.of(new PlayerPointsReportRepository.LedgerTotals(70L, money("100"), money("30"))));
        when(repository.findLedgerEvents(eq("NORMAL"), any(Instant.class), any(Instant.class), eq(1), any()))
                .thenReturn(List.of(new PlayerPointsReportRepository.LedgerEvent(11L, 70L, "ADMIN_GRANT", money("100"),
                        money("0"), money("100"), 9L, "管理员", "G-1", null, null, "上分", createdAt)));
        when(repository.findActionEvents(eq("NORMAL"), any(Instant.class), any(Instant.class), eq(1), any()))
                .thenReturn(List.of());

        PlayerPointsReportService.Report report = new PlayerPointsReportService(repository, scopeService)
                .report("normal", "2026-09-21", 1, 7L);

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
        assertThat(report.rebateMode()).isEqualTo("SINGLE");
        assertThat(report.summary().singleFlow()).isEqualByComparingTo("8.00");
        assertThat(report.summary().doubleFlow()).isEqualByComparingTo("10.00");
        assertThat(report.summary().singleRebate()).isEqualByComparingTo("0.40");
        assertThat(report.summary().effectiveFlow()).isEqualByComparingTo("8.00");
        assertThat(report.summary().effectiveRebate()).isEqualByComparingTo("0.40");
        assertThat(report.players()).singleElement().satisfies(card -> {
            assertThat(card.bets()).hasSize(1);
            assertThat(card.pointOperations()).hasSize(1);
            assertThat(card.botActions()).isEmpty();
        });
        verify(repository).findActionEvents(eq("NORMAL"), eq(report.fromInclusive()), eq(report.toExclusive()), eq(1), any());
    }

    @Test
    void reportSelectsDoubleMetricsFromMachineConfiguration() {
        when(scopeService.requireAccess(7L)).thenReturn(PlayerDeskScopeService.Scope.Global.INSTANCE);
        Instant createdAt = Instant.parse("2026-09-21T10:00:00Z");
        PlayerPointsReportRepository.PlayerAccount player = new PlayerPointsReportRepository.PlayerAccount(
                7L, 70L, 9L, "DOUBLE", "v1001", "P-001", "托玩家", "BOT", money("0"), money("10"));
        PlayerPointsReportRepository.BetEvent losingBet = new PlayerPointsReportRepository.BetEvent(
                1L, 70L, "B-1", "20260921", 1, "FAN", "1", money("10"), money("1.95"),
                money("5"), money("0"), "LOSE", money("-10"), "未中", createdAt, createdAt);
        when(repository.findPlayers(eq("BOT"), any(Instant.class), any(Instant.class), any()))
                .thenReturn(List.of(player));
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<PlayerPointsReportRepository.BetEvent> consumer = invocation.getArgument(4);
            consumer.accept(losingBet);
            return null;
        }).when(repository).forEachBetEvent(eq("BOT"), any(Instant.class), any(Instant.class), any(), any());
        when(repository.findBetEvents(eq("BOT"), any(Instant.class), any(Instant.class), eq(500), any()))
                .thenReturn(List.of(losingBet));
        when(repository.findLedgerTotals(eq("BOT"), any(Instant.class), any(Instant.class), any())).thenReturn(List.of());
        when(repository.findLedgerEvents(eq("BOT"), any(Instant.class), any(Instant.class), eq(500), any()))
                .thenReturn(List.of());
        when(repository.findActionEvents(eq("BOT"), any(Instant.class), any(Instant.class), eq(500), any()))
                .thenReturn(List.of());

        PlayerPointsReportService.Report report = new PlayerPointsReportService(repository, scopeService)
                .report("BOT", "2026-09-21", 500, 7L);

        assertThat(report.rebateMode()).isEqualTo("DOUBLE");
        assertThat(report.summary().effectiveFlow()).isEqualByComparingTo("10.00");
        assertThat(report.summary().doubleRebate()).isEqualByComparingTo("0.50");
    }

    @Test
    void invalidKindAndLimitAreRejectedBeforeRepositoryAccess() {
        when(scopeService.requireAccess(7L)).thenReturn(PlayerDeskScopeService.Scope.Global.INSTANCE);
        PlayerPointsReportService service = new PlayerPointsReportService(repository, scopeService);

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
