package com.xupan.server.game.service;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.repository.DemoAccountRepository;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.web.PlaceBetRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

@Service
public class DemoGameService {

    private static final String INITIAL_ISSUE = "DEMO-0001";
    private final SettlementService settlementService;
    private final GameDataRepository repository;
    private final DemoAccountRepository accountRepository;

    public DemoGameService(SettlementService settlementService, GameDataRepository repository,
                           DemoAccountRepository accountRepository) {
        this.settlementService = settlementService;
        this.repository = repository;
        this.accountRepository = accountRepository;
        ensureInitialized();
    }

    public synchronized GameView current() {
        GameDataRepository.IssueRecord issue = ensureInitialized();
        List<BallResult> results = toResults(issue.numbers());
        List<BallView> ballViews = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            BallResult result = results.size() == 8 ? results.get(index) : null;
            ballViews.add(result == null
                    ? new BallView(index + 1, null, null, null, null)
                    : new BallView(index + 1, result.number(), result.fan(), result.parity(), result.size()));
        }
        List<OddsView> oddsViews = new ArrayList<>();
        for (PlayType playType : PlayType.values()) {
            repository.findOdds(playType).ifPresent(value -> oddsViews.add(new OddsView(playType, value)));
        }
        List<BetView> betViews = repository.findBetsByIssue(issue.issueNumber()).stream()
                .map(DemoGameService::toBetView)
                .toList();
        DemoAccountRepository.AccountRecord account = accountRepository.findByCode(DemoAccountRepository.DEFAULT_USER_CODE);
        return new GameView(issue.issueNumber(), issue.status(), ballViews, oddsViews, betViews,
                new AccountView(account.userCode(), account.displayName(), account.balance(), account.status()));
    }

    @Transactional
    public synchronized BetView placeBet(PlaceBetRequest request) {
        GameDataRepository.IssueRecord issue = ensureInitialized();
        if (!"OPEN".equals(issue.status())) {
            throw new IllegalStateException("当前期已封盘，不能下注");
        }
        BigDecimal snapshotOdds = repository.findOdds(request.playType())
                .orElseThrow(() -> new IllegalArgumentException("玩法赔率不存在"));
        settlementService.validateBet(request.playType(), request.parameters(), request.stake(), snapshotOdds);
        accountRepository.debit(DemoAccountRepository.DEFAULT_USER_CODE, request.stake(),
                "下注扣款：" + issue.issueNumber());
        String betCode = "BET-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        repository.saveBetWithOddsSnapshot(1L, betCode, issue.issueNumber(), request.ballNumber(),
                request.playType(), request.parameters(), money(request.stake()), odds(snapshotOdds));
        return repository.findBetByCode(betCode)
                .map(DemoGameService::toBetView)
                .orElseThrow(() -> new IllegalStateException("下注保存后未找到注单"));
    }

    @Transactional
    public synchronized GameView draw(List<Integer> numbers) {
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
                .map(bet -> new PendingSettlement(bet.id(), settlementService.settle(
                        bet.playType(), bet.parameters(), bet.stake(), bet.odds(), results.get(bet.ballNumber() - 1))))
                .toList();

        repository.saveIssue(issue.issueNumber(), "CLOSED", numbers);
        for (PendingSettlement pending : settlements) {
            if (!repository.settleBetOnce(pending.betId(), pending.settlement())) {
                throw new IllegalStateException("注单已经结算或不存在");
            }
            accountRepository.credit(DemoAccountRepository.DEFAULT_USER_CODE, payout(pending.settlement()),
                    "开奖结算：" + issue.issueNumber());
        }
        return current();
    }

    public synchronized OddsView updateOdds(PlayType playType, BigDecimal newOdds) {
        if (playType == null || newOdds == null || newOdds.compareTo(BigDecimal.ONE) < 0 || newOdds.scale() > 3) {
            throw new IllegalArgumentException("赔率必须不小于 1，最多三位小数");
        }
        ensureInitialized();
        repository.saveOdds(playType, odds(newOdds));
        return new OddsView(playType, repository.findOdds(playType).orElseThrow());
    }

    public synchronized GameView resetIssue() {
        GameDataRepository.IssueRecord issue = ensureInitialized();
        if ("OPEN".equals(issue.status())) {
            throw new IllegalStateException("当前期尚未开奖，不能开启下一期");
        }
        int nextSequence = Integer.parseInt(issue.issueNumber().replace("DEMO-", "")) + 1;
        repository.saveIssue("DEMO-" + String.format("%04d", nextSequence), "OPEN", null);
        return current();
    }

    private GameDataRepository.IssueRecord ensureInitialized() {
        GameDataRepository.IssueRecord issue = repository.findLatestIssue().orElse(null);
        if (issue == null) {
            repository.saveIssue(INITIAL_ISSUE, "OPEN", null);
            issue = repository.findLatestIssue().orElseThrow();
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

    private static List<BallResult> toResults(List<Integer> numbers) {
        if (numbers.size() != 8 || numbers.stream().anyMatch(number -> number == null)) {
            return List.of();
        }
        return numbers.stream().map(BallResult::fromNumber).toList();
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

    public record GameView(String issueNumber, String status, List<BallView> balls,
                           List<OddsView> odds, List<BetView> bets, AccountView account) {
    }

    public record BallView(int ballNumber, Integer number, Integer fan, String parity, String size) {
    }

    public record OddsView(PlayType playType, BigDecimal odds) {
    }

    public record BetView(String id, String issueNumber, int ballNumber, PlayType playType,
                          List<Integer> parameters, BigDecimal stake, BigDecimal odds,
                          SettlementStatus settlementStatus, BigDecimal netProfit, String explanation) {
    }

    public record AccountView(String userCode, String displayName, BigDecimal balance, String status) {
    }

    private record PendingSettlement(long betId, SettlementResult settlement) {
    }

    private static BigDecimal payout(SettlementResult settlement) {
        return switch (settlement.status()) {
            case WIN -> settlement.stake().add(settlement.netProfit());
            case DRAW -> settlement.stake();
            case LOSE, PENDING -> BigDecimal.ZERO;
        };
    }
}
