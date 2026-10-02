package com.xupan.server.report.service;

import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;

/**
 * 报网适配器（BY220 飞单通道）。
 *
 * <p>合规边界（强制）：本项目只使用虚拟积分，**不接入任何真实外部上游**。
 * 因此当前仅提供 {@link LocalReportAdapter} 本地模拟实现；
 * 任何真实上游适配器都必须先通过合规、安全与运维评审，且不得把真实凭据写入仓库。</p>
 *
 * <p>实现必须满足：不修改数据库（状态回写由调用方在同一事务内完成）、
 * 幂等（同一注单重复提交不得在上游重复建单）、可注入可替换以便测试。</p>
 */
public interface ReportAdapter {

    /** 适配器编码，用于按机器 {@code report_type} 选择实现。 */
    String code();

    /**
     * 提交一笔注单。
     *
     * @return 提交结果；{@link ReportOutcome#TIMEOUT} 表示状态未知，调用方不得直接退款。
     */
    ReportOutcome submit(ReportCommand command);
}
