package com.xupan.server.observability;

import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.center.service.CenterReconciliationService;
import com.xupan.server.chat.realtime.ChatConnectionRegistry;
import com.xupan.server.chat.repository.ChatOutboxRepository;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalMetricsTest {

    @Test
    void registersOperationalGaugesWithCurrentValues() {
        RobotDispatchRepository dispatchRepository = mock(RobotDispatchRepository.class);
        ChatOutboxRepository outboxRepository = mock(ChatOutboxRepository.class);
        ChatConnectionRegistry connectionRegistry = mock(ChatConnectionRegistry.class);
        CenterOrderRepository centerOrderRepository = mock(CenterOrderRepository.class);
        CenterReconciliationService reconciliationService = mock(CenterReconciliationService.class);
        when(dispatchRepository.countByStatus(RobotDispatchStatus.PENDING)).thenReturn(3L);
        when(dispatchRepository.countByStatus(RobotDispatchStatus.FAILED)).thenReturn(2L);
        when(outboxRepository.countPending()).thenReturn(4L);
        when(connectionRegistry.activeConnectionCount()).thenReturn(5);
        when(centerOrderRepository.countByStatus(null, "PENDING")).thenReturn(6L);
        when(centerOrderRepository.countByStatus(null, "TIMEOUT")).thenReturn(7L);
        when(reconciliationService.countInconsistentCenterBuckets()).thenReturn(8L);
        when(reconciliationService.countInconsistentAgentBuckets()).thenReturn(9L);
        when(reconciliationService.countOneSidedSettlements()).thenReturn(10L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OperationalMetrics(dispatchRepository, outboxRepository, connectionRegistry,
                centerOrderRepository, reconciliationService).bindTo(registry);

        assertThat(registry.get("xupan_robot_dispatch_pending").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("xupan_robot_dispatch_failed").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("xupan_chat_connections").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("xupan_chat_outbox_pending").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("xupan_center_order_pending").gauge().value()).isEqualTo(6.0);
        assertThat(registry.get("xupan_center_order_timeout").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("xupan_center_ledger_inconsistent_buckets").gauge().value()).isEqualTo(8.0);
        assertThat(registry.get("xupan_agent_ledger_inconsistent_machines").gauge().value()).isEqualTo(9.0);
        assertThat(registry.get("xupan_center_settlement_one_sided_orders").gauge().value()).isEqualTo(10.0);
    }

    @Test
    void gaugesReadNewValuesWhenTheUnderlyingStateChanges() {
        RobotDispatchRepository dispatchRepository = mock(RobotDispatchRepository.class);
        ChatOutboxRepository outboxRepository = mock(ChatOutboxRepository.class);
        ChatConnectionRegistry connectionRegistry = mock(ChatConnectionRegistry.class);
        CenterOrderRepository centerOrderRepository = mock(CenterOrderRepository.class);
        CenterReconciliationService reconciliationService = mock(CenterReconciliationService.class);
        when(dispatchRepository.countByStatus(RobotDispatchStatus.PENDING)).thenReturn(1L, 6L);
        when(dispatchRepository.countByStatus(RobotDispatchStatus.FAILED)).thenReturn(0L);
        when(outboxRepository.countPending()).thenReturn(2L);
        when(connectionRegistry.activeConnectionCount()).thenReturn(1, 0);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OperationalMetrics(dispatchRepository, outboxRepository, connectionRegistry,
                centerOrderRepository, reconciliationService).bindTo(registry);

        assertThat(registry.get("xupan_robot_dispatch_pending").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("xupan_robot_dispatch_pending").gauge().value()).isEqualTo(6.0);
        assertThat(registry.get("xupan_chat_connections").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("xupan_chat_connections").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void databaseMetricFailuresAreNotConvertedToZero() {
        RobotDispatchRepository dispatchRepository = mock(RobotDispatchRepository.class);
        ChatOutboxRepository outboxRepository = mock(ChatOutboxRepository.class);
        ChatConnectionRegistry connectionRegistry = mock(ChatConnectionRegistry.class);
        CenterOrderRepository centerOrderRepository = mock(CenterOrderRepository.class);
        CenterReconciliationService reconciliationService = mock(CenterReconciliationService.class);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(dispatchRepository.countByStatus(RobotDispatchStatus.PENDING)).thenThrow(failure);
        when(dispatchRepository.countByStatus(RobotDispatchStatus.FAILED)).thenReturn(0L);
        when(outboxRepository.countPending()).thenReturn(0L);
        when(connectionRegistry.activeConnectionCount()).thenReturn(0);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OperationalMetrics(dispatchRepository, outboxRepository, connectionRegistry,
                centerOrderRepository, reconciliationService).bindTo(registry);

        assertThat(registry.get("xupan_robot_dispatch_pending").gauge().value()).isNaN();
    }
}
