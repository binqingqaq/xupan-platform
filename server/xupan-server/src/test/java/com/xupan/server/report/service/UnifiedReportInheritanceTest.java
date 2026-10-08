package com.xupan.server.report.service;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.playerauth.service.PlayerLinkTokenCipher;
import com.xupan.server.report.repository.ReportConfigRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一飞单（子账号级飞单配置）只读回退的回归测试。
 *
 * <p><b>BY220 依据</b>：子账号（{@code x_admin}）持有统一飞单凭据——{@code feidanname}（飞单账号）、
 * {@code feidanpwd}（飞单密码）、{@code feidanid}（网盘 → {@code x_rbwp}）、{@code feidantype}（盘口），
 * 见 {@code admin/controller/Users.php:42-162}、{@code admin/view/sub/form.html:48-77}、
 * {@code admin/view/sub/list.html:57}；机器（{@code x_robot} / {@code x_rbfly.flyers_*}）另有自己的
 * 飞单字段与运行状态。本项目对应列为 V35 的 {@code agent_group.unified_report_enabled /
 * report_username / report_password_ciphertext / report_network_code / report_route_code}
 * 与 V56/V59 的 {@code agent.report_*}。</p>
 *
 * <p>覆盖四条规则：</p>
 * <ol>
 *   <li>机器自身没有飞单账号 + 子账号开启统一飞单且配好账号 → 读回子账号的账号/网盘/盘口，
 *       {@code inheritedFromGroup = true}；</li>
 *   <li>机器自身有飞单账号 → 机器优先，{@code inheritedFromGroup = false}；</li>
 *   <li>子账号未开启统一飞单（或开启但未配账号）→ 不回退，读到的就是机器自身的空配置；</li>
 *   <li>回退是只读的：{@code agent.report_username} / {@code report_password_ciphertext}
 *       绝不被写回（{@code uk_agent_report_username} 唯一约束 + 一台机器一个上游账号原则）。</li>
 * </ol>
 *
 * <p>V73 起**报网类型也走统一飞单**：BY220 机器的 {@code x_robot.flyers_type} 由所选网盘的
 * {@code code} 决定（{@code rbt/controller/Index.php:745}），而网盘表 {@code x_rbwp} 没有类型列
 * （{@code admin/controller/Index.php:42-98}），本项目把"网盘编码"与"报网类型"拆成
 * {@code agent_group.report_network_code} 与 {@code agent_group.report_type} 两列；继承态取子账号的
 * {@code report_type}，子账号未设置时回退机器自身的 {@code agent.report_type}（见用例 6、7）。</p>
 *
 * <p>合规边界：全部本地数据，不发起任何网络请求；测试里出现的飞单账号是编造的，
 * 飞单密码由本机加密组件生成密文，只以 {@code hasPassword} 布尔参与断言，不打印明文或密文。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class UnifiedReportInheritanceTest {

    private static final String ADMIN = "ufinh-admin";
    private static final String PASSWORD = "UnifiedReportTest123";

    /** 子账号（{@code agent_group}，统一飞单归属来源）。 */
    private static final String GROUP_ALPHA_CODE = "UFINH_GROUP_ALPHA";
    private static final String GROUP_BETA_CODE = "UFINH_GROUP_BETA";
    private static final String GROUP_GAMMA_CODE = "UFINH_GROUP_GAMMA";
    /** 子账号 Delta：开启统一飞单、配好账号，并且**设置了报网类型**（V73）。 */
    private static final String GROUP_DELTA_CODE = "UFINH_GROUP_DELTA";

    /** 机器账号（{@code agent} 行）。 */
    private static final String MACHINE_A_USER = "ufinh-machine-a";
    private static final String MACHINE_B_USER = "ufinh-machine-b";
    private static final String MACHINE_C_USER = "ufinh-machine-c";
    private static final String MACHINE_D_USER = "ufinh-machine-d";
    private static final String MACHINE_E_USER = "ufinh-machine-e";
    private static final String MACHINE_F_USER = "ufinh-machine-f";
    private static final String MACHINE_A_CODE = "UFINH_A";
    private static final String MACHINE_B_CODE = "UFINH_B";
    private static final String MACHINE_C_CODE = "UFINH_C";
    private static final String MACHINE_D_CODE = "UFINH_D";
    private static final String MACHINE_E_CODE = "UFINH_E";
    private static final String MACHINE_F_CODE = "UFINH_F";

    /** 子账号统一飞单里的假飞单账号（不对应任何真实上游）。 */
    private static final String GROUP_REPORT_USERNAME = "ufinh-unified-fly-account";
    private static final String GROUP_REPORT_NETWORK = "UFNET_A";
    private static final String GROUP_REPORT_ROUTE = "B";
    /** 子账号 Delta 统一飞单的报网类型（V73 {@code agent_group.report_type}）。 */
    private static final String GROUP_DELTA_REPORT_TYPE = "K28";
    /** 机器 B 自己的飞单账号，用于验证"机器优先"。 */
    private static final String MACHINE_B_REPORT_USERNAME = "ufinh-machine-b-fly-account";
    /** 机器 A 自己的飞单账号（不等于子账号统一飞单账号），用于验证"机器专属仍然允许"。 */
    private static final String MACHINE_A_OWN_REPORT_USERNAME = "ufinh-machine-a-own-fly-account";
    /** 只在本测试内出现、不对应任何真实凭据的假飞单密码；只用于切换 hasPassword 真假。 */
    private static final String INVENTED_REPORT_SECRET = "UnifiedReportInventedSecret123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private PlayerLinkTokenCipher passwordCipher;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportConfigRepository reportConfigRepository;

    private long adminId;
    private long groupAlphaId;
    private long groupBetaId;
    private long groupGammaId;
    private long groupDeltaId;
    private long machineAId;
    private long machineBId;
    private long machineCId;
    private long machineDId;
    private long machineEId;
    private long machineFId;
    /** 子账号 Alpha 已存的密文；只用于断言它没有被改写，不做任何输出。 */
    private String groupAlphaCiphertext;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "统一飞单测试管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        // 子账号 Alpha：开启统一飞单并配好账号/网盘/盘口（BY220 x_admin.feidan*），未设置报网类型。
        groupAlphaId = createSubAccount(GROUP_ALPHA_CODE, "统一飞单子账号A", true,
                GROUP_REPORT_USERNAME, GROUP_REPORT_NETWORK, GROUP_REPORT_ROUTE, null);
        // 子账号 Beta：未开启统一飞单，但配了账号 → 必须不回退。
        groupBetaId = createSubAccount(GROUP_BETA_CODE, "统一飞单子账号B", false,
                GROUP_REPORT_USERNAME, GROUP_REPORT_NETWORK, GROUP_REPORT_ROUTE, null);
        // 子账号 Gamma：开启了统一飞单但没配账号 → 也不回退。
        groupGammaId = createSubAccount(GROUP_GAMMA_CODE, "统一飞单子账号C", true, null, null, null, null);
        // 子账号 Delta（V73）：开启统一飞单、配好账号，并显式设置报网类型。
        groupDeltaId = createSubAccount(GROUP_DELTA_CODE, "统一飞单子账号D", true,
                GROUP_REPORT_USERNAME, GROUP_REPORT_NETWORK, GROUP_REPORT_ROUTE, GROUP_DELTA_REPORT_TYPE);

        // 子账号飞单密码目前没有对外写入入口（见交付报告），这里直接落密文以验证 hasPassword 分支。
        groupAlphaCiphertext = passwordCipher.encrypt(INVENTED_REPORT_SECRET);
        jdbc.update("UPDATE agent_group SET report_password_ciphertext = ? WHERE id = ?",
                groupAlphaCiphertext, groupAlphaId);

        machineAId = createMachine(MACHINE_A_CODE, MACHINE_A_USER, "统一飞单机器A", groupAlphaId);
        machineBId = createMachine(MACHINE_B_CODE, MACHINE_B_USER, "统一飞单机器B", groupAlphaId);
        machineCId = createMachine(MACHINE_C_CODE, MACHINE_C_USER, "统一飞单机器C", groupBetaId);
        machineDId = createMachine(MACHINE_D_CODE, MACHINE_D_USER, "统一飞单机器D", groupAlphaId);
        machineEId = createMachine(MACHINE_E_CODE, MACHINE_E_USER, "统一飞单机器E", groupGammaId);
        machineFId = createMachine(MACHINE_F_CODE, MACHINE_F_USER, "统一飞单机器F", groupDeltaId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /**
     * 用例 1：机器无自身飞单账号 + 子账号开启统一飞单且有账号 → 账号/网盘/盘口回退到子账号，
     * {@code inheritedFromGroup = true}；自动上报/失败退单/金额/登录时间/上游地址仍取机器自身。
     *
     * <p>报网类型（V73）也属于统一飞单：子账号 Alpha 未设置类型时回退机器自身的 {@code TT}，
     * 所以这里读回的是机器自己的值（子账号设置了类型则取子账号的值，见用例 6）。</p>
     */
    @Test
    void machineWithoutOwnReportAccountInheritsSubAccountUnifiedReport() throws Exception {
        // 只写机器自己的报网类型与开关，不写飞单账号 → 读取时应回退到子账号统一飞单。
        ReportService.ReportConfigView saved = reportService.saveConfig(adminId, machineAId,
                new ReportService.ReportConfigUpdate("TT", true, true, null, null, null, null, null));

        assertThat(saved.inheritedFromGroup()).isTrue();
        assertThat(saved.reportUsername()).isEqualTo(GROUP_REPORT_USERNAME);
        assertThat(saved.reportNetworkCode()).isEqualTo(GROUP_REPORT_NETWORK);
        assertThat(saved.reportRouteCode()).isEqualTo(GROUP_REPORT_ROUTE);
        assertThat(saved.hasPassword()).isTrue();
        // 类型回退到机器自身（子账号未设置类型）；自动上报/失败退单/金额/最近登录时间/上游地址取机器自身。
        assertThat(saved.reportType()).isEqualTo("TT");
        assertThat(saved.reportAuto()).isTrue();
        assertThat(saved.reportWithdraw()).isTrue();
        assertThat(saved.reportBalance()).isEqualByComparingTo("0.00");
        assertThat(saved.reportStake()).isEqualByComparingTo("0.00");
        assertThat(saved.reportLastLoginAt()).isNull();
        assertThat(saved.reportUrl()).isNull();

        // 仓储两个读取入口（findConfig / findConfigByAgent）都必须走同一条回退。
        ReportConfigRepository.ReportConfigRow row = reportConfigRepository.findConfig(machineAId).orElseThrow();
        assertThat(row.inheritedFromGroup()).isTrue();
        assertThat(row.reportUsername()).isEqualTo(GROUP_REPORT_USERNAME);
        assertThat(row.reportNetworkCode()).isEqualTo(GROUP_REPORT_NETWORK);
        assertThat(row.reportRouteCode()).isEqualTo(GROUP_REPORT_ROUTE);
        assertThat(row.hasPassword()).isTrue();
        assertThat(reportConfigRepository.findConfigByAgent(machineAId).orElseThrow().inheritedFromGroup()).isTrue();

        // 只读回退：机器自身列仍为空。
        assertThat(ownReportUsername(machineAId)).isNull();
        assertThat(ownReportPasswordCiphertext(machineAId)).isNull();

        // 接口响应字段：/api/admin/player-desk/report 暴露 inheritedFromGroup。
        // 阶段 3 起该接口只认机器账号（BY220 robot.php），因此由机器 A 自己读取自己的配置。
        String token = login(MACHINE_A_USER, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/report")
                        .param("machineId", Long.toString(machineAId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.inheritedFromGroup").value(true))
                .andExpect(jsonPath("$.reportUsername").value(GROUP_REPORT_USERNAME))
                .andExpect(jsonPath("$.reportNetworkCode").value(GROUP_REPORT_NETWORK))
                .andExpect(jsonPath("$.reportRouteCode").value(GROUP_REPORT_ROUTE))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.reportType").value("TT"));
    }

    /** 用例 2：机器有自身飞单账号 → 机器优先，{@code inheritedFromGroup = false}。 */
    @Test
    void machineOwnReportConfigWinsOverSubAccountUnifiedReport() {
        reportService.saveConfig(adminId, machineBId, new ReportService.ReportConfigUpdate(
                "K28", false, false, MACHINE_B_REPORT_USERNAME, INVENTED_REPORT_SECRET, null, "MNET_B", "C"));

        ReportConfigRepository.ReportConfigRow row = reportConfigRepository.findConfig(machineBId).orElseThrow();
        assertThat(row.inheritedFromGroup()).isFalse();
        assertThat(row.reportUsername()).isEqualTo(MACHINE_B_REPORT_USERNAME);
        assertThat(row.reportNetworkCode()).isEqualTo("MNET_B");
        assertThat(row.reportRouteCode()).isEqualTo("C");
        assertThat(row.hasPassword()).isTrue();
        assertThat(row.reportType()).isEqualTo("K28");
        assertThat(row.reportAuto()).isFalse();

        // 机器 B 的配置落在自己行上，与子账号统一飞单互不影响。
        assertThat(ownReportUsername(machineBId)).isEqualTo(MACHINE_B_REPORT_USERNAME);
        assertThat(ownReportNetworkCode(machineBId)).isEqualTo("MNET_B");

        // 同组的机器 A 仍然走子账号回退（机器 B 写自己的账号不会改变别人的归属）。
        assertThat(reportConfigRepository.findConfig(machineAId).orElseThrow().inheritedFromGroup()).isTrue();
    }

    /** 用例 3：子账号未开启统一飞单（或开启但未配账号）→ 不回退，返回机器自身的空配置。 */
    @Test
    void subAccountWithoutUnifiedReportDoesNotFallBackToGroupConfig() {
        // 子账号 Beta 配了账号但 unified_report_enabled = false。
        ReportConfigRepository.ReportConfigRow beta = reportConfigRepository.findConfig(machineCId).orElseThrow();
        assertThat(beta.inheritedFromGroup()).isFalse();
        assertThat(beta.reportUsername()).isNull();
        assertThat(beta.reportNetworkCode()).isNull();
        assertThat(beta.reportRouteCode()).isNull();
        assertThat(beta.hasPassword()).isFalse();
        assertThat(beta.reportType()).isNull();
        assertThat(beta.reportAuto()).isFalse();
        assertThat(beta.reportOnline()).isFalse();
        assertThat(beta.reportWithdraw()).isFalse();
        assertThat(reportService.config(adminId, machineCId).inheritedFromGroup()).isFalse();

        // 子账号 Gamma 开启了统一飞单但没配飞单账号 → 同样不回退。
        ReportConfigRepository.ReportConfigRow gamma = reportConfigRepository.findConfig(machineEId).orElseThrow();
        assertThat(gamma.inheritedFromGroup()).isFalse();
        assertThat(gamma.reportUsername()).isNull();
        assertThat(gamma.reportNetworkCode()).isNull();
        assertThat(gamma.reportRouteCode()).isNull();
        assertThat(gamma.hasPassword()).isFalse();
    }

    /**
     * 用例 4：只读回退不得把子账号账号/密码复制进 {@code agent} 行。
     *
     * <p>同一子账号下两台机器读到同一个统一飞单账号，但两台机器的
     * {@code agent.report_username} 都必须保持为空——复制会直接撞
     * {@code uk_agent_report_username}（V59），并破坏"一台机器一个上游账号"原则（原则 8）。</p>
     */
    @Test
    void neverCopiesSubAccountCredentialIntoMachineRow() {
        ReportConfigRepository.ReportConfigRow a = reportConfigRepository.findConfig(machineAId).orElseThrow();
        ReportConfigRepository.ReportConfigRow d = reportConfigRepository.findConfig(machineDId).orElseThrow();
        assertThat(a.inheritedFromGroup()).isTrue();
        assertThat(d.inheritedFromGroup()).isTrue();
        assertThat(a.reportUsername()).isEqualTo(GROUP_REPORT_USERNAME);
        assertThat(d.reportUsername()).isEqualTo(GROUP_REPORT_USERNAME);

        assertThat(ownReportUsername(machineAId)).isNull();
        assertThat(ownReportUsername(machineDId)).isNull();
        assertThat(countAgentsWithReportUsername(GROUP_REPORT_USERNAME)).isZero();

        // 只写机器自己的报网类型（"保持原值"合并必须以机器自身列为基准，
        // 不得把子账号的飞单账号/网盘补进机器行）。
        reportService.saveConfig(adminId, machineAId,
                new ReportService.ReportConfigUpdate("TT", null, null, null, null, null, null, null));
        assertThat(ownReportUsername(machineAId)).isNull();
        assertThat(ownReportNetworkCode(machineAId)).isNull();
        assertThat(ownReportPasswordCiphertext(machineAId)).isNull();

        // 继承来的账号/网盘/盘口 + 机器自己的类型足以满足"本地模拟登录"的前置条件
        // （统一飞单因此真正生效），登录只改机器自己的在线位与登录时间，不搬运任何凭据。
        ReportService.ReportConfigView online = reportService.login(adminId, machineAId);
        assertThat(online.reportOnline()).isTrue();
        assertThat(online.inheritedFromGroup()).isTrue();
        assertThat(online.reportUsername()).isEqualTo(GROUP_REPORT_USERNAME);
        assertThat(online.reportLastLoginAt()).isNotNull();
        assertThat(ownReportUsername(machineAId)).isNull();
        assertThat(ownReportPasswordCiphertext(machineAId)).isNull();

        // "保持原值"的保存合并必须以机器自身列为基准：只改盘口线路时，不能把子账号的
        // 飞单账号/网盘写进机器行；显式写入的盘口只落机器自身列，读取仍按继承规则取子账号盘口。
        reportService.saveConfig(adminId, machineAId,
                new ReportService.ReportConfigUpdate(null, null, null, null, null, null, null, "A"));
        assertThat(ownReportUsername(machineAId)).isNull();
        assertThat(ownReportPasswordCiphertext(machineAId)).isNull();
        assertThat(ownReportNetworkCode(machineAId)).isNull();
        assertThat(reportConfigRepository.findConfig(machineAId).orElseThrow().reportRouteCode())
                .isEqualTo(GROUP_REPORT_ROUTE);

        // 子账号的凭据列未被本次读取/保存改写。
        assertThat(jdbc.queryForObject("SELECT report_password_ciphertext FROM agent_group WHERE id = ?",
                String.class, groupAlphaId)).isEqualTo(groupAlphaCiphertext);
    }

    /**
     * 用例 5（工头验收 D1 回归）：**不能把子账号的统一飞单账号写成本机自有账号**。
     *
     * <p>机器端「设置报网账号」弹窗曾经把继承值回填并原样提交，于是机器 A 把子账号的共享账号写成自己的，
     * 兄弟机器 B 立刻报 {@code REPORT_USERNAME_TAKEN}（{@code uk_agent_report_username}），
     * 机器 A 也不再跟随子账号轮换。这里固定接口层的兜底：显式提交等于本组统一飞单账号 → 409，
     * 且机器行与子账号行都不被改动；留空（null）仍然表示"继续沿用"。</p>
     */
    @Test
    void rejectsWritingInheritedUnifiedReportAccountAsMachineOwn() {
        // 前置：机器 A 处于继承态
        assertThat(reportConfigRepository.findConfig(machineAId).orElseThrow().inheritedFromGroup()).isTrue();

        assertThatThrownBy(() -> reportService.saveConfig(adminId, machineAId,
                new ReportService.ReportConfigUpdate("TT", null, null, GROUP_REPORT_USERNAME, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "REPORT_USERNAME_INHERITED");
        assertThat(ownReportUsername(machineAId)).isNull();
        assertThat(ownReportPasswordCiphertext(machineAId)).isNull();
        assertThat(countAgentsWithReportUsername(GROUP_REPORT_USERNAME)).isZero();
        // 子账号仍是唯一持有者，兄弟机器读取不受影响
        assertThat(reportConfigRepository.findConfig(machineDId).orElseThrow().inheritedFromGroup()).isTrue();

        // 留空（null）＝继续沿用，不报错也不写回
        reportService.saveConfig(adminId, machineAId,
                new ReportService.ReportConfigUpdate("TT", null, null, null, null, null, null, null));
        assertThat(ownReportUsername(machineAId)).isNull();
        assertThat(reportConfigRepository.findConfig(machineAId).orElseThrow().inheritedFromGroup()).isTrue();

        // 机器专属账号（不等于子账号统一飞单账号）仍然允许，机器优先
        reportService.saveConfig(adminId, machineAId, new ReportService.ReportConfigUpdate(
                "TT", null, null, MACHINE_A_OWN_REPORT_USERNAME, INVENTED_REPORT_SECRET, null, "MNET_A", "C"));
        ReportConfigRepository.ReportConfigRow own = reportConfigRepository.findConfig(machineAId).orElseThrow();
        assertThat(own.inheritedFromGroup()).isFalse();
        assertThat(own.reportUsername()).isEqualTo(MACHINE_A_OWN_REPORT_USERNAME);
    }

    /**
     * 用例 6（V73）：**报网类型也走统一飞单**——继承态取子账号的 {@code agent_group.report_type}；
     * 子账号未设置（{@code NULL}）时回退机器自身的 {@code agent.report_type}。
     *
     * <p>BY220 依据：机器的 {@code x_robot.flyers_type} 由所选网盘的 {@code code} 决定
     * （{@code rbt/controller/Index.php:745}），而网盘表 {@code x_rbwp} 没有类型列
     * （{@code admin/controller/Index.php:42-98}）；本项目把"网盘编码"与"报网类型"拆成两列，
     * 因此在子账号侧显式保存统一飞单的类型。回退那条分支保证"原本能登录的机器不会因为
     * 子账号没设类型而变成配置不完整"。</p>
     */
    @Test
    void inheritsReportTypeFromSubAccountAndFallsBackToMachineOwnType() throws Exception {
        // 子账号 Delta 设了 K28、机器 F 自身没有配置 → 继承态读回 K28，机器自身列仍为空（只读回退）。
        ReportConfigRepository.ReportConfigRow f = reportConfigRepository.findConfig(machineFId).orElseThrow();
        assertThat(f.inheritedFromGroup()).isTrue();
        assertThat(f.reportType()).isEqualTo(GROUP_DELTA_REPORT_TYPE);
        assertThat(f.ownReportType()).isNull();
        assertThat(ownReportType(machineFId)).isNull();

        // 子账号 Alpha 未设置报网类型：机器 A 写了自己的 TT 后，继承态**回退**到机器自身的 TT。
        reportService.saveConfig(adminId, machineAId,
                new ReportService.ReportConfigUpdate("TT", null, null, null, null, null, null, null));
        ReportConfigRepository.ReportConfigRow a = reportConfigRepository.findConfig(machineAId).orElseThrow();
        assertThat(a.inheritedFromGroup()).isTrue();
        assertThat(a.reportType()).isEqualTo("TT");
        assertThat(a.ownReportType()).isEqualTo("TT");

        // 接口响应同样透出继承来的类型（阶段 3 起由机器 F 自己读取自己的配置）。
        String token = login(MACHINE_F_USER, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/report")
                        .param("machineId", Long.toString(machineFId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inheritedFromGroup").value(true))
                .andExpect(jsonPath("$.reportType").value(GROUP_DELTA_REPORT_TYPE));

        // 子账号侧的类型只读：机器行没有被写入，子账号行的值也没被改写。
        assertThat(ownReportType(machineFId)).isNull();
        assertThat(jdbc.queryForObject("SELECT report_type FROM agent_group WHERE id = ?",
                String.class, groupDeltaId)).isEqualTo(GROUP_DELTA_REPORT_TYPE);
    }

    /**
     * 用例 7（V73）：保存机器其它字段时，"保持原值"的合并基准必须是**机器自身列**。
     *
     * <p>否则一次"只改盘口线路/只改报网账号"的保存会把子账号统一飞单的类型复制进
     * {@code agent.report_type}（静默漂移：机器从此不再跟随子账号轮换），
     * 或者把机器自己原有的类型冲成 {@code NULL}（让回退分支失效、机器变成配置不完整）。</p>
     */
    @Test
    void savingOtherMachineFieldsNeverCopiesSubAccountReportTypeIntoMachineRow() {
        // 机器 F 继承子账号 Delta 的 K28；自身类型为空时，保存其它字段不得把 K28 写进机器行。
        reportService.saveConfig(adminId, machineFId,
                new ReportService.ReportConfigUpdate(null, null, null, null, null, null, null, "A"));
        assertThat(ownReportType(machineFId)).isNull();
        assertThat(reportConfigRepository.findConfig(machineFId).orElseThrow().reportType())
                .isEqualTo(GROUP_DELTA_REPORT_TYPE);

        // 机器 F 显式写了自身类型 WNS（读取仍按继承规则取子账号的 K28）；
        // 之后只改盘口线路的保存必须保留机器自身列，既不能被 K28 覆盖，也不能被冲成 NULL。
        reportService.saveConfig(adminId, machineFId,
                new ReportService.ReportConfigUpdate("WNS", null, null, null, null, null, null, null));
        assertThat(ownReportType(machineFId)).isEqualTo("WNS");
        assertThat(reportConfigRepository.findConfig(machineFId).orElseThrow().reportType())
                .isEqualTo(GROUP_DELTA_REPORT_TYPE);

        reportService.saveConfig(adminId, machineFId,
                new ReportService.ReportConfigUpdate(null, null, null, null, null, null, null, "B"));
        assertThat(ownReportType(machineFId)).isEqualTo("WNS");
        assertThat(reportConfigRepository.findConfig(machineFId).orElseThrow().reportType())
                .isEqualTo(GROUP_DELTA_REPORT_TYPE);

        // 显式改类型仍然生效，并且只写机器自身列。
        reportService.saveConfig(adminId, machineFId,
                new ReportService.ReportConfigUpdate("ls8899", null, null, null, null, null, null, null));
        assertThat(ownReportType(machineFId)).isEqualTo("LS8899");
        assertThat(reportConfigRepository.findConfig(machineFId).orElseThrow().reportType())
                .isEqualTo(GROUP_DELTA_REPORT_TYPE);
    }

    /**
     * 用例 8（V74）：继承态机器的"报网登录 / 退出"驱动子账号的统一飞单上游状态
     * （{@code agent_group.report_online / report_balance / report_stake}）。
     *
     * <p><b>BY220 依据</b>：{@code console/Websocket.php:307-318} 上游返回成功时
     * {@code feidanonline=1}、{@code feidanmoney=上游余额}、{@code feidanbetting=上游未结算下注额}；
     * {@code Users.php:111-122}（被注释掉的 subLogin 分支）子账号登录上游成功 → {@code feidanonline=1}；
     * {@code Websocket.php:328-330} 重新登录失败 → {@code feidanonline=0}。</p>
     *
     * <p><b>本地模拟口径</b>：本项目不接真实上游、不发起任何网络请求，"机器报网登录成功"就是本地等价物，
     * 因此子账号的余额/下注同步为**那台机器**的 {@code agent.report_balance / report_stake}；
     * 退出只置离线并**保留**这两个读数（BY220 也只改 {@code feidanonline}）。</p>
     */
    @Test
    void inheritedMachineReportLoginSyncsSubAccountUpstreamState() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        // 机器 A 自身只有报网类型、没有飞单账号 → 处于继承态（跟随子账号 Alpha 的统一飞单）。
        reportService.saveConfig(adminId, machineAId,
                new ReportService.ReportConfigUpdate("TT", true, true, null, null, null, null, null));
        assertThat(reportConfigRepository.findConfig(machineAId).orElseThrow().inheritedFromGroup()).isTrue();
        // 机器侧本次"读到的上游余额 / 未结算下注"（本地模拟值，不发起任何请求）。
        jdbc.update("UPDATE agent SET report_balance = 1234.50, report_stake = 678.90 WHERE id = ?", machineAId);

        mockMvc.perform(post("/api/admin/player-desk/report/login")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(true));

        assertThat(subAccountReportOnline(groupAlphaId)).isTrue();
        assertThat(subAccountReportBalance(groupAlphaId)).isEqualByComparingTo("1234.50");
        assertThat(subAccountReportStake(groupAlphaId)).isEqualByComparingTo("678.90");

        mockMvc.perform(post("/api/admin/player-desk/report/logout")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportOnline").value(false));

        assertThat(subAccountReportOnline(groupAlphaId)).isFalse();
        assertThat(subAccountReportBalance(groupAlphaId)).isEqualByComparingTo("1234.50");
        assertThat(subAccountReportStake(groupAlphaId)).isEqualByComparingTo("678.90");
    }

    /**
     * 用例 9（V74）：**非继承态**（机器有自己的飞单账号）的登录/退出**不影响**子账号上游状态。
     *
     * <p>BY220 里这类机器的上游读数是机器自己的（{@code x_rbfly.flyers_online}），
     * 子账号的 {@code feidanonline / feidanmoney / feidanbetting} 只由子账号级统一飞单的读数驱动，
     * 因此这里机器 B（与机器 A 同属子账号 Alpha，但配了自己的飞单账号）登录后，
     * 子账号 Alpha 的三项状态必须原样不动。</p>
     */
    @Test
    void nonInheritedMachineReportLoginLeavesSubAccountUpstreamStateUntouched() throws Exception {
        String token = login(MACHINE_B_USER, PASSWORD);
        reportService.saveConfig(adminId, machineBId, new ReportService.ReportConfigUpdate(
                "K28", true, false, MACHINE_B_REPORT_USERNAME, INVENTED_REPORT_SECRET, null, "MNET_B", "C"));
        assertThat(reportConfigRepository.findConfig(machineBId).orElseThrow().inheritedFromGroup()).isFalse();
        jdbc.update("UPDATE agent SET report_balance = 99.00, report_stake = 11.00 WHERE id = ?", machineBId);
        // 前置：把子账号 Alpha 的上游状态设成一组可识别的值
        jdbc.update("""
                UPDATE agent_group SET report_online = FALSE, report_balance = 1.00, report_stake = 2.00
                 WHERE id = ?
                """, groupAlphaId);

        mockMvc.perform(post("/api/admin/player-desk/report/login")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"machineId\":" + machineBId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inheritedFromGroup").value(false))
                .andExpect(jsonPath("$.reportOnline").value(true));

        assertThat(subAccountReportOnline(groupAlphaId)).isFalse();
        assertThat(subAccountReportBalance(groupAlphaId)).isEqualByComparingTo("1.00");
        assertThat(subAccountReportStake(groupAlphaId)).isEqualByComparingTo("2.00");

        // 退出同样不碰子账号状态
        mockMvc.perform(post("/api/admin/player-desk/report/logout")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"machineId\":" + machineBId + "}"))
                .andExpect(status().isOk());
        assertThat(subAccountReportOnline(groupAlphaId)).isFalse();
        assertThat(subAccountReportBalance(groupAlphaId)).isEqualByComparingTo("1.00");
        assertThat(subAccountReportStake(groupAlphaId)).isEqualByComparingTo("2.00");
    }

    private long createSubAccount(String groupCode, String displayName, boolean unifiedReportEnabled,
                                  String reportUsername, String reportNetworkCode, String reportRouteCode,
                                  String reportType) {
        // 直接落 agent_group：本用例只验证读取侧的"统一飞单"回退规则，
        // 因此不依赖超级后台子账号创建接口（其在同一模块内仍在演进）。
        // reportType 是 V73 的 agent_group.report_type（统一飞单的报网类型），null = 子账号未设置。
        jdbc.update("""
                INSERT INTO agent_group
                    (group_code, display_name, status, created_by, unified_report_enabled,
                     report_username, report_network_code, report_route_code, report_type)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, groupCode, displayName, adminId, unifiedReportEnabled,
                reportUsername, reportNetworkCode, reportRouteCode, reportType);
        return jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, groupCode);
    }

    private long createMachine(String agentCode, String username, String displayName, long groupId) {
        return agentAdminService.createAgent(agentCode, username, displayName, PASSWORD, groupId, adminId).id();
    }

    private String ownReportUsername(long machineId) {
        return jdbc.queryForObject("SELECT report_username FROM agent WHERE id = ?", String.class, machineId);
    }

    private String ownReportNetworkCode(long machineId) {
        return jdbc.queryForObject("SELECT report_network_code FROM agent WHERE id = ?", String.class, machineId);
    }

    /** 机器**自身列** {@code agent.report_type} 的原始值（不做任何统一飞单回退）。 */
    private String ownReportType(long machineId) {
        return jdbc.queryForObject("SELECT report_type FROM agent WHERE id = ?", String.class, machineId);
    }

    private String ownReportPasswordCiphertext(long machineId) {
        return jdbc.queryForObject("SELECT report_password_ciphertext FROM agent WHERE id = ?",
                String.class, machineId);
    }

    /** 子账号的上游在线状态（V74 {@code agent_group.report_online}，BY220 {@code x_admin.feidanonline}）。 */
    private boolean subAccountReportOnline(long groupId) {
        Boolean value = jdbc.queryForObject("SELECT report_online FROM agent_group WHERE id = ?",
                Boolean.class, groupId);
        return Boolean.TRUE.equals(value);
    }

    /** 子账号的网盘余额读数（V74 {@code agent_group.report_balance}，BY220 {@code x_admin.feidanmoney}）。 */
    private java.math.BigDecimal subAccountReportBalance(long groupId) {
        return jdbc.queryForObject("SELECT report_balance FROM agent_group WHERE id = ?",
                java.math.BigDecimal.class, groupId);
    }

    /** 子账号的网盘下注读数（V74 {@code agent_group.report_stake}，BY220 {@code x_admin.feidanbetting}）。 */
    private java.math.BigDecimal subAccountReportStake(long groupId) {
        return jdbc.queryForObject("SELECT report_stake FROM agent_group WHERE id = ?",
                java.math.BigDecimal.class, groupId);
    }

    private long countAgentsWithReportUsername(String reportUsername) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM agent WHERE report_username = ?",
                Long.class, reportUsername);
        return total == null ? 0L : total;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** 清理本测试造出的子账号、机器与登录账号，避免给其它测试留脏数据。 */
    private void clean() {
        List<Long> groupIds = jdbc.queryForList(
                "SELECT id FROM agent_group WHERE group_code LIKE 'UFINH%'", Long.class);
        List<Long> machineIds = jdbc.queryForList(
                "SELECT id FROM agent WHERE agent_code LIKE 'UFINH%'", Long.class);
        List<Long> owners = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username LIKE 'ufinh-%'", Long.class));
        if (!machineIds.isEmpty()) {
            owners.addAll(jdbc.queryForList("SELECT account_user_id FROM agent WHERE id IN ("
                    + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray()));
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
        }
        if (!groupIds.isEmpty()) {
            owners.addAll(jdbc.queryForList("SELECT sys_user_id FROM agent_group WHERE id IN ("
                    + placeholders(groupIds.size()) + ")", Long.class, groupIds.toArray()));
            delete("DELETE FROM agent_ledger WHERE group_id IN (%s)", groupIds);
        }
        List<Long> userIds = owners.stream().filter(Objects::nonNull).distinct().toList();

        if (!userIds.isEmpty()) {
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", userIds);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM demo_user_account WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!groupIds.isEmpty()) {
            delete("DELETE FROM agent_group WHERE id IN (%s)", groupIds);
        }
        if (!userIds.isEmpty()) {
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_user WHERE id IN (%s)", userIds);
        }
    }

    private void delete(String sqlTemplate, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update(sqlTemplate.formatted(placeholders(ids.size())), ids.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(count, 1), "?"));
    }
}
