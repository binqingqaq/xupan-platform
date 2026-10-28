package com.xupan.server.report.service;

import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import org.springframework.stereotype.Component;

/**
 * 中心账号报网适配器。
 *
 * <p>这一层**不直接访问网络**：它把"订单要报给中心"转换成一个内部的入站服务调用
 * （{@link com.xupan.server.center.service.CenterOrderService}），由中心侧完成
 * 中心账号归属校验、额度处理与建档。机器身份来自有效的机器级中心账号会话。</p>
 *
 * <p>为什么不用 HTTP 自调用：中心与本地在**同一个应用、同一个库**里，
 * 走 HTTP 会引入一个自己打自己的假分布式边界（超时、重试、部分失败都要额外处理），
 * 而业务事实完全一样。等中心真正拆成独立服务时，只需把这一层换成 HTTP 客户端，
 * 接口契约不变。</p>
 *
 * <p>对应《代理报网盘口与结算业务规则说明》§10（两层账）、§16.3/§16.4（上报与受理）。</p>
 *
 * <p>边界（强制）：只做虚拟积分与内部风险敞口，不接入任何真实外部上游、不保存真实凭据。</p>
 */
@Component
public class CenterReportAdapter implements ReportAdapter {

    /** 内部订单尝试的适配器编码。 */
    public static final String CODE = "CENTER";

    private final CenterOrderGateway gateway;

    public CenterReportAdapter(CenterOrderGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ReportOutcome submit(ReportCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("报网提交参数不能为空");
        }
        if (!command.online()) {
            return ReportOutcome.NOT_SENT;
        }
        return gateway.submit(command.machineId(), command.betId(), command.issueNumber(),
                command.playType(), command.parameters(), command.stake(), command.odds());
    }

    /** Replays an in-flight order with its persisted center account identity. */
    public ReportOutcome recover(ReportCommand command, long centerAgentId) {
        if (command == null) {
            throw new IllegalArgumentException("报网提交参数不能为空");
        }
        return gateway.recover(centerAgentId, command.machineId(), command.betId(), command.issueNumber(),
                command.playType(), command.parameters(), command.stake(), command.odds());
    }
}
