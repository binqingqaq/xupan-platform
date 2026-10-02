package com.xupan.server.game.service;

import com.xupan.server.game.domain.BallResult;
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
import java.util.List;

@Service
public class 自动轮期服务 {

    public static final String BETTING = "BETTING";
    public static final String DRAWING = "DRAWING";
    public static final String SETTLED = "SETTLED";
    private static final String INITIAL_ISSUE = "3000000";
    private static final long BETTING_SECONDS = 180;
    private static final long ISSUE_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GameDataRepository gameRepository;
    private final GameIssueEventRepository eventRepository;
    private final SettlementService settlementService;
    private final VirtualWalletService walletService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final boolean automationEnabled;
    private final GameSettingsService gameSettingsService;

    @Autowired
    public 自动轮期服务(GameDataRepository gameRepository,
                        GameIssueEventRepository eventRepository,
                        SettlementService settlementService,
                        VirtualWalletService walletService,
                        ApplicationEventPublisher eventPublisher,
                        GameSettingsService gameSettingsService,
                        @Value("${xupan.automation.enabled:true}") boolean automationEnabled) {
        this(gameRepository, eventRepository, settlementService, walletService,
                eventPublisher, gameSettingsService, Clock.systemUTC(), automationEnabled);
    }

    自动轮期服务(GameDataRepository gameRepository,
                 GameIssueEventRepository eventRepository,
                 SettlementService settlementService,
                 VirtualWalletService walletService,
                 ApplicationEventPublisher eventPublisher,
                 GameSettingsService gameSettingsService,
                 Clock clock) {
        this(gameRepository, eventRepository, settlementService, walletService, eventPublisher,
                gameSettingsService, clock, true);
    }

    自动轮期服务(GameDataRepository gameRepository,
                 GameIssueEventRepository eventRepository,
                 SettlementService settlementService,
                 VirtualWalletService walletService,
                 ApplicationEventPublisher eventPublisher,
                 GameSettingsService gameSettingsService,
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
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void scheduledAdvance() {
        if (!automationEnabled) return;
        advanceAllActive(clock.instant());
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
        GameDataRepository.IssueRecord issue = ensureCurrentIssue(gameCode, now);
        if (issue.bettingEndsAt() == null || issue.drawEndsAt() == null) {
            gameRepository.initializeSchedule(issue.issueNumber(), issue.startedAt() == null ? now : issue.startedAt());
            issue = gameRepository.findCurrentIssue(gameCode).orElseThrow();
        }

        if (BETTING.equals(issue.phase())) {
            if (!now.isBefore(issue.bettingEndsAt().minusSeconds(30))) {
                eventRepository.appendOnce(issue.issueNumber(), "BETTING_WARNING",
                        "离封盘还剩30秒！\n20秒以内下注容易失败退单!", now);
            }
            if (!now.isBefore(issue.bettingEndsAt())
                    && gameRepository.transitionPhase(issue.issueNumber(), BETTING, DRAWING)) {
                eventRepository.appendOnce(issue.issueNumber(), "BETTING_CLOSED",
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
            eventRepository.appendOnce(created.issueNumber(), "ISSUE_STARTED",
                    created.issueNumber() + "期开始", created.startedAt());
            return created;
        });
    }

    private void finalizeIssue(String gameCode, GameDataRepository.IssueRecord issue, Instant now) {
        List<Integer> numbers = generateFinalNumbers();
        if (!gameRepository.saveFinalResult(issue.issueNumber(), numbers, now)) {
            return;
        }

        List<BallResult> results = numbers.stream().map(BallResult::fromNumber).toList();
        gameRepository.findBetsByIssue(issue.issueNumber()).stream()
                .filter(bet -> bet.settlementStatus() == SettlementStatus.PENDING)
                .forEach(bet -> {
                    SettlementResult settlement = settlementService.settle(
                            bet.playType(), bet.parameters(), bet.stake(), bet.odds(),
                            results.get(bet.ballNumber() - 1));
                    if (gameRepository.settleBetOnce(bet.id(), settlement)) {
                        BigDecimal payout = payout(settlement);
                        if (payout.signum() > 0) {
                            long settlementUserId = walletService.getByAccountId(bet.accountId()).userId();
                            walletService.creditForSettlement(settlementUserId, bet.id(), issue.issueNumber(), payout,
                                    "开奖结算：" + issue.issueNumber());
                        }
                        if (eventPublisher != null) {
                            eventPublisher.publishEvent(new BetSettlementCompletedEvent(
                                    bet.id(), bet.accountId(), issue.issueNumber(), settlement.status(),
                                    settlement.stake(), settlement.netProfit(), payout));
                        }
                    }
                });

        eventRepository.appendOnce(issue.issueNumber(), "DRAW_RESULT",
                resultMessage(issue.issueNumber(), numbers), now);

        String nextIssue = nextIssueNumber(gameCode, issue.issueNumber());
        if (gameRepository.findCurrentIssue(gameCode).isEmpty()) {
            gameRepository.saveBettingIssue(gameCode, nextIssue, issue.drawEndsAt());
            eventRepository.appendOnce(nextIssue, "ISSUE_STARTED", nextIssue + "期开始", issue.drawEndsAt());
        }
    }

    public List<Integer> previewNumbers(GameDataRepository.IssueRecord issue, Instant now) {
        if (!DRAWING.equals(issue.phase()) || issue.startedAt() == null) {
            return issue.numbers();
        }
        long tick = Math.max(0, Duration.between(issue.startedAt(), now).toSeconds());
        java.util.SplittableRandom previewRandom = new java.util.SplittableRandom(
                issue.issueNumber().hashCode() * 31L + tick);
        List<Integer> numbers = new ArrayList<>(8);
        for (int index = 0; index < 8; index++) {
            numbers.add(previewRandom.nextInt(1, 21));
        }
        return numbers;
    }

    public List<Integer> generateFinalNumbers() {
        List<Integer> numbers = new ArrayList<>(8);
        for (int index = 0; index < 8; index++) {
            numbers.add(RANDOM.nextInt(1, 21));
        }
        return numbers;
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

    private static String resultMessage(String issueNumber, List<Integer> numbers) {
        return issueNumber + "结果:\n" + numbers.stream()
                .map(number -> String.format("%02d", number))
                .reduce((left, right) -> left + "," + right).orElse("")
                + " => " + fanText(numbers.get(7));
    }

    private static String fanText(int special) {
        int fan = special % 4 == 0 ? 4 : special % 4;
        return fan + "," + (special % 2 == 0 ? "双" : "单") + ","
                + (special >= 11 ? "大" : "小");
    }

    private static BigDecimal payout(SettlementResult settlement) {
        return switch (settlement.status()) {
            case WIN -> settlement.stake().add(settlement.netProfit());
            case DRAW -> settlement.stake();
            case LOSE, PENDING, CANCELED -> BigDecimal.ZERO;
        };
    }
}
