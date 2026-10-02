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
import com.xupan.server.system.service.PlayerDeskScopeService;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
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
 * <p>权限规则：读走 {@code AGENT_CONSOLE_READ}（由 URL 层放行），写操作要求
 * {@code USER_MANAGE}（平台管理员）或 {@code AGENT_PLAYER_MANAGE}（机器/子账号）。</p>
 *
 * <p>密码规则：写接口只接受明文入参并**立即加密**，只落
 * {@code agent.report_password_ciphertext}；读接口只返回 {@code hasPassword} 布尔，
 * 明文与密文都不出现在响应、日志或审计里。</p>
 */
@Service
public class ReportService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReportService.class);

    /** 审计动作码（BY220 报网配置）。 */
    public static final String AUDIT_ACTION = "AGENT_REPORT_CONFIG";

    /** 允许的报网类型，与 V56 的 {@code ck_agent_report_type} 保持一致。 */
    public static final Set<String> REPORT_TYPES =
            Set.of("NONE", "TT", "K28", "LS8899", "WNS", "LX", "HY", "CENTER");
    /** 允许的盘口线路，与 V56 的 {@code ck_agent_report_route} 保持一致。 */
    public static final Set<String> ROUTE_CODES = Set.of("A", "B", "C", "D");
    /** 订单上报状态，与 V56 的 {@code ck_game_bet_report_status} 保持一致。 */
    public static final Set<String> REPORT_STATUSES = Set.of("UNREPORTED", "REPORTED", "FAILED", "TIMEOUT");
    /** 允许重报的当前状态（对应 BY220 {@code reFeidan}）。 */
    public static final Set<String> RESUBMITTABLE_STATUSES = Set.of("UNREPORTED", "FAILED", "TIMEOUT");
    /** 尚未上报、可供自动上报链路处理的当前状态。 */
    public static final List<String> SUBMIT_FROM_STATUSES = List.of("UNREPORTED");
    /** BY220 报网类型里的"无"。 */
    private static final String TYPE_NONE = "NONE";
    private static final int MAX_NETWORK_CODE_LENGTH = 64;
    private static final int MAX_USERNAME_LENGTH = 128;
    private static final int MAX_URL_LENGTH = 512;
    /** 明文上限：加密后仍能放进 {@code VARCHAR(512)} 的密文列。 */
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReportConfigRepository repository;
    private final PlayerDeskScopeService scopeService;
    private final PermissionService permissionService;
    private final OperationAuditRepository auditRepository;
    private final ReportAdapter reportAdapter;
    private final PlayerLinkTokenCipher passwordCipher;
    private final GameDataRepository gameDataRepository;
    private final VirtualWalletService walletService;
    private final ChatMessageService chatMessageService;

    public ReportService(ReportConfigRepository repository, PlayerDeskScopeService scopeService,
                         PermissionService permissionService, OperationAuditRepository auditRepository,
                         ReportAdapter reportAdapter, PlayerLinkTokenCipher passwordCipher,
                         GameDataRepository gameDataRepository, VirtualWalletService walletService,
                         ChatMessageService chatMessageService) {
        this.repository = repository;
        this.scopeService = scopeService;
        this.permissionService = permissionService;
        this.auditRepository = auditRepository;
        this.reportAdapter = reportAdapter;
        this.passwordCipher = passwordCipher;
        this.gameDataRepository = gameDataRepository;
        this.walletService = walletService;
        this.chatMessageService = chatMessageService;
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
        ReportConfigRepository.ReportConfigUpdate applied = new ReportConfigRepository.ReportConfigUpdate(
                resolveReportType(update.reportType(), current.reportType()),
                update.reportAuto() == null ? current.reportAuto() : update.reportAuto(),
                update.reportWithdraw() == null ? current.reportWithdraw() : update.reportWithdraw(),
                update.reportUsername() == null ? current.reportUsername()
                        : optionalText(update.reportUsername(), MAX_USERNAME_LENGTH, "REPORT_USERNAME_INVALID", "飞单账号无效"),
                passwordCiphertext(update.reportPassword()),
                update.reportUrl() == null ? current.reportUrl() : validateUrl(update.reportUrl()),
                update.reportNetworkCode() == null ? current.reportNetworkCode()
                        : optionalText(update.reportNetworkCode(), MAX_NETWORK_CODE_LENGTH,
                        "REPORT_NETWORK_CODE_INVALID", "网盘编码无效"),
                update.reportRouteCode() == null ? current.reportRouteCode()
                        : resolveRouteCode(update.reportRouteCode()));
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
        audit(operator, "POST", "/api/admin/player-desk/report/login", targetAgentId, "reportOnline=true");
        return view(targetAgentId);
    }

    /** 退出报网：只置 {@code report_online=false}，保留配置与上次登录时间。 */
    @Transactional
    public ReportConfigView logout(long operator, Long machineId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        requireConfig(targetAgentId);
        repository.markOffline(targetAgentId);
        audit(operator, "POST", "/api/admin/player-desk/report/logout", targetAgentId, "reportOnline=false");
        return view(targetAgentId);
    }

    /** 按 {@code report_status}（可空=全部）列出本机器范围内的注单。 */
    @Transactional(readOnly = true)
    public ReportOrderPage orders(long operator, Long machineId, String status, int page, int pageSize) {
        long targetAgentId = targetAgentId(operator, machineId);
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("REPORT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        String normalizedStatus = normalizeStatus(status);
        List<ReportConfigRepository.OrderRow> items =
                repository.findOrders(targetAgentId, normalizedStatus, page, pageSize);
        return new ReportOrderPage(items, page, pageSize,
                repository.countOrders(targetAgentId, normalizedStatus));
    }

    /**
     * 重报单笔注单（对应 BY220 {@code reFeidan}）。
     *
     * <p>只允许当前状态为 {@code UNREPORTED/FAILED/TIMEOUT} 且属于该机器的注单；
     * 范围外的注单按"不存在"返回 404，状态不允许返回 409。
     * 适配器结果与状态回写在**同一事务**内完成，并写审计（不含任何凭据）。</p>
     */
    @Transactional
    public ResubmitResult resubmit(long operator, Long machineId, long betId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        ReportConfigRepository.BetRow bet = repository.findBetForAgent(betId, targetAgentId)
                .orElseThrow(() -> BusinessException.notFound("REPORT_BET_NOT_FOUND", "注单不存在或不在当前机器范围内"));
        if (!RESUBMITTABLE_STATUSES.contains(bet.reportStatus())) {
            throw BusinessException.conflict("REPORT_BET_STATUS_INVALID", "该注单当前上报状态不允许重报");
        }
        ReportConfigRepository.ReportConfigRow config = requireConfig(targetAgentId);
        String newStatus = submit(bet, config);
        int updated = repository.updateReportStatus(betId, targetAgentId, newStatus,
                List.copyOf(RESUBMITTABLE_STATUSES));
        if (updated != 1) {
            throw BusinessException.conflict("REPORT_BET_STATUS_INVALID", "该注单当前上报状态不允许重报");
        }
        audit(operator, "POST", "/api/admin/player-desk/report/orders/" + betId + "/resubmit", targetAgentId,
                "betId=" + betId + ",reportStatus=" + newStatus);
        return new ResubmitResult(betId, newStatus, view(targetAgentId));
    }

    /**
     * 供下注链路调用的自动上报入口。
     *
     * <p>仅当注单当前状态为 {@code UNREPORTED}、机器的 {@code report_auto=true} 且
     * {@code report_online=true}、并且 {@code report_type} 已配置为非 {@code NONE} 时才提交；
     * 其余情况直接跳过（返回 {@link Optional#empty()}），不影响下注本身。</p>
     *
     * <p><b>边界（本阶段明确不做）</b>：失败退单只做到"标记状态"。
     * 本方法**不修改玩家钱包、不删除注单、不写退款流水**；
     * 失败退单的物理删除 + 幂等退款 + 聊天室取消反馈由后续专门改动接手
     * （对应计划第 11.2 节的失败退单路径）。{@code TIMEOUT} 一律只标记，不退款。</p>
     *
     * @return 本次提交后的上报状态；未提交时为 {@link Optional#empty()}
     */
    @Transactional
    public Optional<String> submitForBet(long betId) {
        if (betId <= 0) {
            return Optional.empty();
        }
        ReportConfigRepository.BetRow bet = repository.findBet(betId).orElse(null);
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
        String newStatus = submit(bet, config);
        if ("FAILED".equals(newStatus) && withdrawOnFailure(bet, config)) {
            // 已按 BY220 失败退单：注单被物理删除并退还本金，不再回写状态
            return Optional.of("WITHDRAWN");
        }
        int updated = repository.updateReportStatus(betId, bet.agentId(), newStatus, SUBMIT_FROM_STATUSES);
        if (updated == 1 && "REPORTED".equals(newStatus)) {
            // 押注为本地模拟值：累计"已成功上报"的投注额（BY220 本地登录时 betting 也为 0，真值来自真实上游）
            repository.addReportedStake(bet.agentId(), bet.stake());
        }
        return updated == 1 ? Optional.of(newStatus) : Optional.empty();
    }

    /**
     * 批量上报（BY220 {@code Websocket.php:658-673} 的飞单轮询）。
     *
     * <p>BY220 每 2.4 秒捞出"未报/失败"的订单重新飞单；xupan 由 {@code ReportDispatchScheduler}
     * 以可配置间隔调用本方法，只处理已开启自动上报、在线且报网类型已配置的机器。</p>
     *
     * @param includeRetry true 时一并重报 {@code FAILED/TIMEOUT}（对应 BY220 轮询捞 {@code flyers_status=3}）
     * @return 处理条数与退单条数
     */
    @Transactional
    public DispatchResult dispatchPending(boolean includeRetry, int limit) {
        List<String> statuses = includeRetry
                ? List.of("UNREPORTED", "FAILED", "TIMEOUT")
                : SUBMIT_FROM_STATUSES;
        List<ReportConfigRepository.BetRow> bets = repository.findSubmittableBets(statuses, limit);
        int submitted = 0;
        int withdrawn = 0;
        for (ReportConfigRepository.BetRow bet : bets) {
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

    private String submit(ReportConfigRepository.BetRow bet, ReportConfigRepository.ReportConfigRow config) {
        ReportCommand command = new ReportCommand(bet.betId(), bet.accountId(), bet.agentId(), bet.issueNumber(),
                bet.playType(), bet.parametersText(), bet.stake(), bet.odds(), config.reportType(),
                config.reportNetworkCode(), config.reportRouteCode(), config.reportOnline());
        return mapOutcome(reportAdapter.submit(command));
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
    private boolean withdrawOnFailure(ReportConfigRepository.BetRow bet,
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
            case ACCEPTED -> "REPORTED";
            case REJECTED -> "FAILED";
            case TIMEOUT -> "TIMEOUT";
        };
    }

    private ReportConfigView view(long agentId) {
        ReportConfigRepository.ReportConfigRow config = requireConfig(agentId);
        ReportConfigRepository.StatusCounts counts = repository.counts(agentId);
        return new ReportConfigView(config.agentId(), config.reportType(), config.reportAuto(),
                config.reportOnline(), config.reportWithdraw(), config.reportUsername(), config.hasPassword(),
                config.reportUrl(), config.reportNetworkCode(), config.reportRouteCode(), config.reportBalance(),
                config.reportStake(), config.reportLastLoginAt(), counts.timeoutCount(), counts.unreportedCount(),
                counts.failedCount());
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

    /** 报网配置读模型；只暴露 {@code hasPassword}，永远不含密码明文或密文。 */
    public record ReportConfigView(long machineId, String reportType, boolean reportAuto, boolean reportOnline,
                                   boolean reportWithdraw, String reportUsername, boolean hasPassword,
                                   String reportUrl, String reportNetworkCode, String reportRouteCode,
                                   BigDecimal reportBalance, BigDecimal reportStake, Instant reportLastLoginAt,
                                   long timeoutOrderCount, long unreportedCount, long failedOrderCount) {
    }

    /** 报网配置写模型；{@code null} 表示保持原值，{@code reportPassword} 只在非空时覆盖。 */
    public record ReportConfigUpdate(String reportType, Boolean reportAuto, Boolean reportWithdraw,
                                     String reportUsername, String reportPassword, String reportUrl,
                                     String reportNetworkCode, String reportRouteCode) {
    }

    /** 报网订单分页结果。 */
    public record ReportOrderPage(List<ReportConfigRepository.OrderRow> items, int page, int pageSize, long total) {
    }

    /** 重报结果；带最新配置快照便于前端一次刷新。 */
    public record ResubmitResult(long betId, String reportStatus, ReportConfigView config) {
    }
}
