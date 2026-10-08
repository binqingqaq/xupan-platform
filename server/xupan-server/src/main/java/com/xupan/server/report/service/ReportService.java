package com.xupan.server.report.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.playerauth.service.PlayerLinkTokenCipher;
import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import com.xupan.server.report.repository.ReportConfigRepository;
import com.xupan.server.report.repository.ReportUpstreamOrderRepository;
import com.xupan.server.system.service.PlayerDeskScopeService;
import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 运营后台（{@code /console}，BY220 {@code robot.php}）报网/盘口服务。
 *
 * <p>合规边界（强制）：只做内部持久化与本地模拟。本服务**不发起任何网络请求**、
 * 不保存真实凭据；{@code report_url} 只在保存时做 http/https 前缀校验后入库，
 * 永不请求。真实上游、中心上游与第二层账本均不在本类范围内。</p>
 *
 * <p>范围规则：机器账号固定操作自己那台；平台管理员与子账号必须显式传 {@code machineId}
 * （未传 → 400 {@code PLAYER_DESK_MACHINE_REQUIRED}，不落到平台直属代理），
 * 且目标机器必须在自己范围内，范围外按"不存在"返回 404。</p>
 *
 * <p>权限规则：读走范围解析（{@code /api/admin/player-desk/**} 由 URL 层按
 * {@code USER_MANAGE | AGENT_PLAYER_MANAGE | AGENT_CONSOLE_READ} 放行，
 * {@code /api/agent/**} 只认 {@code AGENT_CONSOLE_READ}——V76 之后只有机器账号持有它），写操作要求
 * {@code USER_MANAGE}（平台管理员）或 {@code AGENT_PLAYER_MANAGE}（机器/子账号）。</p>
 *
 * <p>密码规则：写接口只接受明文入参并**立即加密**，只落
 * {@code agent.report_password_ciphertext}；读接口只返回 {@code hasPassword} 布尔，
 * 明文与密文都不出现在响应、日志或审计里。</p>
 *
 * <p>统一飞单（BY220 {@code x_admin.feidan*}）：机器自身没有飞单账号时，读配置会
 * **只读**回退到所属子账号的统一飞单账号/网盘/盘口/报网类型（见
 * {@link ReportConfigRepository#findConfig(long)}），因此登录前置条件、自动上报、
 * 人工重报都自然按子账号凭据判断；账号与密码始终留在子账号行，绝不复制到 {@code agent}。
 * 报网类型（V73 {@code agent_group.report_type}）取子账号的值，子账号未设置时回退机器自身。
 * 读取结果用 {@code inheritedFromGroup} 透出，前端可提示"当前使用子账号统一飞单"。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 审计动作码（BY220 报网配置）。 */
    public static final String AUDIT_ACTION = "AGENT_REPORT_CONFIG";

    /** 允许的报网类型，与 V56 的 {@code ck_agent_report_type} 保持一致。 */
    public static final Set<String> REPORT_TYPES =
            Set.of("NONE", "TT", "K28", "LS8899", "WNS", "LX", "HY", "CENTER");
    /** 允许的盘口线路，与 V56 的 {@code ck_agent_report_route} 保持一致。 */
    public static final Set<String> ROUTE_CODES = Set.of("A", "B", "C", "D");
    /**
     * 上游上报订单状态机，与 V58 的 {@code ck_report_upstream_order_status} 保持一致。
     *
     * <p>对应需求文档《代理报网盘口与结算业务规则说明》§16.4：
     * 待处理 / 已接受 / 已拒绝 / 超时 / 已撤单 / 已退款 / 已结算。
     * 本阶段（本地模拟上报）写入 {@code PENDING/ACCEPTED/REJECTED/TIMEOUT}；
     * 后三个由中心上游第二层结算写入。</p>
     */
    public static final Set<String> REPORT_STATUSES = Set.of(
            "PENDING", "ACCEPTED", "REJECTED", "TIMEOUT", "CANCELLED", "REFUNDED", "SETTLED", "MANUAL");
    /** 没有上报订单行时派生出来的状态：还没报过。 */
    public static final String STATUS_PENDING = "PENDING";
    /**
     * 允许重报的当前状态（对应 BY220 {@code reFeidan}：只重报未报/失败/超时）。
     *
     * <p>{@code MANUAL}（人工待处置）**刻意不在集合内**：它表示自动重报已达上限，
     * 继续放进来就会重新造成"失败单占满批次"的头部阻塞。人工重报走
     * {@link #resubmit} 显式入口。</p>
     */
    public static final Set<String> RESUBMITTABLE_STATUSES = Set.of("PENDING", "REJECTED", "TIMEOUT");
    /** 尚未上报、可供自动上报链路处理的当前状态。 */
    public static final List<String> SUBMIT_FROM_STATUSES = List.of(STATUS_PENDING);
    /**
     * 自动上报链路的候选状态集合（含重报），供健康检查统计积压量使用。
     *
     * <p>刻意不含 {@code MANUAL}：它表示已转人工、不再参与自动重报，
     * 把它算进"积压"会让告警永远无法恢复。</p>
     */
    public static final List<String> RESUBMITTABLE_REPORT_STATUSES = List.of("PENDING", "REJECTED", "TIMEOUT");
    /** BY220 报网类型里的"无"。 */
    private static final String TYPE_NONE = "NONE";
    private static final int MAX_NETWORK_CODE_LENGTH = 64;
    private static final int MAX_USERNAME_LENGTH = 128;
    private static final int MAX_URL_LENGTH = 512;
    /** 明文上限：加密后仍能放进 {@code VARCHAR(512)} 的密文列。 */
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReportConfigRepository repository;
    private final ReportUpstreamOrderRepository orderRepository;
    private final PlayerDeskScopeService scopeService;
    private final PermissionService permissionService;
    private final OperationAuditRepository auditRepository;
    private final ReportAdapter reportAdapter;
    private final CenterReportAdapter centerReportAdapter;
    private final PlayerLinkTokenCipher passwordCipher;
    private final GameDataRepository gameDataRepository;
    private final VirtualWalletService walletService;
    private final ChatMessageService chatMessageService;
    /** 只用于把"本地模拟登录/退出"的结果同步到子账号的统一飞单上游状态（V74 {@code agent_group.report_*}）。 */
    private final JdbcTemplate jdbc;
    /** 自动重报的次数上限；达到后转 MANUAL 人工处置（P0-2）。 */
    private final int retryMaxAttempts;
    /** 分级退避梯度；索引 = 第 N 次失败。为空表示不延迟（等同 BY220 每轮无条件重报）。 */
    private final List<Duration> retryBackoff;

    public ReportService(ReportConfigRepository repository, ReportUpstreamOrderRepository orderRepository,
                         PlayerDeskScopeService scopeService,
                         PermissionService permissionService, OperationAuditRepository auditRepository,
                         ReportAdapter reportAdapter, CenterReportAdapter centerReportAdapter,
                         PlayerLinkTokenCipher passwordCipher,
                         GameDataRepository gameDataRepository, VirtualWalletService walletService,
                         ChatMessageService chatMessageService,
                         JdbcTemplate jdbc,
                         @Value("${xupan.report.retry.max-attempts:5}") int retryMaxAttempts,
                         @Value("${xupan.report.retry.backoff:30s,2m,10m,1h}") String retryBackoffSpec) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.scopeService = scopeService;
        this.permissionService = permissionService;
        this.auditRepository = auditRepository;
        this.reportAdapter = reportAdapter;
        this.centerReportAdapter = centerReportAdapter;
        this.passwordCipher = passwordCipher;
        this.gameDataRepository = gameDataRepository;
        this.walletService = walletService;
        this.chatMessageService = chatMessageService;
        this.jdbc = jdbc;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoff = parseBackoff(retryBackoffSpec);
    }

    /** 解析退避梯度，例如 {@code "30s,2m,10m,1h"}。非法项跳过，不影响启动。 */
    private static List<Duration> parseBackoff(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        List<Duration> parsed = new ArrayList<>();
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            Duration duration = parseDuration(token);
            if (duration != null) {
                parsed.add(duration);
            }
        }
        return List.copyOf(parsed);
    }

    /** 支持 {@code 30s} / {@code 2m} / {@code 1h} / {@code 500ms} 四种简写，也接受纯秒数。 */
    private static Duration parseDuration(String token) {
        String lower = token.toLowerCase();
        try {
            if (lower.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2)));
            }
            char unit = lower.charAt(lower.length() - 1);
            if (unit == 's' || unit == 'm' || unit == 'h') {
                long value = Long.parseLong(lower.substring(0, lower.length() - 1));
                return switch (unit) {
                    case 's' -> Duration.ofSeconds(value);
                    case 'm' -> Duration.ofMinutes(value);
                    default -> Duration.ofHours(value);
                };
            }
            return Duration.ofSeconds(Long.parseLong(lower));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 读取机器报网配置 + 上报状态统计（超时 / 未报 / 失败）。密码只返回 {@code hasPassword}。 */
    @Transactional(readOnly = true)
    public ReportConfigView config(long operator, Long machineId) {
        long targetAgentId = targetAgentId(operator, machineId);
        return view(targetAgentId);
    }

    /**
     * 保存机器报网配置。
     *
     * <p>字段语义：{@code null} 表示"保持原值"；其余按 {@link ReportConfigUpdate} 逐个校验后写入。
     * 密码只在传入非空时覆盖（加密后入库），因此不传密码不会清掉既有密文。
     * 审计只记录机器、类型、线路、网盘与开关，**绝不包含密码**。</p>
     */
    @Transactional
    public ReportConfigView saveConfig(long operator, Long machineId, ReportConfigUpdate update) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        ReportConfigRepository.ReportConfigRow current = requireConfig(targetAgentId);
        // 统一飞单回退只作用于**读取**：写入合并的"保持原值"必须以机器自身列为基准。
        // 若直接沿用回退后的值，一次"只改盘口线路"的保存就会把子账号的飞单账号/网盘/盘口
        // 复制到 agent 行——既可能撞 uk_agent_report_username 唯一约束，
        // 也会破坏"一台机器一个上游账号"的账本原则（原则 8）。
        String baseUsername = current.inheritedFromGroup() ? null : current.reportUsername();
        String baseNetworkCode = current.inheritedFromGroup() ? null : current.reportNetworkCode();
        String baseRouteCode = current.inheritedFromGroup() ? null : current.reportRouteCode();
        // 报网类型同理必须落到**机器自身列**：继承态下 current.reportType() 是子账号的类型
        // （V73 起统一飞单也管类型），直接当基准就把子账号的类型复制进 agent 行；
        // 因此用 current.ownReportType()（机器自身列的原始值，不做任何回退），
        // 既不会复制子账号的值，也不会把机器自己原有的类型冲成 NULL。
        String baseReportType = current.ownReportType();
        // 统一飞单账号是**子账号的共享账号**，机器不能把它复制成"自己的"：
        // 否则一台机器会占住该上游账号（撞 uk_agent_report_username），兄弟机器立刻报
        // REPORT_USERNAME_TAKEN，而且这台机器以后不再跟随子账号轮换（静默漂移）。
        // 界面在继承态已不再回填该值，这里是对接口调用方与旧前端的兜底。
        if (current.inheritedFromGroup() && update.reportUsername() != null
                && !update.reportUsername().isBlank()
                && update.reportUsername().trim().equals(current.reportUsername())) {
            throw BusinessException.conflict("REPORT_USERNAME_INHERITED",
                    "该飞单账号来自所属子账号的统一飞单，不能写成机器自身账号：请留空以继续沿用，或填写机器专属账号");
        }
        ReportConfigRepository.ReportConfigUpdate applied = new ReportConfigRepository.ReportConfigUpdate(
                resolveReportType(update.reportType(), baseReportType),
                update.reportAuto() == null ? current.reportAuto() : update.reportAuto(),
                update.reportWithdraw() == null ? current.reportWithdraw() : update.reportWithdraw(),
                update.reportUsername() == null ? baseUsername
                        : optionalText(update.reportUsername(), MAX_USERNAME_LENGTH, "REPORT_USERNAME_INVALID", "飞单账号无效"),
                passwordCiphertext(update.reportPassword()),
                update.reportUrl() == null ? current.reportUrl() : validateUrl(update.reportUrl()),
                update.reportNetworkCode() == null ? baseNetworkCode
                        : optionalText(update.reportNetworkCode(), MAX_NETWORK_CODE_LENGTH,
                        "REPORT_NETWORK_CODE_INVALID", "网盘编码无效"),
                update.reportRouteCode() == null ? baseRouteCode
                        : resolveRouteCode(update.reportRouteCode()));
        requireReportUsernameAvailable(applied.reportUsername(), targetAgentId);
        repository.updateConfig(targetAgentId, applied);
        audit(operator, "PUT", "/api/admin/player-desk/report", targetAgentId,
                "reportType=" + applied.reportType() + ",route=" + applied.reportRouteCode()
                        + ",network=" + applied.reportNetworkCode() + ",auto=" + applied.reportAuto()
                        + ",withdraw=" + applied.reportWithdraw()
                        + ",credentialUpdated=" + (applied.passwordCiphertext() != null));
        return view(targetAgentId);
    }

    /**
     * 本地模拟登录：置 {@code report_online=true} 并写 {@code report_last_login_at}，
     * 不发起任何网络请求、不校验任何外部凭据。
     *
     * <p>前置条件（不满足则 400 {@code REPORT_CONFIG_INCOMPLETE}）：已有报网类型（非空且不是 {@code NONE}）、
     * 网盘编码、盘口线路与飞单账号。</p>
     *
     * <p>统一飞单继承态下，登录成功还会把子账号（V74 {@code agent_group.report_*}）的上游状态置为
     * 在线并把本次读到的余额/未结算下注同步过去，见
     * {@link #syncSubAccountReportOnline(long, ReportConfigRepository.ReportConfigRow, boolean)}。</p>
     */
    @Transactional
    public ReportConfigView login(long operator, Long machineId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        ReportConfigRepository.ReportConfigRow current = requireConfig(targetAgentId);
        if (!configured(current.reportType()) || !configured(current.reportNetworkCode())
                || !configured(current.reportRouteCode()) || !configured(current.reportUsername())) {
            throw BusinessException.badRequest("REPORT_CONFIG_INCOMPLETE",
                    "报网配置不完整：需要报网类型、网盘编码、盘口线路与飞单账号");
        }
        repository.markOnline(targetAgentId, Instant.now());
        syncSubAccountReportOnline(targetAgentId, current, true);
        audit(operator, "POST", "/api/admin/player-desk/report/login", targetAgentId, "reportOnline=true");
        return view(targetAgentId);
    }

    /**
     * 校验上游账号未被其它机器占用（账本原则 8）。
     *
     * <p>《代理报网盘口与结算业务规则说明》§14：共用同一上游账号会导致余额混合、订单归属不明、
     * 风险共用与退款不清，因此这里直接拒绝，而不是"明确隔离"。</p>
     */
    private void requireReportUsernameAvailable(String reportUsername, long targetAgentId) {
        repository.findAgentIdByReportUsername(reportUsername, targetAgentId).ifPresent(other -> {
            throw BusinessException.conflict("REPORT_USERNAME_TAKEN",
                    "该飞单账号已被其它机器占用：" + other);
        });
    }

    /**
     * 退出报网：只置 {@code report_online=false}，保留配置与上次登录时间。
     *
     * <p>统一飞单继承态下，退出还会把子账号（V74 {@code agent_group.report_online}）置为离线，
     * 但**保留**已读到的余额/下注（BY220 {@code Websocket.php:329} 也只改 {@code feidanonline}）。</p>
     */
    @Transactional
    public ReportConfigView logout(long operator, Long machineId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        ReportConfigRepository.ReportConfigRow current = requireConfig(targetAgentId);
        repository.markOffline(targetAgentId);
        syncSubAccountReportOnline(targetAgentId, current, false);
        audit(operator, "POST", "/api/admin/player-desk/report/logout", targetAgentId, "reportOnline=false");
        return view(targetAgentId);
    }

    /**
     * 把本地模拟的"报网登录成功 / 退出"同步到所属子账号的统一飞单上游状态
     * （V74 {@code agent_group.report_online / report_balance / report_stake}）。
     *
     * <p><b>BY220 依据</b>：{@code console/Websocket.php:307-318} 上游返回成功时把子账号的
     * {@code feidanonline=1}、{@code feidanmoney=上游余额}、{@code feidanbetting=上游未结算下注额}
     * 一起写回；{@code admin/controller/Users.php:111-122}（被注释掉的 subLogin 分支）子账号登录
     * 上游成功后 {@code feidanonline=1}；{@code Websocket.php:328-330} 重新登录失败 → {@code feidanonline=0}。
     * 注意 {@code Users.php:73-78} 开启飞单只重置**机器**的 {@code x_rbfly.flyers_online}，
     * 不动子账号状态——本方法因此只由登录/退出驱动，不由开关驱动。</p>
     *
     * <p><b>本地模拟边界</b>：本项目不接任何真实外部上游、不发起任何网络请求，"机器的报网登录成功"
     * 就是 BY220 上游读数的本地等价物，所以这里把子账号的余额/下注直接取**那台机器**的
     * {@code report_balance} / {@code report_stake}（即本次读到的"上游余额 / 未结算下注"）。
     * 退出只置离线并保留余额/下注读数。</p>
     *
     * <p>只有"继承该子账号统一飞单"的机器（{@code inheritedFromGroup = TRUE}，判定与
     * {@link ReportConfigRepository#findConfig(long)} 完全一致）才动子账号状态；
     * 机器自己有飞单账号时不碰子账号（这类机器的上游读数是机器自己的）。</p>
     *
     * @param online true = 登录成功（同步在线与余额/下注），false = 退出（只置离线）
     */
    private void syncSubAccountReportOnline(long agentId, ReportConfigRepository.ReportConfigRow config,
                                            boolean online) {
        if (!config.inheritedFromGroup()) {
            return;
        }
        Long groupId = jdbc.query("SELECT group_id FROM agent WHERE id = ?",
                rs -> rs.next() ? rs.getObject("group_id", Long.class) : null, agentId);
        if (groupId == null) {
            return;
        }
        if (online) {
            jdbc.update("""
                    UPDATE agent_group
                       SET report_online=TRUE, report_balance=?, report_stake=?, updated_at=CURRENT_TIMESTAMP
                     WHERE id=? AND deleted_at IS NULL
                    """, config.reportBalance(), config.reportStake(), groupId);
        } else {
            jdbc.update("""
                    UPDATE agent_group SET report_online=FALSE, updated_at=CURRENT_TIMESTAMP
                     WHERE id=? AND deleted_at IS NULL
                    """, groupId);
        }
    }

    /** 按派生上报状态（可空=全部）列出本机器范围内的注单。 */
    @Transactional(readOnly = true)
    public ReportOrderPage orders(long operator, Long machineId, String status, int page, int pageSize) {
        long targetAgentId = targetAgentId(operator, machineId);
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("REPORT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        String normalizedStatus = normalizeStatus(status);
        List<ReportUpstreamOrderRepository.OrderRow> items =
                orderRepository.findOrders(targetAgentId, normalizedStatus, page, pageSize);
        return new ReportOrderPage(items, page, pageSize,
                orderRepository.countOrders(targetAgentId, normalizedStatus));
    }

    /**
     * 重报单笔注单（对应 BY220 {@code reFeidan}）。
     *
     * <p>只允许派生状态为 {@code PENDING/REJECTED/TIMEOUT} 且属于该机器的注单；
     * 范围外的注单按"不存在"返回 404，状态不允许返回 409。
     * 适配器结果与状态回写在**同一事务**内完成，并写审计（不含任何凭据）。</p>
     */
    @Transactional
    public ResubmitResult resubmit(long operator, Long machineId, long betId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        return doResubmit(operator, targetAgentId, betId, false);
    }

    /**
     * 人工重报（P0-2 附带能力）：把已达自动重试上限、转入 {@code MANUAL} 的注单重新交给上报链路。
     *
     * <p>为什么必须有这个入口：{@code MANUAL} 被刻意排除在自动候选集合之外（否则失败单会重新
     * 堵死批次），因此如果不提供显式重报，人工处置后这些单就永远报不出去了。
     * 重报会**清零尝试计数并解除人工标记**，让退避从第一档重新开始。</p>
     */
    @Transactional
    public ResubmitResult resubmitManual(long operator, Long machineId, long betId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        return doResubmit(operator, targetAgentId, betId, true);
    }

    private ResubmitResult doResubmit(long operator, long targetAgentId, long betId, boolean allowManual) {
        ReportUpstreamOrderRepository.BetRow bet = orderRepository.findBetForAgent(betId, targetAgentId)
                .orElseThrow(() -> BusinessException.notFound("REPORT_BET_NOT_FOUND", "注单不存在或不在当前机器范围内"));
        boolean manual = "MANUAL".equals(bet.reportStatus());
        if (manual && !allowManual) {
            throw BusinessException.conflict("REPORT_BET_STATUS_INVALID",
                    "该注单已转人工待处置，请走人工重报入口");
        }
        if (!manual && !RESUBMITTABLE_STATUSES.contains(bet.reportStatus())) {
            throw BusinessException.conflict("REPORT_BET_STATUS_INVALID", "该注单当前上报状态不允许重报");
        }
        if (manual) {
            // 解除人工标记并清零退避，让重试梯度重新开始。
            orderRepository.resetForManualResubmit(betId);
        }
        ReportConfigRepository.ReportConfigRow config = requireConfig(targetAgentId);
        String newStatus = submitAttempt(bet, config);
        audit(operator, "POST", "/api/admin/player-desk/report/orders/" + betId
                        + (manual ? "/manual-resubmit" : "/resubmit"), targetAgentId,
                "betId=" + betId + ",reportStatus=" + newStatus + ",fromManual=" + manual);
        return new ResubmitResult(betId, newStatus, view(targetAgentId));
    }

    /**
     * 供上报链路调用的自动上报入口（BY220 飞单）。
     *
     * <p>仅当注单派生状态为 {@code PENDING}（从未上报，或上次尝试仍挂在待处理）、机器的
     * {@code report_auto=true} 且 {@code report_online=true}、并且 {@code report_type} 已配置为非
     * {@code NONE} 时才提交；其余情况直接跳过（返回 {@link Optional#empty()}），不影响下注本身。</p>
     *
     * <p>每次提交都会在 {@code report_upstream_order} **追加一行**上报尝试（不覆盖历史），
     * 随后在同一事务内落结果。</p>
     *
     * @return 本次提交后的上报状态；未提交时为 {@link Optional#empty()}
     */
    @Transactional
    public Optional<String> submitForBet(long betId) {
        if (betId <= 0) {
            return Optional.empty();
        }
        ReportUpstreamOrderRepository.BetRow bet = orderRepository.findBet(betId).orElse(null);
        if (bet == null || !SUBMIT_FROM_STATUSES.contains(bet.reportStatus())) {
            return Optional.empty();
        }
        ReportConfigRepository.ReportConfigRow config = repository.findConfig(bet.agentId()).orElse(null);
        // 前置条件只判断"这台机器是否应该上报"：自动上报 + 在线 + 报网类型已配置。
        // 网盘编码/盘口线路缺失属于"配置不完整"，要交给适配器判为失败，
        // 这样 BY220 的"上报失败 → 失败自动退单"路径才走得通（否则会被静默跳过）。
        if (config == null || !config.reportAuto() || !config.reportOnline() || !configured(config.reportType())) {
            return Optional.empty();
        }
        return Optional.of(submitAttempt(bet, config));
    }

    /**
     * 追加一次上报尝试并立即落结果（同一事务）。
     *
     * <p>顺序：写 {@code PENDING} 尝试行（留痕、幂等键 {@code REPORT-<betId>-<attemptNo>}）
     * → 调适配器 → 把结果回写到该尝试行。失败退单开启且结果为 {@code REJECTED} 时执行退单；
     * {@code ACCEPTED} 时累计本机押注本地模拟值。{@code TIMEOUT} 只落状态，不退款。</p>
     *
     * @return 本次尝试的结果状态；退单成功时为 {@code WITHDRAWN}
     */
    private String submitAttempt(ReportUpstreamOrderRepository.BetRow bet,
                                 ReportConfigRepository.ReportConfigRow config) {
        Instant sentAt = Instant.now();
        int attemptNo = orderRepository.nextAttemptNo(bet.betId());
        long orderId = orderRepository.insertAttempt(bet.betId(), bet.agentId(), attemptNo, STATUS_PENDING,
                config.reportType(), config.reportRouteCode(), config.reportNetworkCode(),
                "REPORT-" + bet.betId() + "-" + attemptNo, requestSummary(bet), null, null, sentAt, null);

        ReportCommand command = new ReportCommand(bet.betId(), bet.accountId(), bet.agentId(), bet.issueNumber(),
                bet.playType(), bet.parametersText(), bet.stake(), bet.odds(), config.reportType(),
                config.reportNetworkCode(), config.reportRouteCode(), config.reportOnline());
        ReportOutcome outcome = adapterFor(config.reportType()).submit(command);
        String newStatus = mapOutcome(outcome);
        orderRepository.resolveAttempt(orderId, newStatus, outcome.name(), responseMessage(outcome),
                upstreamOrderNo(bet, attemptNo), Instant.now());

        if ("REJECTED".equals(newStatus) && withdrawOnFailure(bet, config)) {
            // 已按 BY220 失败退单：注单被物理删除并退还本金
            return "WITHDRAWN";
        }
        if ("ACCEPTED".equals(newStatus)) {
            // 押注为本地模拟值：累计"已成功上报"的投注额（BY220 本地登录时 betting 也为 0，真值来自真实上游）
            repository.addReportedStake(bet.agentId(), bet.stake());
            return newStatus;
        }
        // 失败或超时：安排下一次重报时间点，并检查是否已达重试上限。
        scheduleRetryOrManual(bet.betId(), newStatus);
        return newStatus;
    }

    /**
     * 安排重报退避；超过上限转人工待处置（P0-2）。
     *
     * <p>没有这一步，失败单会每 2.4 秒无条件重报，并且永远留在候选集合里——
     * 这正是"累积 50 笔坏单就堵死整条上报链路"的根因。</p>
     *
     * <p>退避梯度默认 30s → 2min → 10min → 1h，第 5 次起转 {@code MANUAL}。
     * 全部可用配置覆盖；把 {@code report.retry.max-attempts} 设为很大的值、
     * 退避梯度全设 0，即可退回 BY220 的"每轮无条件重报"行为。</p>
     */
    private void scheduleRetryOrManual(long betId, String newStatus) {
        int attempts = orderRepository.scheduleRetry(betId, null);
        int maxAttempts = retryMaxAttempts;
        if (attempts >= maxAttempts) {
            orderRepository.markManual(betId, "RETRY_EXHAUSTED",
                    "自动重报已达上限 " + maxAttempts + " 次，转人工处置");
            log.warn("上报重试达上限，转人工处置 betId={} attempts={} lastStatus={}",
                    betId, attempts, newStatus);
            auditRepository.record(0L, AUDIT_ACTION, "PATCH", "/api/admin/player-desk/report/manual",
                    Long.toString(betId), "SUCCESS", null,
                    "reason=RETRY_EXHAUSTED,attempts=" + attempts + ",lastStatus=" + newStatus, null,
                    Instant.now());
            return;
        }
        orderRepository.scheduleRetry(betId, Instant.now().plus(retryBackoff(attempts)));
    }

    /** 第 {@code attempts} 次失败后的退避时长（越界取最后一档）。 */
    private Duration retryBackoff(int attempts) {
        if (retryBackoff.isEmpty()) {
            return Duration.ZERO;
        }
        int index = Math.min(Math.max(attempts, 1), retryBackoff.size()) - 1;
        return retryBackoff.get(index);
    }

    /**
     * 按机器的 {@code report_type} 选择适配器。
     *
     * <p>{@code CENTER} 走中心上游适配器（把订单真正报给中心），其余走本地模拟适配器。
     * 选择放在这里而不是让适配器自己判断，是为了让"这台机器报给谁"只有一个判定点，
     * 便于以后接入新的报网类型。</p>
     */
    private ReportAdapter adapterFor(String reportType) {
        if (CenterReportAdapter.CODE.equalsIgnoreCase(reportType == null ? "" : reportType.trim())) {
            return centerReportAdapter;
        }
        return reportAdapter;
    }

    /** 上报请求摘要（落库留痕；不含任何凭据）。 */
    private static String requestSummary(ReportUpstreamOrderRepository.BetRow bet) {
        return "bet=" + bet.betId() + ",issue=" + bet.issueNumber() + ",play=" + bet.playType()
                + ",stake=" + bet.stake();
    }

    /**
     * 上游订单号。
     *
     * <p>本项目只做本地模拟上报，没有真实上游单号；这里生成一个可追溯的内部单号，
     * 与 {@code response_code=MIGRATED} 一样属于本系统事实，不伪装成上游返回值。</p>
     */
    private static String upstreamOrderNo(ReportUpstreamOrderRepository.BetRow bet, int attemptNo) {
        return "LOCAL-" + bet.betId() + "-" + attemptNo;
    }

    private static String responseMessage(ReportOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> "本地模拟上游已接受";
            case REJECTED -> "本地模拟上游拒绝";
            case TIMEOUT -> "本地模拟上游未及时返回结果";
        };
    }

    /**
     * 批量上报（BY220 {@code Websocket.php:658-673} 的飞单轮询）。
     *
     * <p>BY220 每 2.4 秒捞出"未报/失败"的订单重新飞单；xupan 由 {@code ReportDispatchScheduler}
     * 以可配置间隔调用本方法，只处理已开启自动上报、在线且报网类型已配置的机器。</p>
     *
     * @param includeRetry true 时一并重报 {@code REJECTED/TIMEOUT}（对应 BY220 轮询捞 {@code flyers_status=3}）
     * @return 处理条数与退单条数
     */
    @Transactional
    public DispatchResult dispatchPending(boolean includeRetry, int limit) {
        List<String> statuses = includeRetry
                ? List.of(STATUS_PENDING, "REJECTED", "TIMEOUT")
                : SUBMIT_FROM_STATUSES;
        List<ReportUpstreamOrderRepository.BetRow> bets = orderRepository.findSubmittableBets(statuses, limit);
        int submitted = 0;
        int withdrawn = 0;
        for (ReportUpstreamOrderRepository.BetRow bet : bets) {
            Optional<String> result = submitForBet(bet.betId());
            if (result.isEmpty()) {
                continue;
            }
            if ("WITHDRAWN".equals(result.get())) {
                withdrawn++;
            } else {
                submitted++;
            }
        }
        return new DispatchResult(bets.size(), submitted, withdrawn);
    }

    /** 一次上报轮询的处理结果。 */
    public record DispatchResult(int scanned, int submitted, int withdrawn) {
    }

    /**
     * 失败自动退单（BY220 {@code common.php:1627-1641}）。
     *
     * <p>BY220 的时序：{@code $make == 3}（上报失败）且机器开启 {@code flyers_withdraw} 时，
     * 先给玩家 {@code setInc('score', $order['score'])}（退还本金）、发一条"注单取消"消息，
     * 最后 {@code db('record')->delete($id)} 物理删除注单；未开启时只把 {@code flyers_status} 标成 3。</p>
     *
     * <p>xupan 复用 V55 已确认的撤单路径：同一事务内物理删除注单 + 幂等退款
     * （幂等键 {@code CANCEL_REFUND:<betId>}）+ 审计。{@code TIMEOUT} 一律只标记不退款
     * （BY220 的超时状态未知，不能当失败处理）。</p>
     *
     * @return true 表示已退单（注单被物理删除）
     */
    private boolean withdrawOnFailure(ReportUpstreamOrderRepository.BetRow bet,
                                      ReportConfigRepository.ReportConfigRow config) {
        if (!config.reportWithdraw()) {
            return false;
        }
        if (!gameDataRepository.deletePendingBetById(bet.betId())) {
            // 注单已被结算或已被其它路径删除：不重复退款
            return false;
        }
        walletService.refundForCancellation(bet.sysUserId(), bet.betId(), bet.accountId(),
                bet.issueNumber(), bet.stake());
        // BY220 失败退单会发一条"注单取消"房间提示（common.php:1635 addMsg）。
        // 反馈失败不能影响退款结果，因此单独兜住异常；幂等键保证重复处理不重复发消息。
        try {
            chatMessageService.publishSystemMessage("main", bet.issueNumber(),
                    "CHAT-REPORT-WITHDRAW-" + bet.betId(),
                    "@" + bet.betId() + " 注单取消（上报失败自动退单）", Instant.now());
        } catch (RuntimeException feedbackFailure) {
            log.warn("失败退单的聊天室取消反馈写入失败 betId={} {}", bet.betId(),
                    feedbackFailure.getMessage());
        }
        auditRepository.record(0L, AUDIT_ACTION, "POST", "/api/admin/player-desk/report/withdraw",
                Long.toString(bet.betId()), "SUCCESS", null,
                "machineId=" + bet.agentId() + ",reason=REPORT_FAILED,refunded=" + bet.stake()
                        + ",betDeleted=true", null, Instant.now());
        return true;
    }

    private static String mapOutcome(ReportOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> "ACCEPTED";
            case REJECTED -> "REJECTED";
            case TIMEOUT -> "TIMEOUT";
        };
    }

    private ReportConfigView view(long agentId) {
        ReportConfigRepository.ReportConfigRow config = requireConfig(agentId);
        ReportUpstreamOrderRepository.StatusCounts counts = orderRepository.counts(agentId);
        return new ReportConfigView(config.agentId(), config.reportType(), config.reportAuto(),
                config.reportOnline(), config.reportWithdraw(), config.reportUsername(), config.hasPassword(),
                config.reportUrl(), config.reportNetworkCode(), config.reportRouteCode(), config.reportBalance(),
                config.reportStake(), config.reportLastLoginAt(), counts.timeoutCount(), counts.unreportedCount(),
                counts.failedCount(), config.inheritedFromGroup());
    }

    private ReportConfigRepository.ReportConfigRow requireConfig(long agentId) {
        return repository.findConfig(agentId)
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或已停用"));
    }

    /** 读范围：机器账号固定自己；其余身份必须显式传机器且机器在范围内。 */
    private long targetAgentId(long operator, Long machineId) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        return targetAgentId(scope, machineId);
    }

    /** 写范围：在读范围之上追加写权限（USER_MANAGE 或 AGENT_PLAYER_MANAGE）。 */
    private long targetAgentIdForWrite(long operator, Long machineId) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (!scope.global() && !permissionService.hasPermission(operator, "AGENT_PLAYER_MANAGE")) {
            throw BusinessException.forbidden("REPORT_CONFIG_FORBIDDEN", "没有报网配置权限");
        }
        return targetAgentId(scope, machineId);
    }

    /**
     * 报网场景的机器解析：非机器身份必须显式选机器。
     *
     * <p>与创建玩家不同，报网配置**不允许**把"未传机器"落到平台直属代理，因此这里先拦
     * {@code machineId == null} 再交给 {@link PlayerDeskScopeService#requireTargetAgentId}；
     * 机器账号传了别人的机器按"不存在"返回 404，不做静默改写。</p>
     */
    private long targetAgentId(PlayerDeskScopeService.Scope scope, Long machineId) {
        if (scope instanceof PlayerDeskScopeService.Scope.Machine machine) {
            if (machineId != null && machineId != machine.agentId()) {
                throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或不在当前范围内");
            }
            return machine.agentId();
        }
        if (machineId == null) {
            throw BusinessException.badRequest(PlayerDeskScopeService.MACHINE_REQUIRED_CODE,
                    "报网操作必须指定机器");
        }
        return scopeService.requireTargetAgentId(scope, machineId);
    }

    private String resolveReportType(String value, String current) {
        if (value == null) {
            return current;
        }
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (!REPORT_TYPES.contains(type)) {
            throw BusinessException.badRequest("REPORT_TYPE_INVALID", "报网类型无效");
        }
        return type;
    }

    private String resolveRouteCode(String value) {
        String route = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ROUTE_CODES.contains(route)) {
            throw BusinessException.badRequest("REPORT_ROUTE_INVALID", "盘口线路无效");
        }
        return route;
    }

    /** 空串表示显式清空，非空按长度校验。 */
    private static String optionalText(String value, int maxLength, String code, String message) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > maxLength) {
            throw BusinessException.badRequest(code, message);
        }
        return text;
    }

    /** 上游地址只校验 http/https 前缀与主机名，**不请求**、不解析 DNS。 */
    private static String validateUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw BusinessException.badRequest("REPORT_URL_INVALID", "上游地址必须是有效的 http/https 地址");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("REPORT_URL_INVALID", "上游地址必须是有效的 http/https 地址");
        }
    }

    /** 密码只在这里出现一次：非空即加密，明文不落库、不入日志、不入审计。 */
    private String passwordCiphertext(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return null;
        }
        if (rawPassword.length() > MAX_PASSWORD_LENGTH) {
            throw BusinessException.badRequest("REPORT_CREDENTIAL_INVALID", "飞单密码长度无效");
        }
        return passwordCipher.encrypt(rawPassword);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!REPORT_STATUSES.contains(normalized)) {
            throw BusinessException.badRequest("REPORT_STATUS_INVALID", "上报状态无效");
        }
        return normalized;
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank() && !TYPE_NONE.equalsIgnoreCase(value.trim());
    }

    private void audit(long operator, String method, String path, long machineId, String summary) {
        auditRepository.record(operator, AUDIT_ACTION, method, path, Long.toString(machineId), "SUCCESS",
                null, "machineId=" + machineId + "," + summary, null, Instant.now());
    }

    /**
     * 报网配置读模型；只暴露 {@code hasPassword}，永远不含密码明文或密文。
     *
     * @param reportUsername     飞单账号；{@code inheritedFromGroup = TRUE} 时来自子账号统一飞单
     * @param inheritedFromGroup 本次读取是否走了子账号统一飞单回退（机器自身没有飞单账号时）
     */
    public record ReportConfigView(long machineId, String reportType, boolean reportAuto, boolean reportOnline,
                                   boolean reportWithdraw, String reportUsername, boolean hasPassword,
                                   String reportUrl, String reportNetworkCode, String reportRouteCode,
                                   BigDecimal reportBalance, BigDecimal reportStake, Instant reportLastLoginAt,
                                   long timeoutOrderCount, long unreportedCount, long failedOrderCount,
                                   boolean inheritedFromGroup) {
    }

    /** 报网配置写模型；{@code null} 表示保持原值，{@code reportPassword} 只在非空时覆盖。 */
    public record ReportConfigUpdate(String reportType, Boolean reportAuto, Boolean reportWithdraw,
                                     String reportUsername, String reportPassword, String reportUrl,
                                     String reportNetworkCode, String reportRouteCode) {
    }

    /** 报网订单分页结果。 */
    public record ReportOrderPage(List<ReportUpstreamOrderRepository.OrderRow> items, int page, int pageSize, long total) {
    }

    /** 重报结果；带最新配置快照便于前端一次刷新。 */
    public record ResubmitResult(long betId, String reportStatus, ReportConfigView config) {
    }
}
