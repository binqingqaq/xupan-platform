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
    /** 中心订单仍在处理中；原幂等单继续核实，不新增中心订单且不退款。 */
    PENDING,
    /** 提交超时，状态未知，必须查询或人工处理，不能直接当作失败退款。 */
    TIMEOUT,
    /** 没有有效中心会话，订单未发送；不属于拒绝，不触发退单。 */
    NOT_SENT,
    /** 中心单已撤销；需要人工核对本地玩家单，不能继续按未知结果重试。 */
    CENTER_CANCELLED,
    /** 中心单已退款；需要人工核对本地玩家单，不能继续按未知结果重试。 */
    CENTER_REFUNDED
}
