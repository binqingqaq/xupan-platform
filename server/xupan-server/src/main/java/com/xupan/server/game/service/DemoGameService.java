package com.xupan.server.game.service;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.repository.GameIssueEventRepository;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DemoGameService {

    private static final String INITIAL_ISSUE = "3000000";
    private final SettlementService settlementService;
    private final GameDataRepository repository;
    private final VirtualWalletService walletService;
    private final GameIssueEventRepository eventRepository;
    private final 自动轮期服务 automationService;

    public DemoGameService(SettlementService settlementService, GameDataRepository repository,
                           VirtualWalletService walletService,
                           GameIssueEventRepository eventRepository,
                           自动轮期服务 automationService) {
        this.settlementService = settlementService;
        this.repository = repository;
        this.walletService = walletService;
        this.eventRepository = eventRepository;
        this.automationService = automationService;
    }

    public synchronized GameView current(long authenticatedUserId) {
        Instant now = Instant.now();
        automationService.advanceIfEnabled(now);
        GameDataRepository.IssueRecord issue = ensureInitialized();
        return toGameView(issue, now, authenticatedUserId);
    }

    private GameView toGameView(GameDataRepository.IssueRecord issue, Instant now, long authenticatedUserId) {
        List<BallView> ballViews = toBallViews(automationService.previewNumbers(issue, now));
        List<BallView> previousBallViews = repository.findLatestSettledIssue()
                .map(GameDataRepository.IssueRecord::numbers)
                .map(DemoGameService::toBallViews)
                .orElseGet(List::of);
        List<HistoryView> historyViews = repository.findSettledIssues(10).stream()
                .map(DemoGameService::toHistoryView)
                .toList();
        List<OddsView> oddsViews = new ArrayList<>();
        for (PlayType playType : PlayType.values()) {
            repository.findOdds(playType).ifPresent(value -> oddsViews.add(new OddsView(playType, value)));
        }
        List<BetView> betViews = repository.findBetsByIssue(issue.issueNumber()).stream()
                .map(DemoGameService::toBetView)
                .toList();
        VirtualWallet wallet = walletService.getForCurrentUser(authenticatedUserId);
        return new GameView(issue.issueNumber(), issue.status(), issue.phase(), ballViews, previousBallViews, historyViews, oddsViews, betViews,
                new AccountView(wallet.userCode(), wallet.displayName(), wallet.balance(), wallet.status()),
                now, issue.bettingEndsAt(), issue.drawEndsAt(),
                自动轮期服务.DRAWING.equals(issue.phase()),
                eventRepository.findByIssue(issue.issueNumber()).stream()
                        .map(event -> new EventView(event.id(), event.eventType(), event.message(), event.createdAt()))
                        .toList());
    }

    @Transactional
    public synchronized BetView placeBet(long authenticatedUserId, PlaceBetRequest request) {
        GameDataRepository.IssueRecord issue = ensureInitialized();
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("GAME_BET_IDEMPOTENCY_KEY_REQUIRED");
        }
        if (request.ballNumber() != 1) {
            throw BusinessException.badRequest("GAME_BALL_NOT_SUPPORTED", "下注无效：当前只支持第1球");
        }
        VirtualWallet wallet = walletService.getForCurrentUser(authenticatedUserId);
        var replay = repository.findBetByAccountIdAndIdempotencyKey(wallet.accountId(), request.idempotencyKey());
        if (replay.isPresent()) {
            if (!sameBetRequest(replay.get(), issue, request)) {
                throw BusinessException.conflict("WALLET_IDEMPOTENCY_CONFLICT", "重复下注请求参数不一致");
            }
            return toBetView(replay.get());
        }
        if (!自动轮期服务.BETTING.equals(issue.phase())) {
            String message = 自动轮期服务.DRAWING.equals(issue.phase())
                    ? "下注无效：当前正在开奖，已停止下注"
                    : "下注无效：当前期已封盘，不能下注";
            throw BusinessException.conflict("GAME_BETTING_CLOSED", message);
        }
        BigDecimal snapshotOdds = repository.findOdds(request.playType())
                .orElseThrow(() -> new IllegalArgumentException("玩法赔率不存在"));
        settlementService.validateBet(request.playType(), request.parameters(), request.stake(), snapshotOdds);
        String betCode = "BET-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        long betId;
        try {
            betId = repository.saveBetWithOddsSnapshot(wallet.accountId(), betCode, request.idempotencyKey(),
                    issue.issueNumber(), request.ballNumber(), request.playType(), request.parameters(),
                    money(request.stake()), odds(snapshotOdds));
        } catch (DuplicateKeyException duplicate) {
            var concurrentReplay = repository.findBetByAccountIdAndIdempotencyKey(wallet.accountId(), request.idempotencyKey())
                    .orElseThrow(() -> duplicate);
            if (!sameBetRequest(concurrentReplay, issue, request)) {
                throw BusinessException.conflict("WALLET_IDEMPOTENCY_CONFLICT", "重复下注请求参数不一致");
            }
            return toBetView(concurrentReplay);
        }
        walletService.debitForBet(authenticatedUserId, betId, betCode, issue.issueNumber(), money(request.stake()));
        return repository.findBetByCode(betCode)
                .map(DemoGameService::toBetView)
                .orElseThrow(() -> new IllegalStateException("下注保存后未找到注单"));
    }

    @Transactional
    public synchronized GameView draw(long authenticatedUserId, List<Integer> numbers) {
        GameDataRepository.IssueRecord issue = ensureInitialized();
        if (!"OPEN".equals(issue.status())) {
            throw new IllegalStateException("当前期已经开奖，不能重复开奖");
        }
        if (numbers == null || numbers.size() != 8) {
            throw new IllegalArgumentException("必须提供 8 个开奖号码");
        }
        List<BallResult> results = numbers.stream().map(BallResult::fromNumber).toList();
        List<PendingSettlement> settlements = repository.findBetsByIssue(issue.issueNumber()).stream()
                .filter(bet -> bet.settlementStatus() == SettlementStatus.PENDING)
                .map(bet -> new PendingSettlement(bet.id(), bet.accountId(), settlementService.settle(
                        bet.playType(), bet.parameters(), bet.stake(), bet.odds(),
                        results.get(0))))
                .toList();

        repository.saveIssue(issue.issueNumber(), "CLOSED", numbers);
        for (PendingSettlement pending : settlements) {
            if (!repository.settleBetOnce(pending.betId(), pending.settlement())) {
                throw new IllegalStateException("注单已经结算或不存在");
            }
            BigDecimal payout = payout(pending.settlement());
            if (payout.signum() > 0) {
                long settlementUserId = walletService.getByAccountId(pending.accountId()).userId();
                walletService.creditForSettlement(settlementUserId, pending.betId(), issue.issueNumber(), payout,
                        "开奖结算：" + issue.issueNumber());
            }
        }
        return toGameView(repository.findLatestIssue().orElseThrow(), Instant.now(), authenticatedUserId);
    }

    public synchronized OddsView updateOdds(PlayType playType, BigDecimal newOdds) {
        if (playType == null || newOdds == null || newOdds.compareTo(BigDecimal.ONE) < 0 || newOdds.scale() > 3) {
            throw new IllegalArgumentException("赔率必须不小于 1，最多三位小数");
        }
        ensureInitialized();
        repository.saveOdds(playType, odds(newOdds));
        return new OddsView(playType, repository.findOdds(playType).orElseThrow());
    }

    public synchronized GameView resetIssue(long authenticatedUserId) {
        GameDataRepository.IssueRecord issue = ensureInitialized();
        if (自动轮期服务.BETTING.equals(issue.phase())) {
            throw new IllegalStateException("当前期尚未开奖，不能开启下一期");
        }
        long nextSequence;
        try {
            nextSequence = Long.parseLong(issue.issueNumber()) + 1;
        } catch (NumberFormatException ignored) {
            nextSequence = 3_000_000L;
        }
        repository.saveBettingIssue(String.valueOf(nextSequence), Instant.now());
        eventRepository.appendOnce(String.valueOf(nextSequence), "ISSUE_STARTED",
                nextSequence + "期开始", Instant.now());
        return current(authenticatedUserId);
    }

    private GameDataRepository.IssueRecord ensureInitialized() {
        GameDataRepository.IssueRecord issue = repository.findCurrentIssue().orElse(null);
        if (issue == null) {
            issue = repository.findLatestIssue().orElse(null);
        }
        if (issue == null) {
            repository.saveBettingIssue(INITIAL_ISSUE, Instant.now());
            issue = repository.findCurrentIssue().orElseThrow();
        }
        if (issue.startedAt() == null || issue.bettingEndsAt() == null || issue.drawEndsAt() == null) {
            Instant startedAt = issue.startedAt() == null ? Instant.now() : issue.startedAt();
            repository.initializeSchedule(issue.issueNumber(), startedAt);
            issue = repository.findCurrentIssue().orElse(issue);
        }
        for (var entry : defaultOdds().entrySet()) {
            if (repository.findOdds(entry.getKey()).isEmpty()) {
                repository.saveOdds(entry.getKey(), entry.getValue());
            }
        }
        return issue;
    }

    private static EnumMap<PlayType, BigDecimal> defaultOdds() {
        EnumMap<PlayType, BigDecimal> values = new EnumMap<>(PlayType.class);
        values.put(PlayType.FAN, new BigDecimal("3.850"));
        values.put(PlayType.ANGLE, new BigDecimal("1.950"));
        values.put(PlayType.CAR, new BigDecimal("1.316"));
        values.put(PlayType.STRICT, new BigDecimal("2.900"));
        values.put(PlayType.ADD, new BigDecimal("1.950"));
        values.put(PlayType.POSITIVE, new BigDecimal("1.950"));
        values.put(PlayType.TONG, new BigDecimal("1.950"));
        values.put(PlayType.NONE, new BigDecimal("1.950"));
        values.put(PlayType.ODD_EVEN, new BigDecimal("1.950"));
        values.put(PlayType.BIG_SMALL, new BigDecimal("1.950"));
        values.put(PlayType.SPECIAL, new BigDecimal("18.000"));
        return values;
    }

    private static List<BallView> toBallViews(List<Integer> numbers) {
        List<BallView> ballViews = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            Integer number = numbers.size() == 8 ? numbers.get(index) : null;
            if (number == null) {
                ballViews.add(new BallView(index + 1, null, null, null, null));
                continue;
            }
            BallResult result = BallResult.fromNumber(number);
            ballViews.add(new BallView(index + 1, result.number(), result.fan(), result.parity(), result.size()));
        }
        return ballViews;
    }

    private static HistoryView toHistoryView(GameDataRepository.IssueRecord issue) {
        return new HistoryView(issue.issueNumber(), toBallViews(issue.numbers()), issue.settledAt());
    }

    private static BetView toBetView(GameDataRepository.BetRecord bet) {
        return new BetView(bet.betCode(), bet.issueNumber(), bet.ballNumber(), bet.playType(), bet.parameters(),
                bet.stake(), bet.odds(), bet.settlementStatus(), bet.netProfit(), bet.explanation());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal odds(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static boolean sameBetRequest(GameDataRepository.BetRecord existing,
                                          GameDataRepository.IssueRecord issue,
                                          PlaceBetRequest request) {
        return Objects.equals(existing.issueNumber(), issue.issueNumber())
                && existing.ballNumber() == request.ballNumber()
                && existing.playType() == request.playType()
                && Objects.equals(existing.parameters(), normalizedParameters(request.parameters()))
                && existing.stake().compareTo(money(request.stake())) == 0;
    }

    private static List<Integer> normalizedParameters(List<Integer> parameters) {
        return parameters == null ? List.of() : List.copyOf(parameters);
    }

    public record GameView(String issueNumber, String status, String phase, List<BallView> balls,
                           List<BallView> previousBalls, List<HistoryView> history, List<OddsView> odds,
                           List<BetView> bets, AccountView account,
                           Instant serverNow, Instant bettingEndsAt, Instant drawEndsAt,
                           boolean preview, List<EventView> events) {
    }

    public record BallView(int ballNumber, Integer number, Integer fan, String parity, String size) {
    }

    public record HistoryView(String issueNumber, List<BallView> balls, Instant settledAt) {
    }

    public record OddsView(PlayType playType, BigDecimal odds) {
    }

    public record BetView(String id, String issueNumber, int ballNumber, PlayType playType,
                          List<Integer> parameters, BigDecimal stake, BigDecimal odds,
                          SettlementStatus settlementStatus, BigDecimal netProfit, String explanation) {
    }

    public record AccountView(String userCode, String displayName, BigDecimal balance, String status) {
    }

    public record EventView(long id, String eventType, String message, Instant createdAt) {
    }

    private record PendingSettlement(long betId, long accountId, SettlementResult settlement) {
    }

    private static BigDecimal payout(SettlementResult settlement) {
        return switch (settlement.status()) {
            case WIN -> settlement.stake().add(settlement.netProfit());
            case DRAW -> settlement.stake();
            case LOSE, PENDING -> BigDecimal.ZERO;
        };
    }
}
