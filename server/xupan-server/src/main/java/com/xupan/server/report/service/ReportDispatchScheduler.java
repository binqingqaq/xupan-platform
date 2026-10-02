package com.xupan.server.report.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 飞单上报轮询（BY220 {@code application/console/Websocket.php:658-673} 的 {@code Swoole\Timer::tick(2400, ...)}）。
 *
 * <p>BY220 每 2.4 秒捞出 {@code flyers_status = 3} 的订单重新飞单；xupan 以可配置间隔调用
 * {@link ReportService#dispatchPending(boolean, int)}，只处理"已开启自动上报且在线且报网类型已配置"的机器。
 * 上报结果写回 {@code game_bet.report_status}（未报→已报/失败/超时）；机器开启失败自动退单时，
 * 失败即退还玩家本金并物理删除注单（BY220 {@code common.php:1627-1641}）。</p>
 *
 * <p>合规：上报只走本地模拟适配器，不发起任何外部网络请求。</p>
 */
@Component
public final class ReportDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportDispatchScheduler.class);

    private final ReportService reportService;
    private final int batchSize;

    public ReportDispatchScheduler(ReportService reportService,
                                   @Value("${xupan.report.dispatch-batch-size:50}") int batchSize) {
        if (reportService == null || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("报网调度器参数无效");
        }
        this.reportService = reportService;
        this.batchSize = batchSize;
    }

    /**
     * 扫描并上报待报订单。
     *
     * <p>BY220 的轮询会一并捞取 {@code flyers_status = 3}（失败/超时）的订单重报，因此这里
     * 同样包含重报；重报次数由调度间隔自然限制，不做额外计数（与 BY220 一致）。</p>
     */
    @Scheduled(fixedDelayString = "${xupan.report.dispatch-interval-ms:2400}")
    public void scan() {
        try {
            ReportService.DispatchResult result = reportService.dispatchPending(true, batchSize);
            if (result.scanned() > 0) {
                log.info("报网上报轮询完成 scanned={} submitted={} withdrawn={}",
                        result.scanned(), result.submitted(), result.withdrawn());
            }
        } catch (RuntimeException exception) {
            log.error("报网上报轮询失败 operation=dispatchPending batchSize={}", batchSize, exception);
        }
    }
}
