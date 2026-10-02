package com.xupan.server.report.service;

import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import org.springframework.stereotype.Component;

/**
 * 中心上游适配器（{@code report_type = CENTER}）。
 *
 * <p>这一层**不直接访问网络**：它把"订单要报给中心"转换成一个内部的入站服务调用
 * （{@link com.xupan.server.center.service.CenterOrderService}），由中心侧完成
 * Token 归属、校验、额度冻结与建档。</p>
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

    /** 适配器编码，与 {@code agent.report_type = CENTER} 对应。 */
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
            return ReportOutcome.REJECTED;
        }
        return gateway.submit(command.machineId(), command.betId(), command.issueNumber(),
                command.playType(), command.parameters(), command.stake(), command.odds());
    }
}
