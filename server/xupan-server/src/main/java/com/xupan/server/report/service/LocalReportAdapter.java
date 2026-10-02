package com.xupan.server.report.service;

import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 本地模拟报网适配器（对应 BY220 本地参考环境的"本地盘口"）。
 *
 * <p>该实现**不发起任何网络请求**，也不保存任何外部凭据：它只按机器配置做出确定性判定，
 * 用于把报网状态机、失败退单和重报流程完整地跑通。这样做的依据是 BY220 自己的本地运行方式
 * （`x_rbwp` 指向 `http://127.0.0.1:9999` 且没有真实服务、`BY220_LOCAL_MODE=1` 绕过真实登录）。</p>
 *
 * <p>判定规则（确定性、可测试）：</p>
 * <ol>
 *   <li>报网类型为空或 {@code NONE} → {@link ReportOutcome#REJECTED}（未配置报网）。</li>
 *   <li>机器报网不在线 → {@link ReportOutcome#REJECTED}（离线，BY220 离线时不报）。</li>
 *   <li>网盘编码或盘口线路缺失 → {@link ReportOutcome#REJECTED}（配置不完整）。</li>
 *   <li>盘口线路为 {@code D} → {@link ReportOutcome#TIMEOUT}（用于联调超时分支，见实施计划第 11 节）。</li>
 *   <li>其余情况 → {@link ReportOutcome#ACCEPTED}。</li>
 * </ol>
 *
 * <p>这些规则是本地模拟的**约定**，不代表任何真实上游行为；接入真实上游时必须另行设计。</p>
 */
@Component
public class LocalReportAdapter implements ReportAdapter {

    /** 适配器编码，与 {@code agent.report_type} 的本地取值对应。 */
    public static final String CODE = "LOCAL";
    /** BY220 报网类型里的"无"取值。 */
    public static final String TYPE_NONE = "NONE";
    /** 用于联调超时分支的盘口线路。 */
    public static final String TIMEOUT_ROUTE = "D";

    private static final Set<String> SUPPORTED_ROUTES = Set.of("A", "B", "C", "D");

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ReportOutcome submit(ReportCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("报网提交参数不能为空");
        }
        if (!configured(command.reportType())) {
            return ReportOutcome.REJECTED;
        }
        if (!command.online()) {
            return ReportOutcome.REJECTED;
        }
        if (!configured(command.networkCode()) || !configured(command.routeCode())) {
            return ReportOutcome.REJECTED;
        }
        String route = normalize(command.routeCode());
        if (!SUPPORTED_ROUTES.contains(route)) {
            return ReportOutcome.REJECTED;
        }
        if (TIMEOUT_ROUTE.equals(route)) {
            return ReportOutcome.TIMEOUT;
        }
        return ReportOutcome.ACCEPTED;
    }

    private static boolean configured(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return !TYPE_NONE.equalsIgnoreCase(value.trim());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
