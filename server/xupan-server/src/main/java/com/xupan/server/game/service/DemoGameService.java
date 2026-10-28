package com.xupan.server.game.service;

import com.xupan.server.center.service.CenterSettlementService;
import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.repository.GameIssueEventRepository;
import com.xupan.server.game.repository.PlayerBetSummaryRepository;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class DemoGameService {

    private static final String INITIAL_ISSUE = "3000000";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final SettlementService settlementService;
    private final GameDataRepository repository;
    private final PlayerBetSummaryRepository playerBetSummaryRepository;
    private final VirtualWalletService walletService;
    private final GameIssueEventRepository eventRepository;
    private final 自动轮期服务 automationService;
    private final ApplicationEventPublisher eventPublisher;
    private final MachineBetLimitService machineBetLimitService;
    private final AgentBettingPolicy agentBettingPolicy;
    private final CenterSettlementService centerSettlementService;

    public DemoGameService(SettlementService settlementService, GameDataRepository repository,
                           PlayerBetSummaryRepository playerBetSummaryRepository,
                           VirtualWalletService walletService,
                           GameIssueEventRepository eventRepository,
                           自动轮期服务 automationService,
                           ApplicationEventPublisher eventPublisher,
                           MachineBetLimitService machineBetLimitService,
                           AgentBettingPolicy agentBettingPolicy,
                           CenterSettlementService centerSettlementService) {
        this.settlementService = settlementService;
        this.repository = repository;
        this.playerBetSummaryRepository = playerBetSummaryRepository;
        this.walletService = walletService;
        this.eventRepository = eventRepository;
        this.automationService = automationService;
        this.eventPublisher = eventPublisher;
        this.machineBetLimitService = machineBetLimitService;
        this.agentBettingPolicy = agentBettingPolicy;
        this.centerSettlementService = centerSettlementService;
    }

    public synchronized GameView current(long authenticatedUserId) {
        return current(authenticatedUserId, GameDataRepository.DEFAULT_GAME_CODE);
    }

    public synchronized GameView current(long authenticatedUserId, String gameCode) {
        return current(authenticatedUserId, gameCode, true);
    }

    public synchronized GameView current(long authenticatedUserId, String gameCode, boolean includeAllIssueBets) {
        String normalizedGameCode = normalizedGameCode(gameCode);
        Instant now = Instant.now();
        automationService.advanceIfEnabled(normalizedGameCode, now);
        GameDataRepository.IssueRecord issue = ensureInitialized(normalizedGameCode);
        return toGameView(normalizedGameCode, issue, now, authenticatedUserId, includeAllIssueBets);
    }

    public BetSummaryView betSummary(long authenticatedUserId) {
        VirtualWallet wallet = walletService.getForCurrentUser(authenticatedUserId);
        LocalDate businessDay = businessDay(Instant.now());
        Instant from = businessDay.atTime(6, 0).atZone(BUSINESS_ZONE).toInstant();
        Instant to = businessDay.plusDays(1).atTime(6, 0).atZone(BUSINESS_ZONE).toInstant();
        GameDataRepository.BetDayStatistics dayStatistics = playerBetSummaryRepository
                .findBetDayStatistics(wallet.accountId(), from, to);
        List<BetView> pending = repository.findBetsByAccountId(wallet.accountId(), SettlementStatus.PENDING, 100)
                .stream().map(DemoGameService::toBetView).toList();
        List<BetView> settled = playerBetSummaryRepository.findSettledBets(
                        wallet.accountId(), from, to, 100)
                .stream()
                .map(DemoGameService::toBetView)
                .toList();
        return new BetSummaryView(dayStatistics.turnover(), dayStatistics.netProfit(), pending, settled);
    }

    private GameView toGameView(String gameCode, GameDataRepository.IssueRecord issue, Instant now,
                               long authenticatedUserId, boolean includeAllIssueBets) {
        List<BallView> ballViews = toBallViews(automationService.previewNumbers(issue, now));
        List<BallView> previousBallViews = repository.findLatestSettledIssue(gameCode)
                .map(GameDataRepository.IssueRecord::numbers)
                .map(DemoGameService::toBallViews)
                .orElseGet(List::of);
        List<HistoryView> historyViews = repository.findSettledIssues(gameCode, 10).stream()
                .map(DemoGameService::toHistoryView)
                .toList();
        MachineBettingConfig machineConfig = repository.findMachineBettingConfig(
                walletService.getForCurrentUser(authenticatedUserId).accountId());
        List<OddsView> oddsViews = new ArrayList<>();
        for (PlayType playType : PlayType.values()) {
            try {
                oddsViews.add(new OddsView(playType, agentBettingPolicy.effectiveOdds(playType, null, machineConfig)));
            } catch (IllegalStateException unavailable) {
                throw com.xupan.server.web.BusinessException.conflict(
                        "MACHINE_ODDS_UNAVAILABLE", "所属机器赔率尚未配置");
            }
        }
        List<DisplayOddsView> displayOddsViews = java.util.Arrays.stream(AgentBettingPolicy.DisplayOddsRuleKey.values())
                .map(ruleKey -> new DisplayOddsView(ruleKey.name(),
                        agentBettingPolicy.displayOdds(ruleKey, machineConfig).toPlainString()))
                .toList();
        long currentAccountId = walletService.getForCurrentUser(authenticatedUserId).accountId();
        List<BetView> betViews = repository.findBetsByIssue(issue.issueNumber()).stream()
                .filter(bet -> bet.settlementStatus() != SettlementStatus.CANCELED)
                .filter(bet -> includeAllIssueBets || bet.accountId() == currentAccountId)
                .map(DemoGameService::toBetView)
                .toList();
        VirtualWallet wallet = walletService.getForCurrentUser(authenticatedUserId);
        return new GameView(gameCode, issue.issueNumber(), issue.status(), issue.phase(), ballViews, previousBallViews,
                historyViews, oddsViews, displayOddsViews, betViews,
                new AccountView(wallet.userCode(), wallet.displayName(), wallet.balance(), wallet.status()),
                now, issue.bettingEndsAt(), issue.drawEndsAt(),
                自动轮期服务.DRAWING.equals(issue.phase()),
                machineConfig.oddsRate(), machineConfig.specialOddsRate(), machineConfig.version(),
                repository.isSpecialBettingEnabled(gameCode),
                eventRepository.findByIssue(gameCode, issue.issueNumber()).stream()
                        .map(event -> new EventView(event.id(), event.eventType(), event.message(), event.createdAt()))
                        .toList());
    }

    static LocalDate businessDay(Instant now) {
        var localNow = now.atZone(BUSINESS_ZONE);
        LocalDate date = localNow.toLocalDate();
        return localNow.toLocalTime().isBefore(java.time.LocalTime.of(6, 0)) ? date.minusDays(1) : date;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BetLimitExceededException.class)
    public BetView placeBet(long authenticatedUserId, PlaceBetRequest request) {
        return placeBetBatchForUser(authenticatedUserId, List.of(request), "SINGLE-" + request.idempotencyKey())
                .get(0);
    }

    /** Uses the same bet, wallet debit, idempotency and settlement path for an admin-selected test player. */
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BetLimitExceededException.class)
    public BetView placeBetForUser(long userId, PlaceBetRequest request) {
        return placeBetBatchForUser(userId, List.of(request), "SINGLE-" + request.idempotencyKey()).get(0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BetLimitExceededException.class)
    public List<BetView> placeBetBatchForUser(long userId, List<PlaceBetRequest> requests,
                                               String batchIdempotencyKey) {
        if (requests == null) throw new IllegalArgumentException("下注批次不能为空");
        List<BetInstruction> instructions = requests.stream().map(request -> new BetInstruction(request,
                com.xupan.server.game.domain.BetQuotaBucket.ruleKey(request.playType(), request.parameters()))).toList();
        return placeBetBatchInstructionsForUser(userId, instructions, batchIdempotencyKey);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BetLimitExceededException.class)
    public List<BetView> placeBetBatchInstructionsForUser(long userId, List<BetInstruction> batch,
                                                            String batchIdempotencyKey) {
        if (batch == null || batch.isEmpty()) throw new IllegalArgumentException("下注批次不能为空");
        if (batchIdempotencyKey == null || batchIdempotencyKey.isBlank() || batchIdempotencyKey.length() > 128) {
            throw new IllegalArgumentException("下注批次幂等键无效");
        }
        List<PlaceBetRequest> requests = batch.stream().map(BetInstruction::request).toList();
        String gameCode = requests.get(0).normalizedGameCode();
        if (requests.stream().anyMatch(request -> !gameCode.equals(request.normalizedGameCode()))) {
            throw new IllegalArgumentException("同一下注批次只能包含一个彩种");
        }
        String digest = digest(batch);
        if (!repository.isGameEnabled(gameCode)) {
            throw BusinessException.conflict("GAME_DISABLED", "当前彩种已关闭，不能下注");
        }
        GameDataRepository.IssueRecord issue = repository.findCurrentIssueForUpdate(gameCode)
                .orElseGet(() -> ensureInitialized(gameCode));
        VirtualWallet wallet = walletService.lockForBet(userId);
        var priorBatch = repository.findBetBatch(wallet.accountId(), batchIdempotencyKey);
        if (priorBatch.isPresent()) {
            if (!priorBatch.get().requestDigest().equals(digest)) {
                throw BusinessException.conflict("WALLET_IDEMPOTENCY_CONFLICT", "重複下注批次参数不一致");
            }
            return repository.findBetsByBatchId(priorBatch.get().id()).stream().map(DemoGameService::toBetView).toList();
        }
        if (!自动轮期服务.BETTING.equals(issue.phase())) {
            String message = 自动轮期服务.DRAWING.equals(issue.phase())
                    ? "下注无效：当前正在开奖，已停止下注"
                    : "下注无效：当前期已封盘，不能下注";
            throw BusinessException.conflict("GAME_BETTING_CLOSED", message);
        }
        MachineBettingConfig machineConfig = repository.findMachineBettingConfig(wallet.accountId());
        String rejection = agentBettingPolicy.rejectBet(machineConfig, issue, Instant.now()).orElse(null);
        if (rejection != null) {
            throw BusinessException.conflict(rejection, AgentBettingPolicy.rejectionMessage(rejection));
        }
        if (requests.stream().anyMatch(request -> request.playType() == PlayType.SPECIAL)
                && !repository.isSpecialBettingEnabled(gameCode)) {
            throw BusinessException.conflict("GAME_SPECIAL_DISABLED", "当前彩种不支持特码投注");
        }
        List<BigDecimal> snapshots = new ArrayList<>(requests.size());
        List<BigDecimal> unitStakes = new ArrayList<>(requests.size());
        List<MachineBetLimitService.Instruction> quotaInstructions = new ArrayList<>(requests.size());
        BigDecimal totalStake = BigDecimal.ZERO.setScale(2);
        for (PlaceBetRequest request : requests) {
            BigDecimal snapshotOdds;
            try {
                snapshotOdds = agentBettingPolicy.effectiveOdds(request.playType(), null, machineConfig,
                        request.parameters());
            } catch (IllegalStateException unavailable) {
                throw BusinessException.conflict("MACHINE_ODDS_UNAVAILABLE", "所属机器赔率尚未配置");
            }
            settlementService.validateBet(request.playType(), request.parameters(), request.stake(), snapshotOdds);
            BigDecimal unitStake = request.playType() == PlayType.SPECIAL
                    ? money(request.stake().divide(BigDecimal.valueOf(request.parameters().size()), 2, RoundingMode.UNNECESSARY))
                    : money(request.stake());
            snapshots.add(snapshotOdds);
            unitStakes.add(unitStake);
            totalStake = totalStake.add(money(request.stake()));
            quotaInstructions.add(new MachineBetLimitService.Instruction(request.playType(), request.parameters(),
                    money(request.stake()), unitStake, batch.get(quotaInstructions.size()).quotaSelectionKey()));
        }
        machineBetLimitService.validateBatch(wallet.accountId(), gameCode, issue.issueNumber(), machineConfig, quotaInstructions);
        long batchId = repository.createBetBatch(wallet.accountId(), machineConfig.machineId(), gameCode,
                issue.issueNumber(), batchIdempotencyKey, digest, totalStake, machineConfig.version());
        GameDataRepository.BettingRates rates = repository.findBettingRatesByAccountId(wallet.accountId());
        List<BetView> placed = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            PlaceBetRequest request = requests.get(index);
            String betCode = "BET-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String itemKey = "BATCH-" + batchId + "-" + (index + 1);
            long betId = repository.saveBetWithOddsSnapshot(gameCode, wallet.accountId(), betCode, itemKey,
                    issue.issueNumber(), request.ballNumber(), request.playType(), request.parameters(),
                    money(request.stake()), odds(request.playType(), snapshots.get(index)),
                    rates.rebateRate(), rates.specialRebateRate(),
                    machineConfig.machineId(), machineConfig.version(), machineConfig.oddsRate(),
                    machineConfig.specialOddsRate(),
                    com.xupan.server.game.domain.BetQuotaBucket.forBet(request.playType(), request.parameters()).name(),
                    batch.get(index).quotaSelectionKey(),
                    unitStakes.get(index), batchId, index + 1);
            walletService.debitForBet(userId, betId, betCode, issue.issueNumber(), money(request.stake()));
            placed.add(repository.findBetByCode(betCode).map(DemoGameService::toBetView)
                    .orElseThrow(() -> new IllegalStateException("下注保存后未找到注单")));
        }
        return List.copyOf(placed);
    }

    private static String digest(List<BetInstruction> instructions) {
        String canonical = instructions.stream().map(instruction -> instruction.request().normalizedGameCode() + "|" + instruction.request().ballNumber()
                + "|" + instruction.request().playType().name() + "|" + (instruction.request().parameters() == null ? "" : instruction.request().parameters().toString())
                + "|" + instruction.request().stake().stripTrailingZeros().toPlainString()
                + "|" + instruction.quotaSelectionKey()).collect(java.util.stream.Collectors.joining("\n"));
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Transactional
    public synchronized CancellationResult cancelCurrentBets(long authenticatedUserId, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("撤单时间不能为空");
        }
        automationService.advanceIfEnabled(GameDataRepository.DEFAULT_GAME_CODE, now);
        GameDataRepository.IssueRecord issue = ensureInitialized(GameDataRepository.DEFAULT_GAME_CODE);
        if (!自动轮期服务.BETTING.equals(issue.phase())
                || issue.bettingEndsAt() == null || !now.isBefore(issue.bettingEndsAt())) {
            return CancellationResult.stopped();
        }

        VirtualWallet wallet = walletService.getForCurrentUser(authenticatedUserId);
        MachineBettingConfig machineConfig = repository.findMachineBettingConfig(wallet.accountId());
        if (agentBettingPolicy.isClosedForBetting(machineConfig, issue, now)) {
            return CancellationResult.stopped();
        }
        if (machineConfig.cancellationDisabled()) {
            return CancellationResult.disabled();
        }
        List<GameDataRepository.BetRecord> candidates = repository.findCancelableBets(
                wallet.accountId(), issue.issueNumber(),
                agentBettingPolicy.cancellationWindowStart(machineConfig, now));
        List<CancellationItem> canceled = new ArrayList<>();
        for (GameDataRepository.BetRecord bet : candidates) {
            // 物理删除作为原子占用：删除成功才退款，重复请求不再命中同一注单。
            if (repository.deletePendingBetById(bet.id())) {
                var refund = walletService.refundForCancellation(authenticatedUserId, bet.id(), bet.accountId(),
                        bet.issueNumber(), bet.stake());
                canceled.add(new CancellationItem(bet, refund.wallet().balance()));
            }
        }
        if (canceled.isEmpty()) {
            return CancellationResult.noEligibleBets(machineConfig.cancelSeconds());
        }
        return CancellationResult.canceled(canceled,
                walletService.getForCurrentUser(authenticatedUserId).balance(), machineConfig.cancelSeconds());
    }

    @Transactional
    public synchronized GameView draw(long authenticatedUserId, List<Integer> numbers) {
        ensureInitialized();
        GameDataRepository.IssueRecord issue = repository.findCurrentIssueForUpdate(
                GameDataRepository.DEFAULT_GAME_CODE).orElseGet(() -> repository.findLatestIssue(
                        GameDataRepository.DEFAULT_GAME_CODE).orElseThrow());
        if (!自动轮期服务.DRAWING.equals(issue.phase())) {
            throw BusinessException.conflict("DRAW_RESULT_NOT_READY", "当前期还未进入开奖阶段");
        }
        if (numbers == null || numbers.size() != 8) {
            throw new IllegalArgumentException("必须提供 8 个开奖号码");
        }
        List<BallResult> results = numbers.stream().map(BallResult::fromNumber).toList();
        List<PendingSettlement> settlements = repository.findBetsByIssue(issue.issueNumber()).stream()
                .filter(bet -> bet.settlementStatus() == SettlementStatus.PENDING)
                .map(bet -> new PendingSettlement(bet.id(), bet.accountId(), settlementService.settle(
                        bet.playType(), bet.parameters(), bet.stake(), bet.odds(),
                        bet.playType() == PlayType.SPECIAL ? repository.findSpecialUnitStake(bet.id()) : bet.stake(),
                        bet.machineOddsRate(),
                        results.get(bet.ballNumber() - 1))))
                .toList();

        Instant confirmedAt = Instant.now();
        if (!repository.saveConfirmedResult(GameDataRepository.DEFAULT_GAME_CODE, issue.issueNumber(),
                numbers, confirmedAt, authenticatedUserId)) {
            throw BusinessException.conflict("DRAW_RESULT_CONFIRM_CONFLICT", "开奖结果或草稿已被其他操作修改");
        }
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
            eventPublisher.publishEvent(new BetSettlementCompletedEvent(
                    pending.betId(), pending.accountId(), issue.issueNumber(), pending.settlement().status(),
                    pending.settlement().stake(), pending.settlement().netProfit(), payout));
        }
        CenterSettlementService.SettlementSummary centerSummary = centerSettlementService.settleIssue(
                GameDataRepository.DEFAULT_GAME_CODE, issue.issueNumber(), results,
                numbers.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                Integer.MAX_VALUE);
        if (centerSummary.skippedCount() > 0) {
            throw BusinessException.conflict("CENTER_ORDER_SETTLEMENT_INCOMPLETE",
                    "存在无法按开奖结果结算的中心订单，双层结算已回滚");
        }
        eventRepository.appendOnce(GameDataRepository.DEFAULT_GAME_CODE, issue.issueNumber(), "DRAW_RESULT",
                issue.issueNumber() + "期开奖号码："
                        + numbers.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                confirmedAt);
        return toGameView(GameDataRepository.DEFAULT_GAME_CODE,
                repository.findLatestIssue(GameDataRepository.DEFAULT_GAME_CODE).orElseThrow(),
                Instant.now(), authenticatedUserId, true);
    }

    public synchronized OddsView updateOdds(PlayType playType, BigDecimal newOdds) {
        if (playType == null || newOdds == null || newOdds.compareTo(BigDecimal.ONE) < 0 || newOdds.scale() > 3) {
            throw new IllegalArgumentException("赔率必须不小于 1，最多三位小数");
        }
        ensureInitialized();
        repository.saveOdds(playType, odds(newOdds));
        return new OddsView(playType, repository.findOdds(playType).orElseThrow());
    }

    @Transactional
    public synchronized GameView resetIssue(long authenticatedUserId) {
        GameDataRepository.IssueRecord issue = ensureInitialized();
        if (!"SETTLED".equals(issue.phase())
                || issue.numbers().stream().anyMatch(number -> number < 1 || number > 20)) {
            throw BusinessException.conflict("DRAW_RESULT_NOT_CONFIRMED", "本期结果尚未确认，不能开启下一期");
        }
        long nextSequence;
        try {
            nextSequence = Long.parseLong(issue.issueNumber()) + 1;
        } catch (NumberFormatException ignored) {
            nextSequence = 3_000_000L;
        }
        repository.saveBettingIssue(String.valueOf(nextSequence), Instant.now());
        eventRepository.appendOnce(GameDataRepository.DEFAULT_GAME_CODE, String.valueOf(nextSequence), "ISSUE_STARTED",
                nextSequence + "期开始", Instant.now());
        return current(authenticatedUserId);
    }

    private GameDataRepository.IssueRecord ensureInitialized() {
        return ensureInitialized(GameDataRepository.DEFAULT_GAME_CODE);
    }

    private GameDataRepository.IssueRecord ensureInitialized(String gameCode) {
        String normalizedGameCode = normalizedGameCode(gameCode);
        if (!repository.gameExists(normalizedGameCode)) {
            throw BusinessException.notFound("GAME_NOT_FOUND", "彩种不存在");
        }
        GameDataRepository.IssueRecord issue = repository.findCurrentIssue(normalizedGameCode).orElse(null);
        if (issue == null) {
            issue = repository.findLatestIssue(normalizedGameCode).orElse(null);
        }
        if (issue == null) {
            repository.saveBettingIssue(normalizedGameCode, initialIssue(normalizedGameCode), Instant.now());
            issue = repository.findCurrentIssue(normalizedGameCode).orElseThrow();
        }
        if (issue.startedAt() == null || issue.bettingEndsAt() == null || issue.drawEndsAt() == null) {
            Instant startedAt = issue.startedAt() == null ? Instant.now() : issue.startedAt();
            repository.initializeSchedule(issue.issueNumber(), startedAt);
            issue = repository.findCurrentIssue(normalizedGameCode).orElse(issue);
        }
        for (var entry : defaultOdds().entrySet()) {
            if (repository.findOdds(entry.getKey()).isEmpty()) {
                repository.saveOdds(entry.getKey(), entry.getValue());
            }
        }
        return issue;
    }

    private static String normalizedGameCode(String gameCode) {
        return gameCode == null || gameCode.isBlank()
                ? GameDataRepository.DEFAULT_GAME_CODE
                : gameCode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String initialIssue(String gameCode) {
        return GameDataRepository.DEFAULT_GAME_CODE.equals(gameCode) ? INITIAL_ISSUE : gameCode + "-" + INITIAL_ISSUE;
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

    private static BigDecimal odds(PlayType playType, BigDecimal value) {
        return playType == PlayType.SPECIAL
                ? value.setScale(4, RoundingMode.UNNECESSARY)
                : odds(value);
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

    public record GameView(String gameCode, String issueNumber, String status, String phase, List<BallView> balls,
                           List<BallView> previousBalls, List<HistoryView> history, List<OddsView> odds,
                           List<DisplayOddsView> displayOdds, List<BetView> bets, AccountView account,
                           Instant serverNow, Instant bettingEndsAt, Instant drawEndsAt,
                           boolean preview, BigDecimal oddsRate, BigDecimal specialOddsRate, int configVersion,
                           boolean specialEnabled, List<EventView> events) {
    }

    public record BallView(int ballNumber, Integer number, Integer fan, String parity, String size) {
    }

    public record HistoryView(String issueNumber, List<BallView> balls, Instant settledAt) {
    }

    public record OddsView(PlayType playType, BigDecimal odds) {
    }

    public record DisplayOddsView(String ruleKey, String displayValue) {
    }

    public record BetInstruction(PlaceBetRequest request, String quotaSelectionKey) {
        public BetInstruction {
            Objects.requireNonNull(request, "request");
            if (quotaSelectionKey == null || quotaSelectionKey.isBlank()) {
                throw new IllegalArgumentException("额度规范键不能为空");
            }
        }
    }

    public record BetView(String id, String issueNumber, int ballNumber, PlayType playType,
                          List<Integer> parameters, BigDecimal stake, BigDecimal odds,
                          SettlementStatus settlementStatus, BigDecimal netProfit, String explanation) {
    }

    public record BetSummaryView(BigDecimal todayTurnover, BigDecimal todayNetProfit,
                                 List<BetView> pending, List<BetView> settled) {
    }

    public record CancellationResult(Status status, List<CancellationItem> items,
                                     BigDecimal balance, int windowSeconds) {
        public enum Status {
            /** 已过下注阶段或已进入机器封盘窗口（BY220：本期已停止，禁止取消）。 */
            STOPPED,
            /** 机器取消秒数为 0，关闭取消能力（BY220：禁止取消）。 */
            CANCELLATION_DISABLED,
            NO_ELIGIBLE_BETS,
            CANCELED
        }

        public CancellationResult {
            items = List.copyOf(items == null ? List.of() : items);
        }

        static CancellationResult stopped() {
            return new CancellationResult(Status.STOPPED, List.of(), null, 0);
        }

        static CancellationResult disabled() {
            return new CancellationResult(Status.CANCELLATION_DISABLED, List.of(), null, 0);
        }

        static CancellationResult noEligibleBets(int windowSeconds) {
            return new CancellationResult(Status.NO_ELIGIBLE_BETS, List.of(), null, windowSeconds);
        }

        static CancellationResult canceled(List<CancellationItem> items, BigDecimal balance, int windowSeconds) {
            return new CancellationResult(Status.CANCELED, items, balance, windowSeconds);
        }
    }

    public record CancellationItem(GameDataRepository.BetRecord bet, BigDecimal balanceAfter) {
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
            case LOSE, PENDING, CANCELED -> BigDecimal.ZERO;
        };
    }
}
