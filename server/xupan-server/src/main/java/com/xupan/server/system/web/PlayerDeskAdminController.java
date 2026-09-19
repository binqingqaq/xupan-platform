package com.xupan.server.system.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletStatistics;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.system.repository.PlayerDeskRepository;
import com.xupan.server.system.service.PlayerDeskAdminService;
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

    public PlayerDeskAdminController(PlayerDeskAdminService service) { this.service = service; }

    @GetMapping("/summary")
    public Summary summary(Authentication auth, @RequestParam(required = false) String kind,
                           @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return Summary.from(service.summary(kind, status, keyword, user(auth)));
    }

    @GetMapping("/players")
    public Page players(Authentication auth, @RequestParam(required = false) String kind,
                        @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
                        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return Page.from(service.page(kind, status, keyword, page, pageSize, user(auth)));
    }

    @GetMapping("/players/{userId}")
    public Detail detail(Authentication auth, @PathVariable long userId) { return Detail.from(service.detail(userId, user(auth))); }

    @PostMapping("/players/normal")
    public Detail createNormal(Authentication auth, @Valid @RequestBody CreateNormal request) {
        long id = service.createNormal(request.username(), request.displayName(), request.rawPassword(), user(auth));
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

    @PostMapping("/players/{userId}/balance/grants")
    public Detail grant(Authentication auth, @PathVariable long userId, @Valid @RequestBody BalanceRequest request) {
        return Detail.from(service.grant(userId, request.amount(), request.reason(), request.idempotencyKey(), user(auth)));
    }

    @PostMapping("/players/{userId}/balance/adjustments")
    public Detail adjust(Authentication auth, @PathVariable long userId, @Valid @RequestBody BalanceRequest request) {
        return Detail.from(service.adjust(userId, request.amount(), request.reason(), request.idempotencyKey(), user(auth)));
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
    public record Item(long userId, long accountId, String userCode, String username, String displayName, String avatarKey,
                       String status, String playerKind, String userType, BigDecimal balance, Instant createdAt,
                       Instant lastLoginAt, boolean behaviorEnabled, Instant lastActionAt) {
        static Item from(PlayerDeskRepository.PlayerRow x) { return new Item(x.userId(), x.accountId(), x.userCode(), x.username(), x.displayName(), x.avatarKey(), x.userStatus(), x.playerKind(), x.userType(), x.balance(), x.createdAt(), x.lastLoginAt(), x.behaviorEnabled(), x.lastActionAt()); }
    }
    public record Detail(long userId, long accountId, String userCode, String username, String displayName, String avatarKey,
                         String status, String playerKind, String userType, BigDecimal balance, Instant createdAt,
                         Instant lastLoginAt, boolean behaviorEnabled, Instant lastActionAt, WalletStats walletStatistics,
                         List<Ledger> ledger, List<Action> recentActions, Behavior behavior, List<Bet> bets) {
        static Detail from(PlayerDeskAdminService.Detail x) {
            Item p = Item.from(x.player());
            return new Detail(p.userId(), p.accountId(), p.userCode(), p.username(), p.displayName(), p.avatarKey(), p.status(),
                    p.playerKind(), p.userType(), p.balance(), p.createdAt(), p.lastLoginAt(), p.behaviorEnabled(), p.lastActionAt(),
                    WalletStats.from(x.walletStatistics()), x.ledger().stream().map(Ledger::from).toList(),
                    x.actions().stream().map(Action::from).toList(), Behavior.from(x.behavior()), x.bets().stream().map(Bet::from).toList());
        }
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
    public record CreateNormal(@NotBlank String username, @NotBlank String displayName, @NotBlank String rawPassword) {}
    public record CreateBot(@NotBlank String userCode, @NotBlank String displayName, String avatarKey) {}
    public record StatusRequest(@NotBlank String status) {}
    public record BalanceRequest(@NotNull BigDecimal amount, @NotBlank String reason, @NotBlank String idempotencyKey) {}
    public record BehaviorRequest(@NotBlank String mode, @Min(0) @Max(20) int betsPerIssue, @NotNull @DecimalMin("0.01") BigDecimal stakeMin, @NotNull @DecimalMin("0.01") BigDecimal stakeMax, boolean chatEnabled, @Min(0) @Max(20) int messagesPerIssue) {}
    public record MessageRequest(@NotBlank String content, @NotBlank String clientMessageId) {}
    public record MessageOutcome(ChatMessage message, boolean replayed, String feedback, Object bet) {
        static MessageOutcome from(ChatMessageService.ChatMessageSendOutcome x) { return new MessageOutcome(x.message(), x.deduplicated(), null, null); }
    }
}
