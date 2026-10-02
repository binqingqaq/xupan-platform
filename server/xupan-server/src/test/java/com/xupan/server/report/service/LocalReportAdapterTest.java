package com.xupan.server.report.service;

import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地模拟报网适配器的确定性规则验证。
 *
 * <p>依据：BY220 本地参考环境使用"本地盘口"（`x_rbwp.websiteUrl` 指向没有真实服务的地址、
 * `BY220_LOCAL_MODE=1` 绕过真实登录），因此 xupan 只做本地模拟，不发任何网络请求。</p>
 */
class LocalReportAdapterTest {

    private final LocalReportAdapter adapter = new LocalReportAdapter();

    private static ReportCommand command(String reportType, String networkCode, String routeCode, boolean online) {
        return new ReportCommand(1L, 10L, 18L, "3000000", "FAN", "1",
                new BigDecimal("100.00"), new BigDecimal("3.850"), reportType, networkCode, routeCode, online);
    }

    @Test
    void codeIsLocal() {
        assertThat(adapter.code()).isEqualTo(LocalReportAdapter.CODE);
    }

    @Test
    void configuredAndOnlineBetIsAccepted() {
        assertThat(adapter.submit(command("K28", "K28", "A", true))).isEqualTo(ReportOutcome.ACCEPTED);
        assertThat(adapter.submit(command("TT", "TT", "B", true))).isEqualTo(ReportOutcome.ACCEPTED);
        assertThat(adapter.submit(command("k28", " k28 ", "c", true))).isEqualTo(ReportOutcome.ACCEPTED);
    }

    @Test
    void missingReportTypeIsRejected() {
        assertThat(adapter.submit(command(null, "K28", "A", true))).isEqualTo(ReportOutcome.REJECTED);
        assertThat(adapter.submit(command("", "K28", "A", true))).isEqualTo(ReportOutcome.REJECTED);
        assertThat(adapter.submit(command("NONE", "K28", "A", true))).isEqualTo(ReportOutcome.REJECTED);
        assertThat(adapter.submit(command("none", "K28", "A", true))).isEqualTo(ReportOutcome.REJECTED);
    }

    @Test
    void offlineMachineIsRejected() {
        assertThat(adapter.submit(command("K28", "K28", "A", false))).isEqualTo(ReportOutcome.REJECTED);
    }

    @Test
    void incompleteNetworkOrRouteIsRejected() {
        assertThat(adapter.submit(command("K28", null, "A", true))).isEqualTo(ReportOutcome.REJECTED);
        assertThat(adapter.submit(command("K28", "K28", null, true))).isEqualTo(ReportOutcome.REJECTED);
        assertThat(adapter.submit(command("K28", "  ", "A", true))).isEqualTo(ReportOutcome.REJECTED);
        // 未知盘口线路按拒绝处理，不静默接受
        assertThat(adapter.submit(command("K28", "K28", "Z", true))).isEqualTo(ReportOutcome.REJECTED);
    }

    @Test
    void routeDIsUsedForTimeoutDrillAndMustNotBeTreatedAsFailure() {
        // 盘口 D 用于联调超时分支：调用方不得据此直接退款（见实施计划第 11 节）
        assertThat(adapter.submit(command("K28", "K28", "D", true))).isEqualTo(ReportOutcome.TIMEOUT);
        assertThat(adapter.submit(command("K28", "K28", "d", true))).isEqualTo(ReportOutcome.TIMEOUT);
    }

    @Test
    void nullCommandIsRejectedByContract() {
        assertThatThrownBy(() -> adapter.submit(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
