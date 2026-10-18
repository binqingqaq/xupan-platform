package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.chat.repository.PlayerPointRequestRepository;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.service.VirtualWalletService;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作台「上下分审批」（BY220 玩家聊天室发「上100」→ 后台点"同意"）。
 *
 * <p>BY220 归属：审批在代理后台（{@code application/rbt/controller/Index.php:252/258/298}
 * 的 {@code GetUpDownScoreReq / AcceptUpDownScoreReq / DenyUpDownScoreReq}），即**机器账号**的操作；
 * 超级管理后台 {@code admin.php} 没有玩家运营面。因此本用例的操作者统一是
 * **机器账号**（显式 {@code CONSOLE} 入口登录），超管/子账号调这些接口一律 403
 * （见 {@code PlayerDeskEntryPermissionTest}）。</p>
 *
 * <p>审批就是"给玩家上下分"，必须沿机器池流转：机器账号扣**自己那台**（BY220 {@code robot.php} 语义），
 * 托不联动；池不足时用 BY220 原文，且钱包/机器池/机器账本/申请单四者都不变。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerPointOperationControllerTest {

    private static final String ADMIN = "point-operation-admin";
    private static final String NORMAL = "point-operation-normal";
    private static final String BOT = "point-operation-bot";
    private static final String MACHINE_USER = "point-operation-machine";
    private static final String MACHINE_CODE = "POINT_OP_MACHINE";
    private static final String PASSWORD = "PointOperationPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private ChatMessageService chatMessageService;

    private long adminId;
    private long machineId;
    private long normalAccountId;
    private long botAccountId;
    private Instant now;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(ADMIN, "积分审批管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        // 审批由机器账号执行，扣的是**自己那台机器**的池：本用例自建一台测试机器并给足积分。
        // 刻意不碰 PLATFORM_DIRECT——测试 H2 是同 JVM 共享的内存库，改共享机器会污染其它用例。
        machineId = agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "积分审批机器", PASSWORD, null, adminId).id();
        jdbc.update("UPDATE agent SET score=1000.00 WHERE id=?", machineId);

        long normalId = userRepository.insert(NORMAL, "普通积分玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(normalId, "USER");
        normalAccountId = walletService.ensureWalletForUser(normalId, "普通积分玩家");

        long botId = userRepository.insert(BOT, "托积分玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(botId, "USER");
        botAccountId = walletService.ensureWalletForUser(botId, "托积分玩家");
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE id=?", botAccountId);

        // 两个夹具玩家都归属这台测试机器：机器账号的数据范围是 MACHINE，
        // 归属不对就会按"不存在"拒绝（这正是范围收敛的语义）
        jdbc.update("UPDATE demo_user_account SET agent_id=? WHERE id IN (?, ?)",
                machineId, normalAccountId, botAccountId);

        now = Instant.now();
        insertLedger(normalAccountId, "ADMIN_GRANT", "100.00", "0.00", "100.00", "N-1");
        insertLedger(normalAccountId, "ADMIN_ADJUST", "-25.00", "100.00", "75.00", "N-2");
        insertLedger(normalAccountId, "ADMIN_RESET", "-75.00", "75.00", "0.00", "N-RESET");
        insertLedger(botAccountId, "ADMIN_GRANT", "50.00", "0.00", "50.00", "B-1");
        jdbc.update("UPDATE demo_user_account SET balance=75.00 WHERE id=?", normalAccountId);
        jdbc.update("UPDATE demo_user_account SET balance=50.00 WHERE id=?", botAccountId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void returnsOnlyTopUpAndAdjustForRequestedPlayerKind() throws Exception {
        String machineToken = machineToken();

        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("NORMAL"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].displayName").value("普通积分玩家"))
                .andExpect(jsonPath("$.items[0].direction").value("DOWN"))
                .andExpect(jsonPath("$.items[0].amount").value(-25.00))
                .andExpect(jsonPath("$.items[1].direction").value("TOP_UP"))
                .andExpect(jsonPath("$.items[1].amount").value(100.00));

        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "BOT")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].displayName").value("托积分玩家"))
                .andExpect(jsonPath("$.items[0].direction").value("TOP_UP"));
    }

    @Test
    void supportsCursorPaginationAndRejectsUnauthorizedUsers() throws Exception {
        String machineToken = machineToken();

        MvcResult firstPage = mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL").param("limit", "1")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn();
        Number nextBeforeId = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextBeforeId");

        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL").param("limit", "1")
                        .param("beforeId", String.valueOf(nextBeforeId.longValue()))
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].direction").value("TOP_UP"));

        String normalToken = login(NORMAL, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL")
                        .header("Authorization", bearer(normalToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvesPendingRequestOnceAndRejectsWithoutChangingBalance() throws Exception {
        String machineToken = machineToken();
        long approvalId = insertPending(normalAccountId, "TOP_UP", "100.00", "approval-message-1");
        long rejectionId = insertPending(normalAccountId, "DOWN", "25.00", "rejection-message-1");

        mockMvc.perform(get("/api/admin/player-desk/point-requests")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].displayName").value("普通积分玩家"))
                .andExpect(jsonPath("$[0].requestType").value("TOP_UP"));

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + approvalId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"确认上分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // A1：审批上分必须扣玩家归属机器（1000 - 100 = 900）并写机器账本
        assertThat(machineScore()).isEqualByComparingTo("900.00");
        assertThat(lastMachineLedgerEntryType()).isEqualTo("PLAYER_TOP_UP");
        assertThat(lastMachineLedgerDirection()).isEqualTo("OUT");
        assertThat(lastMachineLedgerAmount()).isEqualByComparingTo("100.00");

        int ledgerCountAfterApproval = ledgerCount(normalAccountId);
        long machineLedgerCountAfterApproval = machineLedgerCount();
        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + approvalId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"重复确认\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(ledgerCount(normalAccountId))
                .isEqualTo(ledgerCountAfterApproval);
        assertThat(machineScore()).as("重复审批不得重复扣机器池").isEqualByComparingTo("900.00");
        assertThat(machineLedgerCount()).isEqualTo(machineLedgerCountAfterApproval);

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + rejectionId + "/reject")
                        .contentType("application/json").content("{\"reason\":\"取消下分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(ledgerCount(normalAccountId))
                .isEqualTo(ledgerCountAfterApproval);
        assertThat(machineScore()).as("驳回不得动机器池").isEqualByComparingTo("900.00");
        // 这条下分申请是 SQL 直接造的**旧口径夹具**（创建时没有预扣行），
        // 因此拒绝时不得退回——否则等于凭空给玩家加钱。新口径的退回见
        // chatDownRequestRejectionRefundsHeldScoreExactlyOnce。
        assertThat(ledgerCountByKey(refundKey(rejectionId))).isZero();
        assertThat(walletService.getForAdminHistory(
                        userRepository.findByUsername(NORMAL).orElseThrow().id()).balance())
                .isEqualByComparingTo("175.00");
    }

    /** 机器账号审批上分 → 扣**自己那台机器**的池、钱包加分、机器账本写 {@code PLAYER_TOP_UP}。 */
    @Test
    void approvalTopUpDebitsMachinePool() throws Exception {
        String machineToken = machineToken();
        long requestId = insertPending(normalAccountId, "TOP_UP", "30.00", "pool-approval-topup-1");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意上分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(machineScore()).isEqualByComparingTo("970.00");
        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("105.00");
        assertThat(lastMachineLedgerEntryType()).isEqualTo("PLAYER_TOP_UP");
        assertThat(lastMachineLedgerDirection()).isEqualTo("OUT");
        assertThat(lastMachineLedgerAmount()).isEqualByComparingTo("30.00");
        assertThat(machineLedgerCount()).isEqualTo(1L);
        assertThat(requestStatus(requestId)).isEqualTo("APPROVED");
        assertThat(approvedFeedbackCount(requestId)).isEqualTo(1);
        assertThat(approvedFeedbackContent(requestId)).isEqualTo("@普通积分玩家，成功上分30");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"重复同意\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk());
        assertThat(approvedFeedbackCount(requestId)).as("重复审批不得重复发布成功消息").isEqualTo(1);
    }

    /**
     * 机器账号审批下分 → **只回补机器池，玩家余额不再变动**（BY220 {@code rbt/Index.php:280-286}：
     * 下分的钱在申请创建时已被 {@code common.php:1874-1876} 扣走）。
     *
     * <p>本用例直接用 SQL 造 PENDING 下分申请（**旧口径夹具：没有预扣行**），因此这里同时固定住
     * 「同意下分绝不因为夹具缺少预扣而反向改玩家余额」——机器池 +25，钱包一动不动。</p>
     */
    @Test
    void approvalDownCreditsMachinePoolBack() throws Exception {
        String machineToken = machineToken();
        long requestId = insertPending(normalAccountId, "DOWN", "25.00", "pool-approval-down-1");
        int walletLedgerBefore = ledgerCount(normalAccountId);

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意下分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(machineScore()).isEqualByComparingTo("1025.00");
        assertThat(balanceOf(normalAccountId)).as("同意下分不得再动玩家余额").isEqualByComparingTo("75.00");
        assertThat(ledgerCount(normalAccountId)).isEqualTo(walletLedgerBefore);
        assertThat(lastMachineLedgerEntryType()).isEqualTo("PLAYER_DOWN");
        assertThat(lastMachineLedgerDirection()).isEqualTo("IN");
        assertThat(lastMachineLedgerAmount()).isEqualByComparingTo("25.00");
        assertThat(machineLedgerCount()).isEqualTo(1L);
    }

    /**
     * 机器账号池不足：409 + **BY220 原文文案**（{@code rbt/Index.php:618-620}），
     * 且**钱包不变、机器池不变、机器账本不写、申请单仍是 PENDING**
     * （不能出现"钱包加了但申请单没批"或反之）。
     */
    @Test
    void approvalWithInsufficientMachinePoolWritesNothing() throws Exception {
        String machineToken = machineToken();
        jdbc.update("UPDATE agent SET score=5.00 WHERE id=?", machineId);
        long requestId = insertPending(normalAccountId, "TOP_UP", "30.00", "pool-approval-insufficient-1");
        int walletLedgerBefore = ledgerCount(normalAccountId);

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意上分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_SCORE_INSUFFICIENT"))
                // 收紧为仅机器账号之后，只可能产生机器端文案；
                // 平台侧那句"该玩家所属机器的积分不足以完成本次上分！"随 GLOBAL/GROUP 链路一起不可达。
                .andExpect(jsonPath("$.message").value("您的账号剩余积分不足以完成本次上分！"));

        assertThat(machineScore()).isEqualByComparingTo("5.00");
        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("75.00");
        assertThat(ledgerCount(normalAccountId)).isEqualTo(walletLedgerBefore);
        assertThat(machineLedgerCount()).isZero();
        assertThat(requestStatus(requestId)).isEqualTo("PENDING");
    }

    /**
     * 机器账号（MACHINE 范围）审批本机玩家 → 扣**自己那台**机器；
     * 池不足时文案是 BY220 原文（收紧后这是唯一可达的池不足文案）。
     */
    @Test
    void machineScopeApprovalDebitsItsOwnMachineAndKeepsBy220Message() throws Exception {
        String machineToken = machineToken();
        long okRequestId = insertPending(normalAccountId, "TOP_UP", "20.00", "pool-approval-machine-1");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + okRequestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"机器同意上分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(machineScore()).isEqualByComparingTo("980.00");
        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("95.00");
        assertThat(lastMachineLedgerEntryType()).isEqualTo("PLAYER_TOP_UP");
        assertThat(lastMachineLedgerAmount()).isEqualByComparingTo("20.00");

        jdbc.update("UPDATE agent SET score=5.00 WHERE id=?", machineId);
        long failRequestId = insertPending(normalAccountId, "TOP_UP", "20.00", "pool-approval-machine-2");
        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + failRequestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"机器同意上分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_SCORE_INSUFFICIENT"))
                .andExpect(jsonPath("$.message").value("您的账号剩余积分不足以完成本次上分！"));

        assertThat(machineScore()).isEqualByComparingTo("5.00");
        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("95.00");
        assertThat(machineLedgerCount()).isEqualTo(1L);
        assertThat(requestStatus(failRequestId)).isEqualTo("PENDING");
    }

    /** 同一 requestId 重复审批不重复扣池（申请单已 APPROVED 时直接返回，钱包与机器池都不再动）。 */
    @Test
    void repeatedApprovalDoesNotDoubleDebitPool() throws Exception {
        String machineToken = machineToken();
        long requestId = insertPending(normalAccountId, "TOP_UP", "30.00", "pool-approval-idempotent-1");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意上分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk());
        int walletLedgerAfterFirst = ledgerCount(normalAccountId);

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"再次同意\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(machineScore()).isEqualByComparingTo("970.00");
        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("105.00");
        assertThat(ledgerCount(normalAccountId)).isEqualTo(walletLedgerAfterFirst);
        assertThat(machineLedgerCount()).isEqualTo(1L);
    }

    /** 机器账号审批**托**的上分：钱包加分但机器池不变（BY220 对托不动机器积分）。 */
    @Test
    void approvalForBotDoesNotTouchMachinePool() throws Exception {
        String machineToken = machineToken();
        long requestId = insertPending(botAccountId, "TOP_UP", "40.00", "pool-approval-bot-1");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意给托上分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(machineScore()).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(botAccountId)).isEqualByComparingTo("90.00");
        assertThat(machineLedgerCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 下分申请「创建即扣、拒绝退回」严格对齐 BY220（计划 §3.2 / §5 的 REQ 用例）
    // ------------------------------------------------------------------

    /**
     * 切片 1（创建即扣）+ 切片 2（同意只加机器池）。
     *
     * <p>BY220 {@code application/common.php:1874-1876}：下分申请创建时
     * {@code db('rbuser')->...->setDec('score', $last)}；{@code application/rbt/controller/Index.php:280-286}：
     * 同意时只 {@code robot.setInc('score', ...)}（普才），玩家余额不再变动。</p>
     */
    @Test
    void chatDownRequestHoldsBalanceImmediatelyAndApprovalOnlyCreditsPool() throws Exception {
        long normalUserId = userId(NORMAL);
        int walletLedgerBefore = ledgerCount(normalAccountId);

        sendChatCommand(NORMAL, "chat-down-hold-1", "下25");

        assertThat(balanceOf(normalAccountId)).as("创建下分申请立即扣玩家余额").isEqualByComparingTo("50.00");
        assertThat(ledgerCount(normalAccountId)).as("必须写一条下分预扣流水").isEqualTo(walletLedgerBefore + 1);
        long requestId = requestIdOf(normalUserId, "chat-down-hold-1");
        assertThat(requestStatus(requestId)).isEqualTo("PENDING");
        String holdKey = holdKey(requestId);
        assertThat(ledgerOperationType(normalAccountId, holdKey)).isEqualTo("ADMIN_ADJUST");
        assertThat(ledgerAmount(normalAccountId, holdKey)).isEqualByComparingTo("-25.00");
        assertThat(ledgerBalanceAfter(normalAccountId, holdKey)).isEqualByComparingTo("50.00");
        assertThat(ledgerReason(normalAccountId, holdKey)).isEqualTo("下分申请预扣（聊天指令）：普通积分玩家");
        assertThat(ledgerCountByKey(holdKey)).isEqualTo(1);

        String machineToken = machineToken();
        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意下分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(balanceOf(normalAccountId)).as("同意下分不得再动玩家余额").isEqualByComparingTo("50.00");
        assertThat(ledgerCount(normalAccountId)).as("同意下分不写新的钱包流水").isEqualTo(walletLedgerBefore + 1);
        assertThat(machineScore()).as("普才：机器池 += amount").isEqualByComparingTo("1025.00");
        assertThat(lastMachineLedgerEntryType()).isEqualTo("PLAYER_DOWN");
        assertThat(lastMachineLedgerDirection()).isEqualTo("IN");
        assertThat(lastMachineLedgerAmount()).isEqualByComparingTo("25.00");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"重复同意\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("50.00");
        assertThat(machineScore()).as("重复同意不得重复加机器池").isEqualByComparingTo("1025.00");
        assertThat(machineLedgerCount()).isEqualTo(1L);
    }

    /**
     * 切片 3：拒绝下分 → 退回预扣积分 + 写退回流水；**重复拒绝只退一次**。
     * BY220 {@code application/rbt/controller/Index.php:307-315}。
     */
    @Test
    void chatDownRequestRejectionRefundsHeldScoreExactlyOnce() throws Exception {
        long normalUserId = userId(NORMAL);
        sendChatCommand(NORMAL, "chat-down-refund-1", "下25");
        long requestId = requestIdOf(normalUserId, "chat-down-refund-1");
        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("50.00");
        int ledgerAfterHold = ledgerCount(normalAccountId);

        String machineToken = machineToken();
        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/reject")
                        .contentType("application/json").content("{\"reason\":\"拒绝下分\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(balanceOf(normalAccountId)).as("拒绝下分退回预扣积分").isEqualByComparingTo("75.00");
        assertThat(ledgerCount(normalAccountId)).as("必须写一条下分退回流水").isEqualTo(ledgerAfterHold + 1);
        String refundKey = refundKey(requestId);
        assertThat(ledgerOperationType(normalAccountId, refundKey)).isEqualTo("ADMIN_GRANT");
        assertThat(ledgerAmount(normalAccountId, refundKey)).isEqualByComparingTo("25.00");
        assertThat(ledgerBalanceAfter(normalAccountId, refundKey)).isEqualByComparingTo("75.00");
        assertThat(ledgerReason(normalAccountId, refundKey)).isEqualTo("下分申请拒绝退回（聊天指令）：普通积分玩家");
        assertThat(machineScore()).as("驳回不得动机器池").isEqualByComparingTo("1000.00");
        assertThat(machineLedgerCount()).isZero();

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/reject")
                        .contentType("application/json").content("{\"reason\":\"重复拒绝\"}")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(balanceOf(normalAccountId)).as("重复拒绝不得重复退回").isEqualByComparingTo("75.00");
        assertThat(ledgerCount(normalAccountId)).isEqualTo(ledgerAfterHold + 1);
        assertThat(ledgerCountByKey(refundKey)).isEqualTo(1);
    }

    /**
     * 计划 §3.2 定死的**有意偏离**：BY220 创建下分申请不校验余额（可扣成负数），我方**拒绝创建**
     * ——400 + 复用既有「您当前积分不足X」文案，且**余额与流水一个字节都不动、不落申请单**。
     */
    @Test
    void chatDownRequestWithoutEnoughBalanceIsRejectedAndKeepsBalance() throws Exception {
        String playerToken = login(NORMAL, PASSWORD);
        int ledgerBefore = ledgerCount(normalAccountId);

        mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .contentType("application/json")
                        .content("{\"clientMessageId\":\"chat-down-insufficient-1\",\"content\":\"下100\"}")
                        .header("Authorization", bearer(playerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WALLET_INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.message").value("您当前积分不足100"));

        assertThat(balanceOf(normalAccountId)).as("拒绝创建时余额不变").isEqualByComparingTo("75.00");
        assertThat(ledgerCount(normalAccountId)).as("拒绝创建时不写任何流水").isEqualTo(ledgerBefore);
        assertThat(count("SELECT COUNT(*) FROM player_point_request WHERE user_id=? AND client_message_id=?",
                userId(NORMAL), "chat-down-insufficient-1")).isZero();
    }

    /**
     * 关键保护：改动**上线前**创建的 PENDING 下分申请是旧口径（创建时不扣、没有预扣流水）。
     * 这类申请被拒绝时**不得退回**，否则等于凭空给玩家加钱。
     */
    @Test
    void legacyPendingDownRequestWithoutHoldIsNotRefundedOnRejection() throws Exception {
        long requestId = insertPending(normalAccountId, "DOWN", "25.00", "legacy-down-1");
        int ledgerBefore = ledgerCount(normalAccountId);

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/reject")
                        .contentType("application/json").content("{\"reason\":\"拒绝旧口径下分\"}")
                        .header("Authorization", bearer(machineToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(requestStatus(requestId)).isEqualTo("REJECTED");
        assertThat(balanceOf(normalAccountId)).as("无预扣行则不得退回").isEqualByComparingTo("75.00");
        assertThat(ledgerCount(normalAccountId)).isEqualTo(ledgerBefore);
        assertThat(ledgerCountByKey(refundKey(requestId))).isZero();
    }

    /**
     * 切片 4 回归：上分申请逐项与改动前一致——创建**不动**余额、不写流水；同意时钱包加分、
     * **普**才扣机器池（BY220 {@code rbt/controller/Index.php:268-279}）。
     */
    @Test
    void chatTopUpRequestKeepsPreChangeBehaviour() throws Exception {
        long normalUserId = userId(NORMAL);
        int ledgerBefore = ledgerCount(normalAccountId);

        sendChatCommand(NORMAL, "chat-topup-regression-1", "上30");

        assertThat(balanceOf(normalAccountId)).as("上分创建不动余额（回归）").isEqualByComparingTo("75.00");
        assertThat(ledgerCount(normalAccountId)).as("上分创建不写钱包流水（回归）").isEqualTo(ledgerBefore);
        assertThat(machineLedgerCount()).as("上分创建不动机器池（回归）").isZero();
        long requestId = requestIdOf(normalUserId, "chat-topup-regression-1");
        assertThat(requestStatus(requestId)).isEqualTo("PENDING");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意上分\"}")
                        .header("Authorization", bearer(machineToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(balanceOf(normalAccountId)).isEqualByComparingTo("105.00");
        assertThat(machineScore()).isEqualByComparingTo("970.00");
        assertThat(lastMachineLedgerEntryType()).isEqualTo("PLAYER_TOP_UP");
        assertThat(lastMachineLedgerDirection()).isEqualTo("OUT");
        assertThat(lastMachineLedgerAmount()).isEqualByComparingTo("30.00");
    }

    /** 下分的**托不联动**：创建照常预扣玩家余额，同意只结束申请，机器池一动不动。 */
    @Test
    void chatDownRequestForBotKeepsHeldBalanceAndDoesNotTouchMachinePool() throws Exception {
        long botUserId = userId(BOT);
        sendChatCommand(BOT, "chat-down-bot-1", "下20");
        assertThat(balanceOf(botAccountId)).isEqualByComparingTo("30.00");
        long requestId = requestIdOf(botUserId, "chat-down-bot-1");

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + requestId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"同意托下分\"}")
                        .header("Authorization", bearer(machineToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(balanceOf(botAccountId)).as("同意下分不动玩家余额").isEqualByComparingTo("30.00");
        assertThat(machineScore()).as("托不联动机器池").isEqualByComparingTo("1000.00");
        assertThat(machineLedgerCount()).isZero();
    }

    private void sendChatCommand(String username, String clientMessageId, String content) {
        chatMessageService.sendUserMessage(userId(username), "main", clientMessageId, content, now);
    }

    private long userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().id();
    }

    private long requestIdOf(long userId, String clientMessageId) {
        return jdbc.queryForObject("SELECT id FROM player_point_request WHERE user_id=? AND client_message_id=?",
                (rs, rowNum) -> rs.getLong(1), userId, clientMessageId);
    }

    private static String holdKey(long requestId) {
        return PlayerPointRequestRepository.holdIdempotencyKey(requestId);
    }

    private static String refundKey(long requestId) {
        return PlayerPointRequestRepository.refundIdempotencyKey(requestId);
    }

    private String ledgerOperationType(long accountId, String idempotencyKey) {
        return jdbc.queryForObject("SELECT operation_type FROM demo_balance_ledger WHERE user_id=? AND idempotency_key=?",
                String.class, accountId, idempotencyKey);
    }

    private BigDecimal ledgerAmount(long accountId, String idempotencyKey) {
        return jdbc.queryForObject("SELECT amount FROM demo_balance_ledger WHERE user_id=? AND idempotency_key=?",
                BigDecimal.class, accountId, idempotencyKey);
    }

    private BigDecimal ledgerBalanceAfter(long accountId, String idempotencyKey) {
        return jdbc.queryForObject("SELECT balance_after FROM demo_balance_ledger WHERE user_id=? AND idempotency_key=?",
                BigDecimal.class, accountId, idempotencyKey);
    }

    private String ledgerReason(long accountId, String idempotencyKey) {
        return jdbc.queryForObject("SELECT reason FROM demo_balance_ledger WHERE user_id=? AND idempotency_key=?",
                String.class, accountId, idempotencyKey);
    }

    private int ledgerCountByKey(String idempotencyKey) {
        return count("SELECT COUNT(*) FROM demo_balance_ledger WHERE idempotency_key=?", idempotencyKey);
    }

    private int count(String sql, Object... args) {
        Integer total = jdbc.queryForObject(sql, Integer.class, args);
        return total == null ? 0 : total;
    }

    private void insertLedger(long accountId, String operation, String amount, String before,
                              String after, String suffix) {
        jdbc.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after,
                     operator_user_id, operator_name, idempotency_key, reason, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, '测试管理员', ?, '最近上下分测试', ?)
                """, accountId, operation, new BigDecimal(amount), new BigDecimal(before),
                new BigDecimal(after), "POINT-OP-" + suffix, Timestamp.from(now));
    }

    private long insertPending(long accountId, String requestType, String amount, String clientMessageId) {
        long userId = jdbc.queryForObject("SELECT sys_user_id FROM demo_user_account WHERE id=?",
                Long.class, accountId);
        var sourceMessage = chatMessageService.sendUserMessage(userId, "main", clientMessageId,
                "上下分申请测试消息", now);
        jdbc.update("""
                INSERT INTO player_point_request
                    (user_id, request_type, amount, status, client_message_id,
                     source_message_id, requested_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                """, userId, requestType, new BigDecimal(amount), clientMessageId,
                sourceMessage.id(), Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM player_point_request WHERE user_id=? AND client_message_id=?",
                Long.class, userId, clientMessageId);
    }

    private int approvedFeedbackCount(long requestId) {
        return count("SELECT COUNT(*) FROM chat_message WHERE idempotency_key=?",
                "POINT-REQUEST-APPROVED:" + requestId);
    }

    private String approvedFeedbackContent(long requestId) {
        return jdbc.queryForObject("SELECT content FROM chat_message WHERE idempotency_key=?",
                String.class, "POINT-REQUEST-APPROVED:" + requestId);
    }

    private int ledgerCount(long accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM demo_balance_ledger WHERE user_id=?",
                Integer.class, accountId);
    }

    private BigDecimal machineScore() {
        return jdbc.queryForObject("SELECT score FROM agent WHERE id=?", BigDecimal.class, machineId);
    }

    private long machineLedgerCount() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM agent_ledger WHERE agent_id=?", Long.class, machineId);
        return total == null ? 0L : total;
    }

    private String lastMachineLedgerEntryType() {
        return jdbc.queryForObject(
                "SELECT entry_type FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1", String.class, machineId);
    }

    private String lastMachineLedgerDirection() {
        return jdbc.queryForObject(
                "SELECT direction FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1", String.class, machineId);
    }

    private BigDecimal lastMachineLedgerAmount() {
        return jdbc.queryForObject(
                "SELECT amount FROM agent_ledger WHERE agent_id=? ORDER BY id DESC LIMIT 1", BigDecimal.class, machineId);
    }

    private BigDecimal balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id=?", BigDecimal.class, accountId);
    }

    private String requestStatus(long requestId) {
        return jdbc.queryForObject("SELECT status FROM player_point_request WHERE id=?", String.class, requestId);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /**
     * 机器账号按 /console 的入口登录（显式 {@code X-Xupan-Auth-Audience: CONSOLE}）。
     *
     * <p>审批属于代理后台（BY220 {@code robot.php}），因此操作者必须是机器账号，
     * 且与前端第二批的登录契约一致地声明 CONSOLE 入口。</p>
     */
    private String machineToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .header(com.xupan.server.auth.web.AuthController.REFRESH_AUDIENCE_HEADER, "CONSOLE")
                        .content("{\"username\":\"" + MACHINE_USER + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void clean() {
        jdbc.update("DELETE FROM auth_ws_ticket");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_login_log");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM player_point_request WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?))", NORMAL, BOT, ADMIN, MACHINE_USER);
        // 聊天指令用例会真的往 public 大厅发消息，按既有 chat 用例的口径清理自己发出的消息
        // （机器人反馈消息的 sender_id 是机器人，不在清理范围内）。
        jdbc.update("DELETE FROM chat_outbox WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?)))", NORMAL, BOT, ADMIN, MACHINE_USER);
        jdbc.update("DELETE FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?))", NORMAL, BOT, ADMIN, MACHINE_USER);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?)))", NORMAL, BOT, ADMIN, MACHINE_USER);
        // demo_user_account.agent_id → agent 外键，必须先删账号再删机器
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?))", NORMAL, BOT, ADMIN, MACHINE_USER);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent_game WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?))", NORMAL, BOT, ADMIN, MACHINE_USER);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?, ?, ?)", NORMAL, BOT, ADMIN, MACHINE_USER);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
