package com.xupan.server.game.service;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.repository.DemoAccountRepository;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.repository.GameIssueEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

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
    private final DemoAccountRepository accountRepository;
    private final Clock clock;
    private final boolean automationEnabled;

    @Autowired
    public 自动轮期服务(GameDataRepository gameRepository,
                        GameIssueEventRepository eventRepository,
                        SettlementService settlementService,
                        DemoAccountRepository accountRepository,
                        @Value("${xupan.automation.enabled:true}") boolean automationEnabled) {
        this(gameRepository, eventRepository, settlementService, accountRepository,
                Clock.systemUTC(), automationEnabled);
    }

    自动轮期服务(GameDataRepository gameRepository,
                 GameIssueEventRepository eventRepository,
                 SettlementService settlementService,
                 DemoAccountRepository accountRepository,
                 Clock clock) {
        this(gameRepository, eventRepository, settlementService, accountRepository, clock, true);
    }

    自动轮期服务(GameDataRepository gameRepository,
                 GameIssueEventRepository eventRepository,
                 SettlementService settlementService,
                 DemoAccountRepository accountRepository,
                 Clock clock,
                 boolean automationEnabled) {
        this.gameRepository = gameRepository;
        this.eventRepository = eventRepository;
        this.settlementService = settlementService;
        this.accountRepository = accountRepository;
        this.clock = clock;
        this.automationEnabled = automationEnabled;
    }

    @Scheduled(fixedDelay = 1000)
    public void scheduledAdvance() {
        if (automationEnabled) {
            advance(clock.instant());
        }
    }

    @Transactional
    public synchronized void advance(Instant now) {
        GameDataRepository.IssueRecord issue = ensureCurrentIssue(now);
        if (issue.bettingEndsAt() == null || issue.drawEndsAt() == null) {
            gameRepository.initializeSchedule(issue.issueNumber(), issue.startedAt() == null ? now : issue.startedAt());
            issue = gameRepository.findCurrentIssue().orElseThrow();
        }

        if (BETTING.equals(issue.phase())) {
            if (!now.isBefore(issue.bettingEndsAt().minusSeconds(30))) {
                eventRepository.appendOnce(issue.issueNumber(), "BETTING_WARNING",
                        "离封盘还剩30秒！\n20秒以内下注容易失败退单!", now);
            }
            if (!now.isBefore(issue.bettingEndsAt())
                    && gameRepository.transitionPhase(issue.issueNumber(), BETTING, DRAWING)) {
                eventRepository.appendOnce(issue.issueNumber(), "BETTING_CLOSED",
                        issue.issueNumber() + "期停止\n-----------\n进入开奖中，停止下注!", now);
                issue = gameRepository.findCurrentIssue().orElseThrow();
            }
        }

        if (DRAWING.equals(issue.phase()) && !now.isBefore(issue.drawEndsAt())) {
            finalizeIssue(issue, now);
        }
    }

    private GameDataRepository.IssueRecord ensureCurrentIssue(Instant now) {
        return gameRepository.findCurrentIssue().orElseGet(() -> {
            GameDataRepository.IssueRecord latest = gameRepository.findLatestIssue().orElse(null);
            if (latest == null) {
                gameRepository.saveBettingIssue(INITIAL_ISSUE, now);
            } else {
                gameRepository.saveBettingIssue(nextIssueNumber(latest.issueNumber()),
                        latest.settledAt() == null ? now : latest.settledAt());
            }
            GameDataRepository.IssueRecord created = gameRepository.findCurrentIssue().orElseThrow();
            eventRepository.appendOnce(created.issueNumber(), "ISSUE_STARTED",
                    created.issueNumber() + "期开始", created.startedAt());
            return created;
        });
    }

    private void finalizeIssue(GameDataRepository.IssueRecord issue, Instant now) {
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
                        accountRepository.credit(DemoAccountRepository.DEFAULT_USER_CODE,
                                payout(settlement), "开奖结算：" + issue.issueNumber());
                    }
                });

        eventRepository.appendOnce(issue.issueNumber(), "DRAW_RESULT",
                resultMessage(issue.issueNumber(), numbers), now);

        String nextIssue = nextIssueNumber(issue.issueNumber());
        if (gameRepository.findCurrentIssue().isEmpty()) {
            gameRepository.saveBettingIssue(nextIssue, issue.drawEndsAt());
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

    private static String nextIssueNumber(String issueNumber) {
        try {
            return String.valueOf(Long.parseLong(issueNumber) + 1);
        } catch (NumberFormatException ignored) {
            return INITIAL_ISSUE;
        }
    }

    private static String resultMessage(String issueNumber, List<Integer> numbers) {
        return issueNumber + "结果:\n" + numbers.stream()
                .map(number -> String.format("%02d", number))
                .reduce((left, right) -> left + "," + right).orElse("")
                + "\n机器人已完成开奖和结算";
    }

    private static BigDecimal payout(SettlementResult settlement) {
        return switch (settlement.status()) {
            case WIN -> settlement.stake().add(settlement.netProfit());
            case DRAW -> settlement.stake();
            case LOSE, PENDING -> BigDecimal.ZERO;
        };
    }
}
