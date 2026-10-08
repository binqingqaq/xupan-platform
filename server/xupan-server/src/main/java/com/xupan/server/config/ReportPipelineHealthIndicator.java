package com.xupan.server.config;

import com.xupan.server.report.repository.ReportUpstreamOrderRepository;
import com.xupan.server.report.service.ReportService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 上报链路健康检查（P0-2 的告警出口）。
 *
 * <p><b>为什么需要它</b>：原缺陷最危险的地方不是"报不上去"，而是**报不上去却没有声音**——
 * 50 笔坏单占满批次后，新注单永远排不上，业务方只能靠玩家投诉才发现。
 * 这里把三个信号暴露到 {@code /actuator/health}，让监控可以主动报警：</p>
 * <ol>
 *   <li>{@code backlog}：到点可报的待上报积压量。健康运行时它应随时间下降；持续不降 = 链路堵住。</li>
 *   <li>{@code manual}：转入人工待处置的注单总数（多次重试仍失败）。</li>
 *   <li>{@code skippedTaskRounds}：定时任务被其它进程占锁而跳过的轮次。
 *       单实例下应恒为 0；持续增长说明有第二个进程在跑同一批任务（重复开奖前兆）。</li>
 * </ol>
 *
 * <p>判定口径刻意保守：只有积压**超过阈值**才报 {@code DOWN}，
 * 短时波动（例如封盘前集中下注）不会触发告警。</p>
 *
 * <p>注意 Spring Boot 4 把健康检查类移到了 {@code org.springframework.boot.health.contributor}
 * （Boot 3 是 {@code org.springframework.boot.actuate.health}），因此这里的 import 与旧代码不同。</p>
 */
@Component("reportPipelineHealth")
public class ReportPipelineHealthIndicator implements HealthIndicator {

    /** 待上报积压超过该值即认为链路异常。按 30 代理规模给的宽松阈值。 */
    private static final long BACKLOG_ALERT_THRESHOLD = 500;

    private final ReportUpstreamOrderRepository orderRepository;
    private final ScheduledTaskLock taskLock;

    public ReportPipelineHealthIndicator(ReportUpstreamOrderRepository orderRepository,
                                         ScheduledTaskLock taskLock) {
        this.orderRepository = orderRepository;
        this.taskLock = taskLock;
    }

    @Override
    public Health health() {
        long backlog = orderRepository.backlogCount(ReportService.RESUBMITTABLE_REPORT_STATUSES);
        long manual = orderRepository.manualCountAll();
        Map<String, Long> skipped = taskLock.skippedRounds();
        long skippedTotal = skipped.values().stream().mapToLong(Long::longValue).sum();

        Health.Builder builder = backlog > BACKLOG_ALERT_THRESHOLD ? Health.down() : Health.up();
        return builder
                .withDetail("backlog", backlog)
                .withDetail("backlogAlertThreshold", BACKLOG_ALERT_THRESHOLD)
                .withDetail("manualPending", manual)
                .withDetail("skippedTaskRounds", skippedTotal)
                .withDetail("skippedTaskRoundsByLock", skipped)
                .build();
    }
}
