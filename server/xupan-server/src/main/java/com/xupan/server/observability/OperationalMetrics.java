package com.xupan.server.observability;

import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.center.service.CenterReconciliationService;
import com.xupan.server.chat.realtime.ChatConnectionRegistry;
import com.xupan.server.chat.repository.ChatOutboxRepository;
import com.xupan.server.robot.domain.RobotDispatchStatus;
import com.xupan.server.robot.repository.RobotDispatchRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Read-only gauges that expose the in-process and database-backed operational state. */
@Component
public class OperationalMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);

    private final RobotDispatchRepository robotDispatchRepository;
    private final ChatOutboxRepository chatOutboxRepository;
    private final ChatConnectionRegistry chatConnectionRegistry;
    private final CenterOrderRepository centerOrderRepository;
    private final CenterReconciliationService centerReconciliationService;

    public OperationalMetrics(RobotDispatchRepository robotDispatchRepository,
                              ChatOutboxRepository chatOutboxRepository,
                              ChatConnectionRegistry chatConnectionRegistry,
                              CenterOrderRepository centerOrderRepository,
                              CenterReconciliationService centerReconciliationService) {
        this.robotDispatchRepository = robotDispatchRepository;
        this.chatOutboxRepository = chatOutboxRepository;
        this.chatConnectionRegistry = chatConnectionRegistry;
        this.centerOrderRepository = centerOrderRepository;
        this.centerReconciliationService = centerReconciliationService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("xupan_robot_dispatch_pending", this,
                        metrics -> metrics.robotDispatchCount(RobotDispatchStatus.PENDING))
                .description("待处理的机器人投递任务数量")
                .register(registry);
        Gauge.builder("xupan_robot_dispatch_failed", this,
                        metrics -> metrics.robotDispatchCount(RobotDispatchStatus.FAILED))
                .description("失败的机器人投递任务数量")
                .register(registry);
        Gauge.builder("xupan_chat_connections", this,
                        metrics -> metrics.chatConnectionCount())
                .description("当前实例的聊天室 WebSocket 连接数量")
                .register(registry);
        Gauge.builder("xupan_chat_outbox_pending", this,
                        metrics -> metrics.chatOutboxPendingCount())
                .description("待发布的聊天室 outbox 事件数量")
                .register(registry);
        Gauge.builder("xupan_center_order_pending", this,
                        metrics -> metrics.centerOrderCount("PENDING"))
                .description("中心侧待核实订单数量")
                .register(registry);
        Gauge.builder("xupan_center_order_timeout", this,
                        metrics -> metrics.centerOrderCount("TIMEOUT"))
                .description("中心侧超时订单数量")
                .register(registry);
        Gauge.builder("xupan_center_settlement_one_sided_orders", this,
                        OperationalMetrics::oneSidedSettlementCount)
                .description("本地与中心仅一侧完成结算的订单数量")
                .register(registry);
        Gauge.builder("xupan_center_ledger_inconsistent_buckets", this,
                        OperationalMetrics::inconsistentCenterBuckets)
                .description("余额与最新账本不一致的中心账本层数")
                .register(registry);
        Gauge.builder("xupan_agent_ledger_inconsistent_machines", this,
                        OperationalMetrics::inconsistentAgentBuckets)
                .description("积分与最新账本不一致的机器数")
                .register(registry);
    }

    private long robotDispatchCount(RobotDispatchStatus status) {
        try {
            return robotDispatchRepository.countByStatus(status);
        } catch (RuntimeException exception) {
            log.error("读取机器人投递运行指标失败 status={}", status, exception);
            throw exception;
        }
    }

    private int chatConnectionCount() {
        return chatConnectionRegistry.activeConnectionCount();
    }

    private long chatOutboxPendingCount() {
        try {
            return chatOutboxRepository.countPending();
        } catch (RuntimeException exception) {
            log.error("读取聊天室 outbox 运行指标失败 status=PENDING", exception);
            throw exception;
        }
    }

    private long centerOrderCount(String status) {
        try {
            return centerOrderRepository.countByStatus(null, status);
        } catch (RuntimeException exception) {
            log.error("读取中心订单运行指标失败 status={}", status, exception);
            throw exception;
        }
    }

    private long inconsistentCenterBuckets() {
        try {
            return centerReconciliationService.countInconsistentCenterBuckets();
        } catch (RuntimeException exception) {
            log.error("读取中心账本一致性指标失败", exception);
            throw exception;
        }
    }

    private long inconsistentAgentBuckets() {
        try {
            return centerReconciliationService.countInconsistentAgentBuckets();
        } catch (RuntimeException exception) {
            log.error("读取机器账本一致性指标失败", exception);
            throw exception;
        }
    }

    private long oneSidedSettlementCount() {
        try {
            return centerReconciliationService.countOneSidedSettlements();
        } catch (RuntimeException exception) {
            log.error("读取单边结算运行指标失败", exception);
            throw exception;
        }
    }
}
