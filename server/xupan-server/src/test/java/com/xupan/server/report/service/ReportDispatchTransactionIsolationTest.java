package com.xupan.server.report.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import com.xupan.server.report.repository.ReportConfigRepository;
import com.xupan.server.report.repository.ReportFailureCancellationRepository;
import com.xupan.server.report.repository.ReportUpstreamOrderRepository;
import com.xupan.server.system.service.PlayerDeskScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDispatchTransactionIsolationTest {

    @Test
    void oneFailedBetDoesNotRollbackTheNextBetInTheBatch() {
        ReportConfigRepository configRepository = mock(ReportConfigRepository.class);
        ReportUpstreamOrderRepository orderRepository = mock(ReportUpstreamOrderRepository.class);
        PlayerDeskScopeService scopeService = mock(PlayerDeskScopeService.class);
        PermissionService permissionService = mock(PermissionService.class);
        OperationAuditRepository auditRepository = mock(OperationAuditRepository.class);
        CenterReportAdapter adapter = mock(CenterReportAdapter.class);
        CenterUpstreamAccountService accountService = mock(CenterUpstreamAccountService.class);
        ReportFailureCancellationRepository cancellationRepository =
                mock(ReportFailureCancellationRepository.class);
        VirtualWalletService walletService = mock(VirtualWalletService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report-dispatch-isolation;DB_CLOSE_DELAY=-1", "sa", "");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);

        ReportService service = new ReportService(configRepository, orderRepository, scopeService,
                permissionService, auditRepository, adapter, accountService, cancellationRepository,
                walletService, chatMessageService, jdbc, transactionManager, 5, "30s,2m,10m,1h");

        ReportUpstreamOrderRepository.BetRow first = bet(101L);
        ReportUpstreamOrderRepository.BetRow second = bet(102L);
        ReportConfigRepository.ReportConfigRow config = new ReportConfigRepository.ReportConfigRow(
                20L, 30L, "M20", "机器 20", true, true, false);
        when(orderRepository.findPendingAttempts(50)).thenReturn(List.of());
        when(orderRepository.findSubmittableBets(any(), eq(50))).thenReturn(List.of(first, second));
        when(orderRepository.findBet(101L)).thenReturn(Optional.of(first));
        when(orderRepository.findBet(102L)).thenReturn(Optional.of(second));
        when(orderRepository.isReportableBet(anyLong(), anyLong())).thenReturn(true);
        when(configRepository.findConfig(20L)).thenReturn(Optional.of(config));
        when(orderRepository.findLatestAttempt(anyLong())).thenReturn(Optional.empty());
        when(orderRepository.nextAttemptNo(anyLong())).thenReturn(1);
        when(orderRepository.insertAttempt(anyLong(), anyLong(), any(), anyInt(), anyString(), anyString(),
                any(), any(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(1L);

        List<Integer> completions = new ArrayList<>();
        when(adapter.submit(any(ReportCommand.class))).thenAnswer(invocation -> {
            ReportCommand command = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completions.add(status);
                }
            });
            if (command.betId() == 101L) {
                throw new IllegalStateException("synthetic failure for first bet");
            }
            return ReportOutcome.ACCEPTED;
        });

        ReportService.DispatchResult result = service.dispatchPending(false, 50);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.submitted()).isEqualTo(1);
        assertThat(result.withdrawn()).isZero();
        assertThat(completions).containsExactly(
                TransactionSynchronization.STATUS_ROLLED_BACK,
                TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void lostRecoveryCasDoesNotRefundOrScheduleRetry() {
        ReportConfigRepository configRepository = mock(ReportConfigRepository.class);
        ReportUpstreamOrderRepository orderRepository = mock(ReportUpstreamOrderRepository.class);
        PlayerDeskScopeService scopeService = mock(PlayerDeskScopeService.class);
        PermissionService permissionService = mock(PermissionService.class);
        OperationAuditRepository auditRepository = mock(OperationAuditRepository.class);
        CenterReportAdapter adapter = mock(CenterReportAdapter.class);
        CenterUpstreamAccountService accountService = mock(CenterUpstreamAccountService.class);
        ReportFailureCancellationRepository cancellationRepository =
                mock(ReportFailureCancellationRepository.class);
        VirtualWalletService walletService = mock(VirtualWalletService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report-recovery-cas;DB_CLOSE_DELAY=-1", "sa", "");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        ReportService service = new ReportService(configRepository, orderRepository, scopeService,
                permissionService, auditRepository, adapter, accountService, cancellationRepository,
                walletService, chatMessageService, jdbc, transactionManager, 5, "30s,2m,10m,1h");

        ReportUpstreamOrderRepository.BetRow bet = bet(101L);
        ReportUpstreamOrderRepository.AttemptRow pending = new ReportUpstreamOrderRepository.AttemptRow(
                501L, 101L, 20L, 30L, "CENTER", 1, 3, "TIMEOUT", "BET-101",
                "TIMEOUT", "unknown", null, null, null, null);
        ReportUpstreamOrderRepository.AttemptRow refunded = new ReportUpstreamOrderRepository.AttemptRow(
                501L, 101L, 20L, 30L, "CENTER", 1, 3, "REFUNDED", "BET-101",
                "REJECTED", "rejected", null, null, null, null);
        when(orderRepository.findPendingAttempts(50)).thenReturn(List.of(pending));
        when(orderRepository.findBet(101L)).thenReturn(Optional.of(bet));
        when(orderRepository.findSubmittableBets(any(), eq(50))).thenReturn(List.of());
        when(orderRepository.findLatestAttempt(101L)).thenReturn(Optional.of(refunded));
        when(adapter.recover(any(ReportCommand.class), eq(30L))).thenReturn(ReportOutcome.REJECTED);
        when(orderRepository.resolveAttempt(eq(501L), eq(3), eq("REJECTED"), eq("REJECTED"),
                anyString(), eq("BET-101"), any(Instant.class))).thenReturn(0);

        ReportService.DispatchResult result = service.dispatchPending(true, 50);

        assertThat(result.submitted()).isEqualTo(1);
        verify(cancellationRepository, never()).cancelPendingBet(anyLong(), any());
        verify(orderRepository, never()).scheduleRetry(anyLong(), any());
        verify(orderRepository, never()).markManual(anyLong(), anyString(), anyString());
    }

    @Test
    void rejectedAttemptIncrementsRetryCountOnceAndSetsBackoffSeparately() {
        ReportConfigRepository configRepository = mock(ReportConfigRepository.class);
        ReportUpstreamOrderRepository orderRepository = mock(ReportUpstreamOrderRepository.class);
        PlayerDeskScopeService scopeService = mock(PlayerDeskScopeService.class);
        PermissionService permissionService = mock(PermissionService.class);
        OperationAuditRepository auditRepository = mock(OperationAuditRepository.class);
        CenterReportAdapter adapter = mock(CenterReportAdapter.class);
        CenterUpstreamAccountService accountService = mock(CenterUpstreamAccountService.class);
        ReportFailureCancellationRepository cancellationRepository =
                mock(ReportFailureCancellationRepository.class);
        VirtualWalletService walletService = mock(VirtualWalletService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report-retry-count;DB_CLOSE_DELAY=-1", "sa", "");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        ReportService service = new ReportService(configRepository, orderRepository, scopeService,
                permissionService, auditRepository, adapter, accountService, cancellationRepository,
                walletService, chatMessageService, jdbc, transactionManager, 5, "30s,2m,10m,1h");

        ReportUpstreamOrderRepository.BetRow bet = bet(101L);
        ReportConfigRepository.ReportConfigRow config = new ReportConfigRepository.ReportConfigRow(
                20L, 30L, "M20", "机器 20", true, true, false);
        when(orderRepository.findPendingAttempts(50)).thenReturn(List.of());
        when(orderRepository.findSubmittableBets(any(), eq(50))).thenReturn(List.of(bet));
        when(orderRepository.findBet(101L)).thenReturn(Optional.of(bet));
        when(orderRepository.isReportableBet(101L, 20L)).thenReturn(true);
        when(configRepository.findConfig(20L)).thenReturn(Optional.of(config));
        when(orderRepository.findLatestAttempt(101L)).thenReturn(Optional.empty());
        when(orderRepository.nextAttemptNo(101L)).thenReturn(1);
        when(orderRepository.insertAttempt(anyLong(), anyLong(), any(), anyInt(), anyString(), anyString(),
                any(), any(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(501L);
        when(adapter.submit(any(ReportCommand.class))).thenReturn(ReportOutcome.REJECTED);
        when(orderRepository.resolveAttempt(eq(501L), eq(0), eq("REJECTED"), eq("REJECTED"),
                anyString(), eq("BET-101"), any(Instant.class))).thenReturn(1);
        when(orderRepository.scheduleRetry(501L, null)).thenReturn(1);

        service.dispatchPending(false, 50);

        verify(orderRepository).scheduleRetry(501L, null);
        verify(orderRepository).updateRetryAt(eq(501L), any(Instant.class));
    }

    private static ReportUpstreamOrderRepository.BetRow bet(long betId) {
        return new ReportUpstreamOrderRepository.BetRow(betId, 1L, 2L, 20L, "39999901",
                "FAN", "1", new BigDecimal("100.00"), new BigDecimal("3.850"),
                "PENDING", "PENDING", null);
    }
}
