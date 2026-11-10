package com.xupan.server.game.service;

import com.xupan.server.config.ScheduledTaskLock;
import com.xupan.server.center.service.CenterSettlementService;
import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.DrawRule;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.repository.GameIssueEventRepository;
import com.xupan.server.platformadmin.service.GameSettingsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class 自动轮期服务 {

    public static final String BETTING = "BETTING";
    public static final String DRAWING = "DRAWING";
    public static final String SETTLED = "SETTLED";
    private static final String INITIAL_ISSUE = "3000000";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GameDataRepository gameRepository;
    private final GameIssueEventRepository eventRepository;
    private final SettlementService settlementService;
    private final VirtualWalletService walletService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final boolean automationEnabled;
    private final GameSettingsService gameSettingsService;
    private final ScheduledTaskLock taskLock;
    private CenterSettlementService centerSettlementService;

    @Autowired
    public 自动轮期服务(GameDataRepository gameRepository,
                        GameIssueEventRepository eventRepository,
                        SettlementService settlementService,
                        VirtualWalletService walletService,
                        CenterSettlementService centerSettlementService,
                        ApplicationEventPublisher eventPublisher,
                        GameSettingsService gameSettingsService,
                        ScheduledTaskLock taskLock,
                        @Value("${xupan.automation.enabled:true}") boolean automationEnabled) {
        this(gameRepository, eventRepository, settlementService, walletService,
                eventPublisher, gameSettingsService, taskLock, Clock.systemUTC(), automationEnabled);
        this.centerSettlementService = centerSettlementService;
    }

    自动轮期服务(GameDataRepository gameRepository,
                 GameIssueEventRepository eventRepository,
                 SettlementService settlementService,
                 VirtualWalletService walletService,
                 ApplicationEventPublisher eventPublisher,
                 GameSettingsService gameSettingsService,
                 Clock clock) {
        this(gameRepository, eventRepository, settlementService, walletService, eventPublisher,
                gameSettingsService, null, clock, true);
    }

    自动轮期服务(GameDataRepository gameRepository,
                 GameIssueEventRepository eventRepository,
                 SettlementService settlementService,
                 VirtualWalletService walletService,
                 ApplicationEventPublisher eventPublisher,
                 GameSettingsService gameSettingsService,
                 Clock clock,
                 boolean automationEnabled) {
        this(gameRepository, eventRepository, settlementService, walletService, eventPublisher,
                gameSettingsService, null, clock, automationEnabled);
    }

    /**
     * @param taskLock 定时任务互斥锁；为 {@code null} 时表示"直接执行"。
     *                 测试与直接调用 {@link #advanceAllActive(Instant)} 的路径不需要锁。
     */
    自动轮期服务(GameDataRepository gameRepository,
                 GameIssueEventRepository eventRepository,
                 SettlementService settlementService,
                 VirtualWalletService walletService,
                 ApplicationEventPublisher eventPublisher,
                 GameSettingsService gameSettingsService,
                 ScheduledTaskLock taskLock,
                 Clock clock,
                 boolean automationEnabled) {
        this.gameRepository = gameRepository;
        this.eventRepository = eventRepository;
        this.settlementService = settlementService;
        this.walletService = walletService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.automationEnabled = automationEnabled;
        this.gameSettingsService = gameSettingsService;
        this.taskLock = taskLock;
    }

    /**
     * 1 秒一次的自动轮期。
     *
     * <p><b>并发</b>：必须经 {@link ScheduledTaskLock} 互斥。多进程并存（滚动重启的新旧进程重叠、
     * 或将来部署第二实例）时双跑会**重复开奖**，属错账级事故。这与"要不要扩容"无关。</p>
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void scheduledAdvance() {
        if (!automationEnabled) return;
        if (taskLock == null) {
            advanceAllActive(clock.instant());
            return;
        }
        taskLock.runExclusively("xupan:auto-advance", () -> advanceAllActive(clock.instant()));
    }

    @Transactional
    public synchronized void advanceAllActive(Instant now) {
        for (GameSettingsService.GameCatalogItem game : gameSettingsService.activeCatalog()) {
            advance(game.gameCode(), now);
        }
    }

    @Transactional
    public void advanceIfEnabled(Instant now) {
        advanceIfEnabled(GameDataRepository.DEFAULT_GAME_CODE, now);
    }

    @Transactional
    public void advanceIfEnabled(String gameCode, Instant now) {
        if (automationEnabled) {
            advance(gameCode, now);
        }
    }

    @Transactional
    public synchronized void advance(Instant now) {
        advance(GameDataRepository.DEFAULT_GAME_CODE, now);
    }

    @Transactional
    public synchronized void advance(String gameCode, Instant now) {
        // Use the same game-definition -> issue lock order as an explicit ball-count cutover.
        gameRepository.nextIssueBallCountForUpdate(gameCode);
        GameDataRepository.IssueRecord issue = ensureCurrentIssue(gameCode, now);
        if (issue.bettingEndsAt() == null || issue.drawEndsAt() == null) {
            gameRepository.initializeSchedule(gameCode, issue.issueNumber(),
                    issue.startedAt() == null ? now : issue.startedAt());
            issue = gameRepository.findCurrentIssue(gameCode).orElseThrow();
        }

        if (BETTING.equals(issue.phase())) {
            if (!GameDataRepository.DEFAULT_GAME_CODE.equals(gameCode)
                    && !now.isBefore(issue.bettingEndsAt().minusSeconds(30))) {
                eventRepository.appendOnce(gameCode, issue.issueNumber(), "BETTING_WARNING",
                        "离封盘还剩30秒！\n20秒以内下注容易失败退单!", now);
            }
            if (!now.isBefore(issue.bettingEndsAt())
                    && gameRepository.transitionPhase(gameCode, issue.issueNumber(), BETTING, DRAWING)) {
                eventRepository.appendOnce(gameCode, issue.issueNumber(), "BETTING_CLOSED",
                        issue.issueNumber() + "期停止", now);
                issue = gameRepository.findCurrentIssue(gameCode).orElseThrow();
            }
        }

        if (DRAWING.equals(issue.phase()) && !now.isBefore(issue.drawEndsAt())) {
            finalizeIssue(gameCode, issue, now);
        }
    }

    private GameDataRepository.IssueRecord ensureCurrentIssue(String gameCode, Instant now) {
        return gameRepository.findCurrentIssue(gameCode).orElseGet(() -> {
            GameDataRepository.IssueRecord latest = gameRepository.findLatestIssue(gameCode).orElse(null);
            if (latest == null) {
                gameRepository.saveBettingIssue(gameCode, initialIssue(gameCode), now);
            } else {
                gameRepository.saveBettingIssue(gameCode, nextIssueNumber(gameCode, latest.issueNumber()),
                        latest.settledAt() == null ? now : latest.settledAt());
            }
            GameDataRepository.IssueRecord created = gameRepository.findCurrentIssue(gameCode).orElseThrow();
            eventRepository.appendOnce(gameCode, created.issueNumber(), "ISSUE_STARTED",
                    created.issueNumber() + "期开始", created.startedAt());
            return created;
        });
    }

    private void finalizeIssue(String gameCode, GameDataRepository.IssueRecord issue, Instant now) {
        List<Integer> existingNumbers = issue.numbers();
        if (existingNumbers == null || existingNumbers.size() != issue.ballCount()) {
            throw new IllegalStateException("期次预设号码数量与球数不一致");
        }
        int maxNumber = DrawRule.maxNumber(gameCode, issue.drawRuleVersion());
        List<Integer> numbers = new ArrayList<>(existingNumbers);
        Set<Integer> used = new HashSet<>();
        for (Integer preset : existingNumbers) {
            if (preset == null) continue;
            if (preset < 1 || preset > maxNumber) {
                throw new IllegalStateException("期次预设号码超出适用规则的号码范围");
            }
            if (DrawRule.requiresDistinctNumbers(gameCode, issue.drawRuleVersion()) && !used.add(preset)) {
                throw new IllegalStateException("AU8 同一期七个开奖号码不能重复");
            }
            used.add(preset);
        }
        if (DrawRule.requiresDistinctNumbers(gameCode, issue.drawRuleVersion())) {
            List<Integer> available = new ArrayList<>();
            for (int number = 1; number <= maxNumber; number++) {
                if (!used.contains(number)) available.add(number);
            }
            for (int index = 0; index < numbers.size(); index++) {
                if (numbers.get(index) == null) {
                    numbers.set(index, available.remove(RANDOM.nextInt(available.size())));
                }
            }
        } else {
            for (int index = 0; index < numbers.size(); index++) {
                if (numbers.get(index) == null) numbers.set(index, RANDOM.nextInt(1, maxNumber + 1));
            }
        }
        DrawRule.validateNumbers(gameCode, issue.drawRuleVersion(), issue.ballCount(), numbers);
        if (!gameRepository.saveFinalResult(gameCode, issue.issueNumber(), numbers, now)) {
            return;
        }

        List<BallResult> results = numbers.stream()
                .map(number -> BallResult.fromNumber(number, gameCode, issue.drawRuleVersion())).toList();
        gameRepository.findBetsByIssue(gameCode, issue.issueNumber()).stream()
                .filter(bet -> bet.settlementStatus() == SettlementStatus.PENDING)
                .forEach(bet -> {
                    SettlementResult settlement = settlementService.settle(
                            bet.playType(), bet.parameters(), bet.stake(), bet.odds(),
                            bet.playType() == PlayType.SPECIAL
                                    ? gameRepository.findSpecialUnitStake(bet.id()) : bet.stake(),
                            bet.machineOddsRate(),
                            results.get(bet.ballNumber() - 1));
                    if (gameRepository.settleBetOnce(bet.id(), settlement)) {
                        BigDecimal payout = payout(settlement);
                        BigDecimal balanceAfter;
                        if (payout.signum() > 0) {
                            long settlementUserId = walletService.getByAccountId(bet.accountId()).userId();
                            balanceAfter = walletService.creditForSettlement(settlementUserId, bet.id(),
                                    issue.issueNumber(), payout, "开奖结算：" + issue.issueNumber())
                                    .wallet().balance();
                        } else {
                            balanceAfter = walletService.getByAccountId(bet.accountId()).balance();
                        }
                        if (eventPublisher != null) {
                            eventPublisher.publishEvent(new BetSettlementCompletedEvent(
                                    bet.id(), bet.accountId(), issue.issueNumber(), settlement.status(),
                                    settlement.stake(), settlement.netProfit(), payout, balanceAfter));
                        }
                    }
                });

        if (centerSettlementService != null) {
            CenterSettlementService.SettlementSummary centerSummary = centerSettlementService.settleIssue(
                    gameCode, issue.issueNumber(), results,
                    numbers.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                    Integer.MAX_VALUE);
            if (centerSummary.skippedCount() > 0) {
                throw new IllegalStateException("存在无法按开奖结果结算的中心订单，自动开奖事务已回滚");
            }
        }

        eventRepository.appendOnce(gameCode, issue.issueNumber(), "DRAW_RESULT",
                resultMessage(issue.issueNumber(), numbers, gameCode, issue.drawRuleVersion()), now);

        String nextIssue = nextIssueNumber(gameCode, issue.issueNumber());
        if (gameRepository.findCurrentIssue(gameCode).isEmpty()) {
            gameRepository.saveBettingIssue(gameCode, nextIssue, issue.drawEndsAt());
            eventRepository.appendOnce(gameCode, nextIssue, "ISSUE_STARTED", nextIssue + "期开始", issue.drawEndsAt());
        }
    }

    public List<Integer> previewNumbers(GameDataRepository.IssueRecord issue, Instant now) {
        if (!DRAWING.equals(issue.phase()) || issue.startedAt() == null) {
            return issue.numbers();
        }
        long tick = Math.max(0, Duration.between(issue.startedAt(), now).toSeconds());
        java.util.SplittableRandom previewRandom = new java.util.SplittableRandom(
                issue.issueNumber().hashCode() * 31L + tick);
        return generateNumbers(issue.ballCount(), DrawRule.maxNumber(GameDataRepository.DEFAULT_GAME_CODE,
                issue.drawRuleVersion()), DrawRule.requiresDistinctNumbers(GameDataRepository.DEFAULT_GAME_CODE,
                issue.drawRuleVersion()), previewRandom::nextInt);
    }

    public List<Integer> generateFinalNumbers() {
        return generateFinalNumbers(7);
    }

    public List<Integer> generateFinalNumbers(int ballCount) {
        if (ballCount != 7 && ballCount != 8) {
            throw new IllegalArgumentException("期次球数只能是 7 或 8");
        }
        if (ballCount != DrawRule.AU8_BALL_COUNT) throw new IllegalArgumentException("AU8 固定为七球");
        return generateNumbers(ballCount, 40, true, (origin, bound) -> origin + RANDOM.nextInt(bound - origin));
    }

    private static List<Integer> generateNumbers(int count, int maxNumber, boolean distinct,
                                                  java.util.function.BiFunction<Integer, Integer, Integer> random) {
        List<Integer> result = new ArrayList<>(count);
        if (!distinct) {
            for (int index = 0; index < count; index++) result.add(random.apply(1, maxNumber + 1));
            return List.copyOf(result);
        }
        List<Integer> available = new ArrayList<>(maxNumber);
        for (int number = 1; number <= maxNumber; number++) available.add(number);
        for (int index = 0; index < count; index++) {
            result.add(available.remove(random.apply(0, available.size()).intValue()));
        }
        return List.copyOf(result);
    }

    private static String initialIssue(String gameCode) {
        return GameDataRepository.DEFAULT_GAME_CODE.equals(gameCode) ? INITIAL_ISSUE : gameCode + "-" + INITIAL_ISSUE;
    }

    private static String nextIssueNumber(String gameCode, String issueNumber) {
        String prefix = GameDataRepository.DEFAULT_GAME_CODE.equals(gameCode) ? "" : gameCode + "-";
        String numeric = issueNumber.startsWith(prefix) ? issueNumber.substring(prefix.length()) : issueNumber;
        try {
            return prefix + (Long.parseLong(numeric) + 1);
        } catch (NumberFormatException ignored) {
            return initialIssue(gameCode);
        }
    }

    private static String resultMessage(String issueNumber, List<Integer> numbers, String gameCode, int ruleVersion) {
        return issueNumber + "结果:\n" + numbers.stream()
                .map(number -> String.format("%02d", number))
                .reduce((left, right) -> left + "," + right).orElse("")
                + " => " + fanText(numbers.get(numbers.size() - 1), gameCode, ruleVersion);
    }

    private static String fanText(int special, String gameCode, int ruleVersion) {
        int fan = special % 4 == 0 ? 4 : special % 4;
        return fan + "," + (special % 2 == 0 ? "双" : "单") + ","
                + (DrawRule.isBig(special, gameCode, ruleVersion) ? "大" : "小");
    }

    private static BigDecimal payout(SettlementResult settlement) {
        return switch (settlement.status()) {
            case WIN -> settlement.stake().add(settlement.netProfit());
            case DRAW -> settlement.stake();
            case LOSE, PENDING, CANCELED -> BigDecimal.ZERO;
        };
    }
}
