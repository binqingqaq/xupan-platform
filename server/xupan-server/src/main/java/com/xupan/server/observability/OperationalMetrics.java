package com.xupan.server.observability;

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

    public OperationalMetrics(RobotDispatchRepository robotDispatchRepository,
                              ChatOutboxRepository chatOutboxRepository,
                              ChatConnectionRegistry chatConnectionRegistry) {
        this.robotDispatchRepository = robotDispatchRepository;
        this.chatOutboxRepository = chatOutboxRepository;
        this.chatConnectionRegistry = chatConnectionRegistry;
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
}
