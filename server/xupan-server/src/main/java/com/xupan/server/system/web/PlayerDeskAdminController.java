package com.xupan.server.system.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.chat.repository.PlayerPointRequestRepository;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletStatistics;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.agent.service.ScoreLedgerQueryService;
import com.xupan.server.report.service.ReportService;
import com.xupan.server.system.repository.PlayerDeskRepository;
import com.xupan.server.media.AvatarPresetService;
import com.xupan.server.system.service.PlayerDeskAdminService;
import com.xupan.server.system.service.PlayerDeskScopeService;
import com.xupan.server.system.service.PlayerPointOperationService;
import com.xupan.server.system.service.PlayerPointRequestService;
import com.xupan.server.system.service.PlayerPointsReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/player-desk")public class PlayerDeskAdminController {
    private final PlayerDeskAdminService service;
    private final PlayerPointsReportService pointsReportService;
    private final PlayerPointRequestService pointRequestService;
    private final PlayerPointOperationService pointOperationService;
    private final PlayerDeskScopeService scopeService;
    private final ReportService reportService;
    private final ScoreLedgerQueryService scoreLedgerQueryService;

    public PlayerDeskAdminController(PlayerDeskAdminService service, PlayerPointsReportService pointsReportService,
                                     PlayerPointRequestService pointRequestService,
                                     PlayerPointOperationService pointOperationService,
                                     PlayerDeskScopeService scopeService,
                                     ReportService reportService,
                                ScoreLedgerQueryService scoreLedgerQueryService) {
        this.service = service;
        this.pointsReportService = pointsReportService;
        this.pointRequestService = pointRequestService;
        this.pointOperationService = pointOperationService;
        this.scopeService = scopeService;
        this.reportService = reportService;
        this.scoreLedgerQueryService = scoreLedgerQueryService;
    }

    /** 当前登录身份的运营后台范围与档案，供前端渲染头部。 */
    @GetMapping("/scope")
    public ScopeResponse scope(Authentication auth) {
        return ScopeResponse.from(scopeService.requireAccess(user(auth)));
    }

    /**
     * 机器级配置（BY220 {@code robot.php} 顶部状态栏与底部控制区）。
     *
     * <p>机器账号固定读自己；管理员按 {@code machineId} 读取指定机器。</p>
     */
    @GetMapping("/machine-config")
    public PlayerDeskAdminService.MachineConfig machineConfig(Authentication auth,
                                                             @RequestParam(required = false) Long machineId) {
        return service.machineConfig(user(auth), machineId);
    }

    /** 保存机器级配置：代理可改自己那台，管理员按范围改；不改彩种归属。 */
    @PutMapping("/machine-config")
    public PlayerDeskAdminService.MachineConfig updateMachineConfig(Authentication auth,
                                                                   @Valid @RequestBody MachineConfigRequest request) {
        return service.updateMachineConfig(user(auth), request.machineId(), request.toUpdate());
    }

    /** 机器报网配置与上报统计（BY220 {@code robot.php} 报网栏）；密码只返回 {@code hasPassword}。 */
    @GetMapping("/report")
    public ReportConfigResponse report(Authentication auth, @RequestParam(required = false) Long machineId) {
        return ReportConfigResponse.from(reportService.config(user(auth), machineId));
    }

    /** 保存报网类型/账号/密码/上游地址/网盘/盘口/自动上报/失败退单；密码只在非空时覆盖。 */
    @PutMapping("/report")
    public ReportConfigResponse updateReport(Authentication auth, @RequestBody ReportConfigRequest request) {
        return ReportConfigResponse.from(reportService.saveConfig(user(auth), request.machineId(), request.toUpdate()));
    }

    /** 本地模拟登录（不发任何外部请求）：置在线并记录登录时间。 */
    @PostMapping("/report/login")
    public ReportConfigResponse reportLogin(Authentication auth,
                                            @RequestBody(required = false) ReportMachineRequest request) {
        return ReportConfigResponse.from(reportService.login(user(auth), request == null ? null : request.machineId()));
    }

    /** 退出报网：置离线。 */
    @PostMapping("/report/logout")
    public ReportConfigResponse reportLogout(Authentication auth,
                                             @RequestBody(required = false) ReportMachineRequest request) {
        return ReportConfigResponse.from(reportService.logout(user(auth), request == null ? null : request.machineId()));
    }

    /** 按 {@code report_status}（可空=全部）列出本机器范围内的注单。 */
    @GetMapping("/report/orders")
    public ReportOrders reportOrders(Authentication auth, @RequestParam(required = false) Long machineId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int pageSize) {
        return ReportOrders.from(reportService.orders(user(auth), machineId, status, page, pageSize));
    }

    /**
     * 本机器自己的积分流水（代理层账本，Flyway V60 {@code agent_ledger}）。
     *
     * <p>账本原则 2、3 的落地：任何积分变化都有一条追加式流水，这里把它读出来，
     * 使账本可被核对（下一阶段中心上游的第二层结算与对账都依赖它）。</p>
     *
     * <p>BY220 {@code robot.php} 没有"积分流水"菜单，因此本接口只提供数据，
     * **不新增任何菜单页面**，避免发明 BY220 不存在的业务流程。</p>
     */
    @GetMapping("/score-ledger")
    public ScoreLedgerPage scoreLedger(Authentication auth, @RequestParam(required = false) Long machineId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        return ScoreLedgerPage.from(scoreLedgerQueryService.page(user(auth), machineId, page, pageSize));
    }

    /** 重报单笔注单（BY220 {@code reFeidan}）：只允许未报/失败/超时，且注单属于本机器。 */
    @PostMapping("/report/orders/{betId}/resubmit")
    public ReportResubmit resubmitReportOrder(Authentication auth, @PathVariable long betId,
                                              @RequestBody(required = false) ReportMachineRequest request) {
        return ReportResubmit.from(reportService.resubmit(user(auth), request == null ? null : request.machineId(), betId));
    }

    @GetMapping("/summary")
    public Summary summary(Authentication auth, @RequestParam(required = false) String kind,
                           @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return Summary.from(service.summary(kind, status, keyword, includeDeleted, user(auth)));
    }

    @GetMapping("/players")
    public Page players(Authentication auth, @RequestParam(required = false) String kind,
                        @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
                        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
                        @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return Page.from(service.page(kind, status, keyword, page, pageSize, includeDeleted, user(auth)));
    }

    @GetMapping("/players/{userId}")
    public Detail detail(Authentication auth, @PathVariable long userId,
                         @RequestParam(defaultValue = "false") boolean includeDeleted) { return Detail.from(service.detail(userId, includeDeleted, user(auth))); }

    @GetMapping("/points-records")
    public PointsReport pointsRecords(Authentication auth,
                                      @RequestParam String kind,
                                      @RequestParam(required = false) String date,
                                      @RequestParam(defaultValue = "500") int detailLimit) {
        return PointsReport.from(pointsReportService.report(kind, date, detailLimit, user(auth)));
    }

    @GetMapping("/point-requests")
    public List<PointRequest> pointRequests(Authentication auth,
                                            @RequestParam(defaultValue = "100") int limit) {
        return pointRequestService.pending(user(auth), limit).stream().map(PointRequest::from).toList();
    }

    @GetMapping("/point-operations/recent")
    public RecentPointOperations recentPointOperations(
            Authentication auth,
            @RequestParam String kind,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "50") int limit) {
        return RecentPointOperations.from(pointOperationService.recent(kind, beforeId, limit, user(auth)));
    }

    @PostMapping("/point-requests/{requestId}/approve")
    public PointRequest approvePointRequest(Authentication auth, @PathVariable long requestId,
                                            @RequestBody(required = false) ReviewRequest request) {
        PlayerPointRequestService.ReviewResult result = pointRequestService.approve(requestId, user(auth),
                request == null ? null : request.reason());
        return PointRequest.from(result.request());
    }

    @PostMapping("/point-requests/{requestId}/reject")
    public PointRequest rejectPointRequest(Authentication auth, @PathVariable long requestId,
                                           @RequestBody(required = false) ReviewRequest request) {
        PlayerPointRequestService.ReviewResult result = pointRequestService.reject(requestId, user(auth),
                request == null ? null : request.reason());
        return PointRequest.from(result.request());
    }

    @PostMapping("/players/normal")
    public Detail createNormal(Authentication auth, @Valid @RequestBody CreateNormal request) {
        long id = service.createNormal(request.displayName(), request.machineId(), user(auth));
        return Detail.from(service.detail(id, user(auth)));
    }

    @PostMapping("/players/bot")
    public Detail createBot(Authentication auth, @Valid @RequestBody CreateBot request) {
        long id = service.createBot(request.userCode(), request.displayName(), request.machineId(), user(auth));
        return Detail.from(service.detail(id, user(auth)));
    }

    @GetMapping("/avatar-presets")
    public List<AvatarPresetService.AvatarPresetOption> avatarPresets(Authentication auth,
                                                                      @RequestParam(required = false) Long userId) {
        return service.avatarPresets(userId, user(auth));
    }

    @PutMapping("/players/{userId}/avatar")
    public Detail updateAvatar(Authentication auth, @PathVariable long userId,
                               @Valid @RequestBody AvatarUpdate request) {
        return Detail.from(service.replaceAvatar(userId, request.avatarKey(), user(auth)));
    }

    @PatchMapping("/players/{userId}/status")
    public Detail status(Authentication auth, @PathVariable long userId, @Valid @RequestBody StatusRequest request) {
        return Detail.from(service.status(userId, request.status(), user(auth)));
    }

    @DeleteMapping("/players/{userId}")
    public Detail delete(Authentication auth, @PathVariable long userId) {
        return Detail.from(service.delete(userId, user(auth)));
    }

    @PostMapping("/players/{userId}/balance/grants")
    public Detail grant(Authentication auth, @PathVariable long userId, @Valid @RequestBody BalanceRequest request) {
        return Detail.from(service.grant(userId, request.amount(), request.reason(), request.idempotencyKey(), user(auth)));
    }

    @PostMapping("/players/{userId}/balance/adjustments")
    public Detail adjust(Authentication auth, @PathVariable long userId, @Valid @RequestBody BalanceRequest request) {
        return Detail.from(service.adjust(userId, request.amount(), request.reason(), request.idempotencyKey(), user(auth)));
    }

    @PutMapping("/players/{userId}/nickname")
    public Detail updateNickname(Authentication auth, @PathVariable long userId, @Valid @RequestBody NicknameRequest request) {
        return Detail.from(service.updateNickname(userId, request.displayName(), user(auth)));
    }

    @GetMapping("/players/{userId}/name-history")
    public NameHistory nameHistory(Authentication auth, @PathVariable long userId) {
        return NameHistory.from(service.nameHistory(userId, user(auth)));
    }

    @GetMapping("/players/{userId}/behavior")
    public Behavior behavior(Authentication auth, @PathVariable long userId) { return Behavior.from(service.behavior(userId, user(auth))); }

    @PutMapping("/players/{userId}/behavior")
    public Behavior updateBehavior(Authentication auth, @PathVariable long userId, @Valid @RequestBody BehaviorRequest request) {
        return Behavior.from(service.updateBehavior(userId, request.mode(), request.betsPerIssue(),
                request.stakeRangeCode(), request.stakeRoundTen(), request.activityPercent(),
                request.playRandom(), request.playTypes(), request.topupProbabilityPercent(),
                request.topupMin(), request.topupMax(), user(auth)));
    }

    @PostMapping("/players/{userId}/behavior/run-now")
    public List<Action> runNow(Authentication auth, @PathVariable long userId) { return service.runNow(userId, user(auth)).stream().map(Action::from).toList(); }

    @PostMapping("/players/{userId}/messages")
    public MessageOutcome message(Authentication auth, @PathVariable long userId, @Valid @RequestBody MessageRequest request) {
        return MessageOutcome.from(service.sendMessage(userId, request.content(), request.clientMessageId(), user(auth)));
    }

    @GetMapping("/players/{userId}/actions")
    public List<Action> actions(Authentication auth, @PathVariable long userId, @RequestParam(defaultValue = "20") int limit) {
        return service.actions(userId, limit, user(auth)).stream().map(Action::from).toList();
    }

    private static long user(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        return user.getUserId();
    }

    public record Summary(BigDecimal totalPoints, long normalCount, long botCount) {
        static Summary from(PlayerDeskRepository.Summary x) { return new Summary(x.totalPoints(), x.normalCount(), x.botCount()); }
    }
    /** 当前身份的范围与档案；GLOBAL 与 GROUP 模式下机器字段为 null。 */
    public record ScopeResponse(String mode, Long machineId, String machineCode, String machineName,
                                String machineStatus, BigDecimal score, Long botLimit, Long playerCount,
                                Long botCount, Long groupId, String groupName) {
        static ScopeResponse from(PlayerDeskScopeService.Scope scope) {
            if (scope instanceof PlayerDeskScopeService.Scope.Machine machine) {
                return new ScopeResponse(machine.mode(), machine.agentId(), machine.agentCode(),
                        machine.agentName(), machine.status(), machine.score(), machine.botLimit(),
                        machine.playerCount(), machine.botCount(), machine.groupId(), machine.groupName());
            }
            if (scope instanceof PlayerDeskScopeService.Scope.Group group) {
                return new ScopeResponse(group.mode(), null, null, null, null, null, null, null, null,
                        group.groupId(), group.groupName());
            }
            return new ScopeResponse(PlayerDeskScopeService.Scope.Global.INSTANCE.mode(),
                    null, null, null, null, null, null, null, null, null, null);
        }
    }
    public record Page(List<Item> items, int page, int pageSize, long total) {
        static Page from(PlayerDeskAdminService.Page x) { return new Page(x.items().stream().map(Item::from).toList(), x.page(), x.pageSize(), x.total()); }
    }
    public record Item(long userId, long accountId, String internalCode, String memberCode, String userCode,
                       String username, String displayName, String avatarKey, String authMode, String status,
                       String playerKind, String userType, BigDecimal balance, Instant createdAt,
                       Instant lastLoginAt, boolean behaviorEnabled, Instant lastActionAt) {
        static Item from(PlayerDeskRepository.PlayerRow x) { return new Item(x.userId(), x.accountId(), x.internalCode(), x.memberCode(), x.userCode(), x.username(), x.displayName(), x.avatarKey(), x.authMode(), x.userStatus(), x.playerKind(), x.userType(), x.balance(), x.createdAt(), x.lastLoginAt(), x.behaviorEnabled(), x.lastActionAt()); }
    }
    public record Detail(long userId, long accountId, String internalCode, String memberCode, String userCode,
                         String username, String displayName, String avatarKey, String authMode, String status,
                         String playerKind, String userType, BigDecimal balance, Instant createdAt,
                         Instant lastLoginAt, boolean behaviorEnabled, Instant lastActionAt, LinkStatus linkStatus, WalletStats walletStatistics,
                         List<Ledger> ledger, List<Action> recentActions, Behavior behavior, List<Bet> bets) {
        static Detail from(PlayerDeskAdminService.Detail x) {
            Item p = Item.from(x.player());
            LinkStatus link = x.linkStatus() == null ? null : LinkStatus.from(x.linkStatus());
            return new Detail(p.userId(), p.accountId(), p.internalCode(), p.memberCode(), p.userCode(), p.username(), p.displayName(), p.avatarKey(), p.authMode(), p.status(),
                    p.playerKind(), p.userType(), p.balance(), p.createdAt(), p.lastLoginAt(), p.behaviorEnabled(), p.lastActionAt(),
                    link, WalletStats.from(x.walletStatistics()), x.ledger().stream().map(Ledger::from).toList(),
                    x.actions().stream().map(Action::from).toList(), Behavior.from(x.behavior()), x.bets().stream().map(Bet::from).toList());
        }
    }
    public record PointsReport(String businessDate, Instant fromInclusive, Instant toExclusive, List<String> availableDates, String playerKind,
                               PointsSummary summary, List<PointsCard> players, int detailLimit) {
        static PointsReport from(PlayerPointsReportService.Report x) {
            return new PointsReport(x.businessDate().toString(), x.fromInclusive(), x.toExclusive(), x.availableDates().stream().map(Object::toString).toList(), x.playerKind(),
                    PointsSummary.from(x.summary()), x.players().stream().map(PointsCard::from).toList(), x.detailLimit());
        }
    }
    public record PointsSummary(int playerCount, long betCount, long settledBetCount, BigDecimal turnover,
                                BigDecimal netProfit, BigDecimal topUp, BigDecimal down,
                                BigDecimal openingBalance, BigDecimal closingBalance) {
        static PointsSummary from(PlayerPointsReportService.Summary x) {
            return new PointsSummary(x.playerCount(), x.betCount(), x.settledBetCount(), x.turnover(), x.netProfit(),
                    x.topUp(), x.down(), x.openingBalance(), x.closingBalance());
        }
    }
    public record PointsCard(long userId, long accountId, String memberCode, String userCode, String displayName,
                             String playerKind, BigDecimal openingBalance, BigDecimal closingBalance,
                             long betCount, long settledBetCount, BigDecimal turnover, BigDecimal netProfit,
                             BigDecimal topUp, BigDecimal down, List<BetDetail> bets,
                             List<PointsOperation> pointOperations, List<BotAction> botActions) {
        static PointsCard from(PlayerPointsReportService.Card x) {
            return new PointsCard(x.userId(), x.accountId(), x.memberCode(), x.userCode(), x.displayName(), x.playerKind(),
                    x.openingBalance(), x.closingBalance(), x.betCount(), x.settledBetCount(), x.turnover(), x.netProfit(),
                    x.topUp(), x.down(), x.bets().stream().map(BetDetail::from).toList(),
                    x.pointOperations().stream().map(PointsOperation::from).toList(),
                    x.botActions().stream().map(BotAction::from).toList());
        }
    }
    public record BetDetail(long id, String betCode, String issueNumber, int ballNumber, String playType,
                            String parametersText, BigDecimal stake, BigDecimal odds, String settlementStatus,
                            BigDecimal netProfit, String explanation, Instant createdAt, Instant settledAt) {
        static BetDetail from(PlayerPointsReportService.BetDetail x) {
            return new BetDetail(x.id(), x.betCode(), x.issueNumber(), x.ballNumber(), x.playType(), x.parametersText(),
                    x.stake(), x.odds(), x.settlementStatus(), x.netProfit(), x.explanation(), x.createdAt(), x.settledAt());
        }
    }
    public record PointsOperation(long id, String operationType, BigDecimal amount, BigDecimal balanceBefore,
                                  BigDecimal balanceAfter, Long operatorUserId, String operatorName,
                                  String idempotencyKey, Long relatedBetId, String issueNumber, String reason,
                                  Instant createdAt) {
        static PointsOperation from(PlayerPointsReportService.PointsOperation x) {
            return new PointsOperation(x.id(), x.operationType(), x.amount(), x.balanceBefore(), x.balanceAfter(),
                    x.operatorUserId(), x.operatorName(), x.idempotencyKey(), x.relatedBetId(), x.issueNumber(),
                    x.reason(), x.createdAt());
        }
    }
    public record BotAction(long id, String issueNumber, int actionNo, String actionType, String sourceText,
                            String status, int attempts, String errorCode, String errorMessage, Long messageId,
                            Long betId, Instant createdAt, Instant updatedAt) {
        static BotAction from(PlayerPointsReportService.BotAction x) {
            return new BotAction(x.id(), x.issueNumber(), x.actionNo(), x.actionType(), x.sourceText(), x.status(),
                    x.attempts(), x.errorCode(), x.errorMessage(), x.messageId(), x.betId(), x.createdAt(), x.updatedAt());
        }
    }
    public record LinkStatus(long linkId, String scope, Instant expiresAt, Instant revokedAt, Instant lastUsedAt, boolean active, int configuredDays) {
        static LinkStatus from(PlayerDeskAdminService.LinkStatus x) { return new LinkStatus(x.linkId(), x.scope(), x.expiresAt(), x.revokedAt(), x.lastUsedAt(), x.active(), x.configuredDays()); }
    }
    public record WalletStats(long totalBetCount, long settledBetCount, long pendingBetCount, BigDecimal totalStake, BigDecimal settledStake, BigDecimal pendingStake, BigDecimal netProfit) {
        static WalletStats from(WalletStatistics x) { return x == null ? new WalletStats(0,0,0,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO) : new WalletStats(x.totalBetCount(), x.settledBetCount(), x.pendingBetCount(), x.totalStake(), x.settledStake(), x.pendingStake(), x.netProfit()); }
    }
    public record Ledger(long id, long accountId, long userId, String operationType, BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter, Long operatorUserId, String operatorName, String idempotencyKey, Long relatedBetId, String issueNumber, String reason, Instant createdAt) {
        static Ledger from(WalletLedgerEntry x) { return new Ledger(x.id(), x.accountId(), x.userId(), x.operationType().name(), x.amount(), x.balanceBefore(), x.balanceAfter(), x.operatorUserId(), x.operatorName(), x.idempotencyKey(), x.relatedBetId(), x.issueNumber(), x.reason(), x.createdAt()); }
    }
    public record Behavior(Long id, Long accountId, String mode, boolean enabled, int betsPerIssue, BigDecimal stakeMin, BigDecimal stakeMax, boolean chatEnabled, int messagesPerIssue, Instant nextRunAt, String lastIssueNumber, String lastErrorCode, String lastErrorMessage, long version, Instant updatedAt,
                           String stakeRangeCode, String stakeRoundTen, int activityPercent, boolean playRandom, List<String> playTypes,
                           int topupProbabilityPercent, BigDecimal topupMin, BigDecimal topupMax) {
        static Behavior from(PlayerDeskAdminService.BehaviorConfig x) {
            if (x == null) return null;
            PlayerDeskRepository.Behavior b = x.behavior();
            return new Behavior(b.id(), b.accountId(), b.mode(), b.enabled(), b.betsPerIssue(), b.stakeMin(),
                    b.stakeMax(), b.chatEnabled(), b.messagesPerIssue(), b.nextRunAt(), b.lastIssueNumber(),
                    b.lastErrorCode(), b.lastErrorMessage(), b.version(), b.updatedAt(), b.stakeRangeCode(),
                    b.stakeRoundTen(), b.activityPercent(), b.playRandom(), x.playTypes(),
                    b.topupProbabilityPercent(), b.topupMin(), b.topupMax());
        }
    }
    public record Action(long id, String issueNumber, int actionNo, String actionType, String sourceText, String status, int attempts, String errorCode, String errorMessage, Long messageId, Long betId, Instant createdAt, Instant updatedAt) {
        static Action from(PlayerDeskRepository.ActionRow x) { return new Action(x.id(), x.issueNumber(), x.actionNo(), x.actionType(), x.sourceText(), x.status(), x.attempts(), x.errorCode(), x.errorMessage(), x.messageId(), x.betId(), x.createdAt(), x.updatedAt()); }
    }
    public record Bet(String id, String issueNumber, int ballNumber, PlayType playType, List<Integer> parameters,
                      BigDecimal stake, BigDecimal odds, SettlementStatus settlementStatus, BigDecimal netProfit,
                      String explanation) {
        static Bet from(GameDataRepository.BetRecord x) {
            return new Bet(x.betCode(), x.issueNumber(), x.ballNumber(), x.playType(), x.parameters(), x.stake(),
                    x.odds(), x.settlementStatus(), x.netProfit(), x.explanation());
        }
    }
    /** 机器级配置请求体；字段与 {@link PlayerDeskAdminService.MachineConfigUpdate} 一一对应。 */
    public record MachineConfigRequest(Long machineId, boolean boardOpen, boolean chaseEnabled, int botCount,
                                       int closeSeconds, int cancelSeconds, BigDecimal rebateRate, BigDecimal oddsRate,
                                       BigDecimal specialRebateRate, BigDecimal specialOddsRate, String rebateMode,
                                       BigDecimal totalLimit, BigDecimal positiveLimit, BigDecimal angleLimit,
                                       BigDecimal strictLimit, BigDecimal tongLimit, BigDecimal carLimit,
                                       BigDecimal specialLimit, BigDecimal oddEvenLimit, BigDecimal bigSmallLimit,
                                       BigDecimal fanLimit, BigDecimal addLimit, BigDecimal playerMinStake,
                                       BigDecimal playerMaxStake) {
        PlayerDeskAdminService.MachineConfigUpdate toUpdate() {
            return new PlayerDeskAdminService.MachineConfigUpdate(boardOpen, chaseEnabled, botCount, closeSeconds,
                    cancelSeconds, rebateRate, oddsRate, specialRebateRate, specialOddsRate, rebateMode,
                    totalLimit, positiveLimit, angleLimit, strictLimit, tongLimit, carLimit, specialLimit,
                    oddEvenLimit, bigSmallLimit, fanLimit, addLimit, playerMinStake, playerMaxStake);
        }
    }

    public record CreateNormal(@NotBlank String displayName, Long machineId) {}    public record CreateBot(String userCode, @NotBlank String displayName, Long machineId) {}
    public record StatusRequest(@NotBlank String status) {}
    public record AvatarUpdate(@NotBlank String avatarKey) {}
    public record BalanceRequest(@NotNull BigDecimal amount, @NotBlank String reason, @NotBlank String idempotencyKey) {}
    public record NicknameRequest(@NotBlank String displayName) {}
    public record NameHistory(String currentName, long remainingToday, List<NameChange> records) {
        static NameHistory from(PlayerDeskAdminService.NameHistory x) {
            return new NameHistory(x.currentName(), x.remainingToday(), x.records().stream().map(NameChange::from).toList());
        }
    }
    public record NameChange(long id, String oldName, String newName, Instant changedAt) {
        static NameChange from(PlayerDeskAdminService.NameChange x) {
            return new NameChange(x.id(), x.oldName(), x.newName(), x.changedAt());
        }
    }
    public record BehaviorRequest(@NotBlank String mode, @Min(0) @Max(20) int betsPerIssue,
                                  @NotBlank String stakeRangeCode, @NotBlank String stakeRoundTen,
                                  @Min(0) @Max(100) int activityPercent, boolean playRandom,
                                  List<String> playTypes, @Min(0) @Max(100) int topupProbabilityPercent,
                                  @NotNull @DecimalMin("0.01") BigDecimal topupMin,
                                  @NotNull @DecimalMin("0.01") BigDecimal topupMax) {}
    public record MessageRequest(@NotBlank String content, @NotBlank String clientMessageId) {}
    public record ReviewRequest(String reason) {}
    public record PointRequest(long id, long userId, String requestType, BigDecimal amount, String status,
                               String clientMessageId, long sourceMessageId, Instant requestedAt,
                               String displayName, String memberCode, String playerKind) {
        static PointRequest from(PlayerPointRequestRepository.Request x) {
            return new PointRequest(x.id(), x.userId(), x.requestType(), x.amount(), x.status(),
                    x.clientMessageId(), x.sourceMessageId(), x.requestedAt(),
                    x.displayName(), x.memberCode(), x.playerKind());
        }
    }
    public record RecentPointOperations(String kind, String businessDate, Instant fromInclusive,
                                        Instant toExclusive, List<RecentPointOperation> items,
                                        Long nextBeforeId, boolean hasMore) {
        static RecentPointOperations from(PlayerPointOperationService.Recent x) {
            return new RecentPointOperations(x.kind(), x.businessDate().toString(), x.fromInclusive(),
                    x.toExclusive(), x.items().stream().map(RecentPointOperation::from).toList(),
                    x.nextBeforeId(), x.hasMore());
        }
    }
    public record RecentPointOperation(long ledgerId, long userId, String memberCode,
                                       String displayName, String playerKind, String operationType,
                                       String direction, BigDecimal amount, BigDecimal balanceAfter,
                                       String reason, Instant createdAt) {
        static RecentPointOperation from(PlayerPointOperationService.Item x) {
            return new RecentPointOperation(x.ledgerId(), x.userId(), x.memberCode(), x.displayName(),
                    x.playerKind(), x.operationType(), x.direction(), x.amount(), x.balanceAfter(),
                    x.reason(), x.createdAt());
        }
    }
    public record MessageOutcome(ChatMessage message, boolean replayed, String feedback, Object bet) {
        static MessageOutcome from(ChatMessageService.ChatMessageSendOutcome x) { return new MessageOutcome(x.message(), x.deduplicated(), null, null); }
    }
    /** 报网配置请求体；{@code null} 字段表示保持原值，{@code reportPassword} 只在非空时覆盖。 */
    public record ReportConfigRequest(Long machineId, String reportType, Boolean reportAuto, Boolean reportWithdraw,
                                      String reportUsername, String reportPassword, String reportUrl,
                                      String reportNetworkCode, String reportRouteCode) {
        ReportService.ReportConfigUpdate toUpdate() {
            return new ReportService.ReportConfigUpdate(reportType, reportAuto, reportWithdraw, reportUsername,
                    reportPassword, reportUrl, reportNetworkCode, reportRouteCode);
        }
    }
    /** 只带机器选择的报网请求体（登录/退出/重报）。 */
    public record ReportMachineRequest(Long machineId) {}
    /** 报网配置响应；只含 {@code hasPassword} 布尔，绝不含密码明文或密文。 */
    public record ReportConfigResponse(long machineId, String reportType, boolean reportAuto, boolean reportOnline,
                                       boolean reportWithdraw, String reportUsername, boolean hasPassword,
                                       String reportUrl, String reportNetworkCode, String reportRouteCode,
                                       BigDecimal reportBalance, BigDecimal reportStake, Instant reportLastLoginAt,
                                       long timeoutOrderCount, long unreportedCount, long failedOrderCount) {
        static ReportConfigResponse from(ReportService.ReportConfigView x) {
            return new ReportConfigResponse(x.machineId(), x.reportType(), x.reportAuto(), x.reportOnline(),
                    x.reportWithdraw(), x.reportUsername(), x.hasPassword(), x.reportUrl(), x.reportNetworkCode(),
                    x.reportRouteCode(), x.reportBalance(), x.reportStake(), x.reportLastLoginAt(),
                    x.timeoutOrderCount(), x.unreportedCount(), x.failedOrderCount());
        }
    }
    /** 报网订单分页响应。 */
    /** 一页代理积分流水（账本原则 2、3）。 */
    public record ScoreLedgerPage(long machineId, List<ScoreLedgerEntry> items, int page, int pageSize,
                                  long total, boolean balanced) {
        static ScoreLedgerPage from(ScoreLedgerQueryService.Page x) {
            return new ScoreLedgerPage(x.machineId(), x.items().stream().map(ScoreLedgerEntry::from).toList(),
                    x.page(), x.pageSize(), x.total(), x.balanced());
        }
    }

    /**
     * 一行代理积分流水。
     *
     * @param direction IN 增加 / OUT 减少
     * @param amount    变动额（正数，方向由 {@code direction} 表达）
     */
    public record ScoreLedgerEntry(long id, String entryType, String direction, BigDecimal amount,
                                   BigDecimal balanceBefore, BigDecimal balanceAfter, String reason,
                                   String referenceType, String referenceId, Instant createdAt) {
        static ScoreLedgerEntry from(com.xupan.server.agent.repository.AgentLedgerRepository.LedgerRow x) {
            return new ScoreLedgerEntry(x.id(), x.entryType(), x.direction(), x.amount(), x.balanceBefore(),
                    x.balanceAfter(), x.reason(), x.referenceType(), x.referenceId(), x.createdAt());
        }
    }

    public record ReportOrders(List<ReportOrder> items, int page, int pageSize, long total) {        static ReportOrders from(ReportService.ReportOrderPage x) {
            return new ReportOrders(x.items().stream().map(ReportOrder::from).toList(), x.page(), x.pageSize(),
                    x.total());
        }
    }
    public record ReportOrder(long betId, String betCode, long accountId, String memberCode, String displayName,
                              String issueNumber, int ballNumber, String playType, String parametersText,
                              BigDecimal stake, BigDecimal odds, String settlementStatus, String reportStatus,
                              Instant createdAt) {
        static ReportOrder from(com.xupan.server.report.repository.ReportUpstreamOrderRepository.OrderRow x) {
            return new ReportOrder(x.betId(), x.betCode(), x.accountId(), x.memberCode(), x.displayName(),
                    x.issueNumber(), x.ballNumber(), x.playType(), x.parametersText(), x.stake(), x.odds(),
                    x.settlementStatus(), x.reportStatus(), x.createdAt());
        }
    }
    /** 重报结果：本次回写的上报状态 + 最新配置快照。 */
    public record ReportResubmit(long betId, String reportStatus, ReportConfigResponse config) {
        static ReportResubmit from(ReportService.ResubmitResult x) {
            return new ReportResubmit(x.betId(), x.reportStatus(), ReportConfigResponse.from(x.config()));
        }
    }
}
