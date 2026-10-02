package com.xupan.server.report.service;

import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.service.CenterAgentService;
import com.xupan.server.report.domain.ReportOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地报网 → 中心上游 的适配器链路（阶段 B 第四步）。
 *
 * <p>验证 {@code report_type = CENTER} 时订单真正进入中心：绑定解析、幂等、拒绝语义。
 * 这是"代理下注 → 报中心 → 中心接单"闭环的关键一环。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CenterReportAdapterTest {

    private static final String CENTER_CODE = "ADAPTER_CENTER_A";
    private static final String CENTER_UPSTREAM = "adapter_center_up_a";
    private static final String MACHINE_CODE = "ADAPTER_MACHINE_A";

    @Autowired
    private CenterReportAdapter adapter;

    @Autowired
    private CenterOrderGateway gateway;

    @Autowired
    private CenterAgentService centerAgentService;

    @Autowired
    private CenterAgentRepository centerAgentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.xupan.server.auth.repository.UserRepository userRepository;

    private long centerAgentId;
    private long machineId;
    /** 持 CENTER_AGENT_MANAGE 的操作者（服务层会校验权限，测试也要走同一条路）。 */
    private long operatorId;

    @BeforeEach
    void setUp() {
        clean();
        operatorId = userRepository.insert("adapter-center-admin", "适配器测试管理员",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("AdapterPass123"),
                "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");

        CenterAgentService.Created created = centerAgentService.create(CENTER_CODE, "适配器测试中心代理",
                CENTER_UPSTREAM, new BigDecimal("50000.00"), operatorId);
        centerAgentId = created.agent().id();
        centerAgentService.grantAvailable(centerAgentId, new BigDecimal("1000.00"), "适配器测试额度",
                operatorId, "adapter-grant-1");

        // 造一台本地机器并绑定中心代理身份。
        // 注意 ck_agent_account_binding：非系统自有的机器必须绑定 account_user_id，
        // 所以先建一个机器账号用户。
        long accountUserId = userRepository.insert("adapter-machine-account", "适配器测试机器账号",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("AdapterPass123"),
                "ACTIVE");
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, status, system_owned, board_open,
                                   report_type, report_online, center_agent_id, account_user_id, created_by)
                VALUES (?, '适配器测试机器', 'ACTIVE', FALSE, TRUE, 'CENTER', TRUE, ?, ?, ?)
                """, MACHINE_CODE, centerAgentId, accountUserId, operatorId);
        machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);
        jdbc.update("INSERT INTO agent_game (agent_id, game_code) VALUES (?, 'K28')", machineId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /** 绑定正常时，订单进入中心并成为一笔 ACCEPTED 的中心订单。 */
    @Test
    void submitsOrderIntoCenterWhenMachineIsBound() {
        ReportOutcome outcome = gateway.submit(machineId, 900001L, "39999601", "FAN", "1",
                new BigDecimal("100.00"), new BigDecimal("3.850"));

        assertThat(outcome).isEqualTo(ReportOutcome.ACCEPTED);
        assertThat(centerOrderCount()).isEqualTo(1L);
        // 中心侧记录：游戏编码来自 agent_game、参数随 playText 传递、本金进入冻结
        assertThat(jdbc.queryForObject(
                "SELECT game_code FROM center_order WHERE agent_order_no = 'BET-900001'", String.class))
                .isEqualTo("K28");
        assertThat(jdbc.queryForObject(
                "SELECT play_text FROM center_order WHERE agent_order_no = 'BET-900001'", String.class))
                .isEqualTo("params=1");
        assertThat(availableBalance()).isEqualByComparingTo("900.00");
        assertThat(frozenBalance()).isEqualByComparingTo("100.00");
    }

    /** 同一注单重复上报 → 中心只建一单，额度只冻结一次（幂等）。 */
    @Test
    void repeatedSubmitOfSameBetDoesNotDuplicateCenterOrder() {
        gateway.submit(machineId, 900002L, "39999602", "FAN", "1", new BigDecimal("100.00"),
                new BigDecimal("3.850"));
        gateway.submit(machineId, 900002L, "39999602", "FAN", "1", new BigDecimal("100.00"),
                new BigDecimal("3.850"));

        assertThat(centerOrderCount()).isEqualTo(1L);
        assertThat(frozenBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * 未绑定中心代理身份 → REJECTED（"确认未受理"，代理侧可退单），不得误判为 TIMEOUT。
     *
     * <p>这条很重要：§12.3 规定超时不得直接退款，如果这里返回 TIMEOUT，
     * 一笔配置不完整的订单会永远挂着不退款也不退单。</p>
     */
    @Test
    void rejectsWhenMachineIsNotBoundToAnyCenterAgent() {
        // ck_agent_center_binding：report_type = CENTER 时必须绑定中心代理，所以解绑要连类型一起改
        jdbc.update("UPDATE agent SET report_type = NULL, center_agent_id = NULL WHERE id = ?", machineId);

        ReportOutcome outcome = gateway.submit(machineId, 900003L, "39999603", "FAN", "1",
                new BigDecimal("100.00"), new BigDecimal("3.850"));

        assertThat(outcome).isEqualTo(ReportOutcome.REJECTED);
        assertThat(centerOrderCount()).isZero();
    }

    /** 中心代理被停用后同样按拒绝处理，不会偷偷放行。 */
    @Test
    void rejectsWhenCenterAgentIsDisabled() {
        centerAgentService.changeStatus(centerAgentId, "DISABLED", operatorId);

        ReportOutcome outcome = gateway.submit(machineId, 900004L, "39999604", "FAN", "1",
                new BigDecimal("100.00"), new BigDecimal("3.850"));

        assertThat(outcome).isEqualTo(ReportOutcome.REJECTED);
        assertThat(centerOrderCount()).isZero();
    }

    /** 中心可用额度不足 → 中心拒绝，本地按 REJECTED 处理（可退单），不产生冻结。 */
    @Test
    void rejectsWhenCenterCreditIsInsufficient() {
        ReportOutcome outcome = gateway.submit(machineId, 900005L, "39999605", "FAN", "1",
                new BigDecimal("5000.00"), new BigDecimal("3.850"));

        assertThat(outcome).isEqualTo(ReportOutcome.REJECTED);
        assertThat(frozenBalance()).isEqualByComparingTo("0.00");
    }

    /** 适配器离线时不提交。 */
    @Test
    void offlineMachineDoesNotSubmit() {
        ReportOutcome outcome = adapter.submit(new com.xupan.server.report.domain.ReportCommand(
                900006L, 1L, machineId, "39999606", "FAN", "1", new BigDecimal("100.00"),
                new BigDecimal("3.850"), "CENTER", "K28", "A", false));

        assertThat(outcome).isEqualTo(ReportOutcome.REJECTED);
        assertThat(centerOrderCount()).isZero();
    }

    /** 适配器编码与 report_type 取值一致（路由靠它）。 */
    @Test
    void adapterCodeMatchesReportTypeValue() {
        assertThat(adapter.code()).isEqualTo("CENTER");
        Optional<CenterAgentRepository.AgentRow> row = centerAgentRepository.findById(centerAgentId);
        assertThat(row).isPresent();
        assertThat(row.get().upstreamAccount()).isEqualTo(CENTER_UPSTREAM);
    }

    private long centerOrderCount() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_order WHERE center_agent_id = ?", Long.class, centerAgentId);
        return total == null ? 0L : total;
    }

    private BigDecimal availableBalance() {
        return jdbc.queryForObject("SELECT available_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
    }

    private BigDecimal frozenBalance() {
        return jdbc.queryForObject("SELECT frozen_balance FROM center_agent WHERE id = ?",
                BigDecimal.class, centerAgentId);
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_CODE);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_CODE);
        // ck_agent_center_binding 要求 report_type = CENTER 时必须绑定中心代理，
        // 所以解绑前必须先把报网类型改掉，否则约束会挡住清理。
        jdbc.update("UPDATE agent SET report_type = NULL, center_agent_id = NULL WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", CENTER_CODE);
        jdbc.update("DELETE FROM agent_game WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        // 先删机器（agent 引用 account_user_id），再删账号用户，最后删中心代理
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", "adapter-machine-account");
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", "adapter-machine-account");
        jdbc.update("DELETE FROM sys_user WHERE username = ?", "adapter-machine-account");
        // center_agent.created_by 引用操作者，所以必须先删中心代理再删该用户；
        // 而 center_agent 又被 agent.center_agent_id 引用，故 agent 必须更早删除（见上）。
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", CENTER_CODE);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", "adapter-center-admin");
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", "adapter-center-admin");
        jdbc.update("DELETE FROM sys_user WHERE username = ?", "adapter-center-admin");
    }
}
