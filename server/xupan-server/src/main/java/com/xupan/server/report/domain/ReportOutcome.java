package com.xupan.server.report.domain;

/**
 * BY220 报网结果（`x_record.flyers_status` 的三态：2 已报、3 失败/超时，以及 0/1 未报）。
 *
 * <p>对应 xupan 的 {@code game_bet.report_status}：{@code ACCEPTED → REPORTED}、
 * {@code REJECTED → FAILED}、{@code TIMEOUT → TIMEOUT}。</p>
 */
public enum ReportOutcome {

    /** 上游接受该注单。 */
    ACCEPTED,
    /** 上游明确拒绝该注单。 */
    REJECTED,
    /** 提交超时，状态未知，必须查询或人工处理，不能直接当作失败退款。 */
    TIMEOUT
}
