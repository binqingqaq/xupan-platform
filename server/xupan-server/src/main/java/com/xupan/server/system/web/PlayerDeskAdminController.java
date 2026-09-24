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
import com.xupan.server.system.repository.PlayerDeskRepository;
import com.xupan.server.system.service.PlayerDeskAdminService;
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
@RequestMapping("/api/admin/player-desk")
public class PlayerDeskAdminController {
    private final PlayerDeskAdminService service;
    private final PlayerPointsReportService pointsReportService;
    private final PlayerPointRequestService pointRequestService;
    private final PlayerPointOperationService pointOperationService;

    public PlayerDeskAdminController(PlayerDeskAdminService service, PlayerPointsReportService pointsReportService,
                                     PlayerPointRequestService pointRequestService,
                                     PlayerPointOperationService pointOperationService) {
        this.service = service;
        this.pointsReportService = pointsReportService;
        this.pointRequestService = pointRequestService;
        this.pointOperationService = pointOperationService;
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
        long id = service.createNormal(request.displayName(), user(auth));
        return Detail.from(service.detail(id, user(auth)));
    }

    @PostMapping("/players/bot")
    public Detail createBot(Authentication auth, @Valid @RequestBody CreateBot request) {
        long id = service.createBot(request.userCode(), request.displayName(), request.avatarKey(), user(auth));
        return Detail.from(service.detail(id, user(auth)));
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
        return Behavior.from(service.updateBehavior(userId, request.mode(), request.betsPerIssue(), request.stakeMin(), request.stakeMax(), request.chatEnabled(), request.messagesPerIssue(), user(auth)));
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
    public record Behavior(Long id, Long accountId, String mode, boolean enabled, int betsPerIssue, BigDecimal stakeMin, BigDecimal stakeMax, boolean chatEnabled, int messagesPerIssue, Instant nextRunAt, String lastIssueNumber, String lastErrorCode, String lastErrorMessage, long version, Instant updatedAt) {
        static Behavior from(PlayerDeskRepository.Behavior x) { return x == null ? null : new Behavior(x.id(), x.accountId(), x.mode(), x.enabled(), x.betsPerIssue(), x.stakeMin(), x.stakeMax(), x.chatEnabled(), x.messagesPerIssue(), x.nextRunAt(), x.lastIssueNumber(), x.lastErrorCode(), x.lastErrorMessage(), x.version(), x.updatedAt()); }
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
    public record CreateNormal(@NotBlank String displayName) {}
    public record CreateBot(String userCode, @NotBlank String displayName, String avatarKey) {}
    public record StatusRequest(@NotBlank String status) {}
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
    public record BehaviorRequest(@NotBlank String mode, @Min(0) @Max(20) int betsPerIssue, @NotNull @DecimalMin("0.01") BigDecimal stakeMin, @NotNull @DecimalMin("0.01") BigDecimal stakeMax, boolean chatEnabled, @Min(0) @Max(20) int messagesPerIssue) {}
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
}
