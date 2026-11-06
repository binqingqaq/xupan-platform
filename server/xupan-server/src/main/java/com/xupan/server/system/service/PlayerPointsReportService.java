package com.xupan.server.system.service;

import com.xupan.server.system.repository.PlayerPointsReportRepository;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.service.BetFlowMetricsCalculator;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

@Service
public class PlayerPointsReportService {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime BUSINESS_CUTOFF = LocalTime.of(6, 0);
    private static final LocalTime MIDNIGHT_CUTOFF = LocalTime.MIDNIGHT;
    private static final int DEFAULT_DETAIL_LIMIT = 500;
    private static final int MAX_DETAIL_LIMIT = 1000;

    private final PlayerPointsReportRepository repository;
    private final PlayerDeskScopeService scopeService;

    public PlayerPointsReportService(PlayerPointsReportRepository repository, PlayerDeskScopeService scopeService) {
        this.repository = repository;
        this.scopeService = scopeService;
    }

    @Transactional(readOnly = true)
    public Report report(String kind, String date, int detailLimit, long operator) {
        return report(kind, date, detailLimit, 6, operator);
    }

    @Transactional(readOnly = true)
    public Report report(String kind, String date, int detailLimit, int cutoffHour, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        String playerKind = normalizeKind(kind);
        int safeDetailLimit = normalizeDetailLimit(detailLimit);
        cutoffTime(cutoffHour);
        BusinessDay businessDay = resolveBusinessDay(date, cutoffHour);

        List<PlayerPointsReportRepository.PlayerAccount> players = repository.findPlayers(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), scope);
        Map<Long, CardBuilder> cards = new LinkedHashMap<>();
        for (PlayerPointsReportRepository.PlayerAccount player : players) {
            cards.put(player.accountId(), new CardBuilder(player));
        }

        repository.forEachBetEvent(playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), scope, bet -> {
            CardBuilder card = cards.get(bet.accountId());
            if (card != null) card.addBetAggregate(bet);
        });

        List<PlayerPointsReportRepository.BetEvent> bets = repository.findBetEvents(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), safeDetailLimit, scope);
        for (PlayerPointsReportRepository.BetEvent bet : bets) {
            CardBuilder card = cards.get(bet.accountId());
            if (card != null) card.addBetDetail(bet);
        }

        for (PlayerPointsReportRepository.LedgerTotals totals : repository.findLedgerTotals(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), scope)) {
            CardBuilder card = cards.get(totals.accountId());
            if (card != null) card.addLedgerTotals(totals);
        }

        List<PlayerPointsReportRepository.LedgerEvent> ledgers = repository.findLedgerEvents(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), safeDetailLimit, scope);
        for (PlayerPointsReportRepository.LedgerEvent ledger : ledgers) {
            CardBuilder card = cards.get(ledger.accountId());
            if (card != null) card.addLedger(ledger);
        }

        List<PlayerPointsReportRepository.ActionEvent> actions = repository.findActionEvents(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), safeDetailLimit, scope);
        for (PlayerPointsReportRepository.ActionEvent action : actions) {
            CardBuilder card = cards.get(action.accountId());
            if (card != null) card.addAction(action);
        }

        List<Card> resultCards = cards.values().stream().map(CardBuilder::build).toList();
        String rebateMode = scope instanceof PlayerDeskScopeService.Scope.Machine machine
                ? normalizeRebateMode(repository.findAgentRebateMode(machine.agentId()))
                : reportRebateMode(resultCards);
        return new Report(businessDay.businessDate(), businessDay.fromInclusive(), businessDay.toExclusive(),
                cutoffHour, availableDates(businessDay.businessDate(), cutoffHour),
                availableDates(businessDay.businessDate(), 0), availableDates(businessDay.businessDate(), 6),
                playerKind, rebateMode,
                summary(resultCards), resultCards, safeDetailLimit);
    }

    private static List<LocalDate> availableDates(LocalDate selectedDate, int cutoffHour) {
        LocalDate current = currentBusinessDate(cutoffHour);
        List<LocalDate> dates = IntStream.rangeClosed(0, 6)
                .mapToObj(current::minusDays)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!selectedDate.isAfter(current) && !dates.contains(selectedDate)) dates.add(selectedDate);
        dates.sort(Comparator.reverseOrder());
        return List.copyOf(dates);
    }

    static BusinessDay resolveBusinessDay(String requestedDate) {
        return resolveBusinessDay(requestedDate, 6);
    }

    static BusinessDay resolveBusinessDay(String requestedDate, int cutoffHour) {
        LocalTime cutoff = cutoffTime(cutoffHour);
        LocalDate currentBusinessDate = currentBusinessDate(cutoffHour);
        LocalDate date;
        if (requestedDate == null || requestedDate.isBlank()) {
            date = currentBusinessDate;
        } else {
            try {
                date = LocalDate.parse(requestedDate.trim());
            } catch (DateTimeParseException exception) {
                throw BusinessException.badRequest("POINTS_REPORT_DATE_INVALID", "日期必须使用 yyyy-MM-dd 格式");
            }
            if (date.isAfter(currentBusinessDate)) {
                throw BusinessException.badRequest("POINTS_REPORT_DATE_INVALID", "不能查询未来业务日");
            }
        }
        ZonedDateTime start = date.atTime(cutoff).atZone(BUSINESS_ZONE);
        return new BusinessDay(date, start.toInstant(), start.plusDays(1).toInstant());
    }

    static LocalDate currentBusinessDate() {
        return currentBusinessDate(6);
    }

    static LocalDate currentBusinessDate(int cutoffHour) {
        LocalTime cutoff = cutoffTime(cutoffHour);
        ZonedDateTime now = Instant.now().atZone(BUSINESS_ZONE);
        return now.toLocalDate().minusDays(now.toLocalTime().isBefore(cutoff) ? 1 : 0);
    }

    private static LocalTime cutoffTime(int cutoffHour) {
        if (cutoffHour == 0) return MIDNIGHT_CUTOFF;
        if (cutoffHour == 6) return BUSINESS_CUTOFF;
        throw BusinessException.badRequest("POINTS_REPORT_CUTOFF_INVALID", "业务日截点只能是 0 点或 6 点");
    }

    static int normalizeDetailLimit(int detailLimit) {
        int value = detailLimit == 0 ? DEFAULT_DETAIL_LIMIT : detailLimit;
        if (value < 1 || value > MAX_DETAIL_LIMIT) {
            throw BusinessException.badRequest("POINTS_REPORT_LIMIT_INVALID", "明细数量必须在 1 到 1000 之间");
        }
        return value;
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!"NORMAL".equals(value) && !"BOT".equals(value)) {
            throw BusinessException.badRequest("POINTS_REPORT_KIND_INVALID", "玩家类型必须是 NORMAL 或 BOT");
        }
        return value;
    }

    private static Summary summary(List<Card> cards) {
        SummaryBuilder result = new SummaryBuilder();
        for (Card card : cards) result.add(card);
        return result.build(cards.size());
    }

    private static String reportRebateMode(List<Card> cards) {
        List<String> modes = cards.stream().map(Card::rebateMode).distinct().toList();
        if (modes.isEmpty()) return "SINGLE";
        return modes.size() == 1 ? modes.getFirst() : "MIXED";
    }

    private static String normalizeRebateMode(String value) {
        return "DOUBLE".equalsIgnoreCase(value) ? "DOUBLE" : "SINGLE";
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static final class SummaryBuilder {
        private long betCount;
        private long settledBetCount;
        private BigDecimal turnover = BigDecimal.ZERO;
        private BigDecimal netProfit = BigDecimal.ZERO;
        private BigDecimal topUp = BigDecimal.ZERO;
        private BigDecimal down = BigDecimal.ZERO;
        private BigDecimal openingBalance = BigDecimal.ZERO;
        private BigDecimal closingBalance = BigDecimal.ZERO;
        private BigDecimal singleFlow = BigDecimal.ZERO;
        private BigDecimal doubleFlow = BigDecimal.ZERO;
        private BigDecimal singleRebate = BigDecimal.ZERO;
        private BigDecimal doubleRebate = BigDecimal.ZERO;
        private BigDecimal effectiveFlow = BigDecimal.ZERO;
        private BigDecimal effectiveRebate = BigDecimal.ZERO;
        private BigDecimal flyersWait = BigDecimal.ZERO;
        private BigDecimal flyersSuccess = BigDecimal.ZERO;
        private BigDecimal flyersFail = BigDecimal.ZERO;

        void add(Card card) {
            betCount += card.betCount();
            settledBetCount += card.settledBetCount();
            turnover = turnover.add(card.turnover());
            netProfit = netProfit.add(card.netProfit());
            topUp = topUp.add(card.topUp());
            down = down.add(card.down());
            openingBalance = openingBalance.add(card.openingBalance());
            closingBalance = closingBalance.add(card.closingBalance());
            singleFlow = singleFlow.add(card.singleFlow());
            doubleFlow = doubleFlow.add(card.doubleFlow());
            singleRebate = singleRebate.add(card.singleRebate());
            doubleRebate = doubleRebate.add(card.doubleRebate());
            effectiveFlow = effectiveFlow.add(card.effectiveFlow());
            effectiveRebate = effectiveRebate.add(card.effectiveRebate());
            flyersWait = flyersWait.add(card.flyersWait());
            flyersSuccess = flyersSuccess.add(card.flyersSuccess());
            flyersFail = flyersFail.add(card.flyersFail());
        }

        Summary build(int playerCount) {
            return new Summary(playerCount, betCount, settledBetCount, money(turnover), money(netProfit),
                    money(topUp), money(down), money(openingBalance), money(closingBalance), money(singleFlow),
                    money(doubleFlow), money(singleRebate), money(doubleRebate), money(effectiveFlow), money(effectiveRebate),
                    money(flyersWait), money(flyersSuccess), money(flyersFail));
        }
    }

    private static final class CardBuilder {
        private final PlayerPointsReportRepository.PlayerAccount player;
        private long betCount;
        private long settledBetCount;
        private BigDecimal turnover = BigDecimal.ZERO;
        private BigDecimal netProfit = BigDecimal.ZERO;
        private BigDecimal topUp = BigDecimal.ZERO;
        private BigDecimal down = BigDecimal.ZERO;
        private final String rebateMode;
        private BigDecimal singleFlow = BigDecimal.ZERO;
        private BigDecimal doubleFlow = BigDecimal.ZERO;
        private BigDecimal singleRebate = BigDecimal.ZERO;
        private BigDecimal doubleRebate = BigDecimal.ZERO;
        private BigDecimal flyersWait = BigDecimal.ZERO;
        private BigDecimal flyersSuccess = BigDecimal.ZERO;
        private BigDecimal flyersFail = BigDecimal.ZERO;
        private final List<BetDetail> bets = new ArrayList<>();
        private final List<PointsOperation> pointOperations = new ArrayList<>();
        private final List<BotAction> botActions = new ArrayList<>();

        CardBuilder(PlayerPointsReportRepository.PlayerAccount player) {
            this.player = player;
            this.rebateMode = "DOUBLE".equalsIgnoreCase(player.rebateMode()) ? "DOUBLE" : "SINGLE";
        }

        void addBetAggregate(PlayerPointsReportRepository.BetEvent value) {
            betCount++;
            turnover = turnover.add(value.stake());
            if (!"PENDING".equals(value.settlementStatus())) {
                settledBetCount++;
                netProfit = netProfit.add(value.netProfit());
            }
            PlayType playType = PlayType.valueOf(value.playType());
            BetFlowMetricsCalculator.Metrics metrics = BetFlowMetricsCalculator.calculate(
                    playType, value.parametersText(), value.stake(), value.odds(),
                    value.netProfit(), value.settlementStatus(), value.rebateRate(), value.specialRebateRate(),
                    playType == PlayType.SPECIAL ? value.selectionUnitStake() : value.stake());
            singleFlow = singleFlow.add(metrics.singleFlow());
            doubleFlow = doubleFlow.add(metrics.doubleFlow());
            singleRebate = singleRebate.add(metrics.fanShui());
            doubleRebate = doubleRebate.add(metrics.doubleFanShui());
            if ("NORMAL".equals(player.playerKind())) {
                BigDecimal flyAmount = value.stake();
                switch (flyBucket(value.reportStatus(), value.reportResponseCode())) {
                    case "WAIT" -> flyersWait = flyersWait.add(flyAmount);
                    case "SUCCESS" -> flyersSuccess = flyersSuccess.add(flyAmount);
                    case "FAIL" -> flyersFail = flyersFail.add(flyAmount);
                    default -> { }
                }
            }
        }

        void addBetDetail(PlayerPointsReportRepository.BetEvent value) {
            bets.add(new BetDetail(value.id(), value.betCode(), value.issueNumber(), value.ballNumber(),
                    value.playType(), value.parametersText(), value.stake(), value.odds(), value.settlementStatus(),
                    value.netProfit(), value.explanation(), value.createdAt(), value.settledAt(), value.gameCode(),
                    value.reportStatus(), value.reportResponseCode(), value.drawNumbers()));
        }

        void addLedgerTotals(PlayerPointsReportRepository.LedgerTotals value) {
            topUp = topUp.add(value.topUp());
            down = down.add(value.down());
        }

        void addLedger(PlayerPointsReportRepository.LedgerEvent value) {
            pointOperations.add(new PointsOperation(value.id(), value.operationType(), value.amount(),
                    value.balanceBefore(), value.balanceAfter(), value.operatorUserId(), value.operatorName(),
                    value.idempotencyKey(), value.relatedBetId(), value.issueNumber(), value.reason(), value.createdAt()));
        }

        void addAction(PlayerPointsReportRepository.ActionEvent value) {
            botActions.add(new BotAction(value.id(), value.issueNumber(), value.actionNo(), value.actionType(),
                    value.sourceText(), value.status(), value.attempts(), value.errorCode(), value.errorMessage(),
                    value.messageId(), value.betId(), value.createdAt(), value.updatedAt()));
        }

        Card build() {
            boolean doubleMode = "DOUBLE".equals(rebateMode);
            return new Card(player.userId(), player.accountId(), player.memberCode(), player.userCode(),
                    player.displayName(), player.playerKind(), money(player.openingBalance()), money(player.closingBalance()),
                    betCount, settledBetCount, money(turnover), money(netProfit), money(topUp), money(down),
                    money(singleFlow), money(doubleFlow), money(singleRebate), money(doubleRebate),
                    money(doubleMode ? doubleFlow : singleFlow), money(doubleMode ? doubleRebate : singleRebate),
                    money(flyersWait), money(flyersSuccess), money(flyersFail),
                    rebateMode, bets, pointOperations, botActions);
        }
    }

    private static String flyBucket(String status, String responseCode) {
        if ("CENTER_CANCELLED".equals(responseCode) || "CENTER_REFUNDED".equals(responseCode)) return "SUCCESS";
        if ("NOT_APPLICABLE".equals(status)) return "";
        return switch (status == null ? "NOT_SENT" : status) {
            case "NOT_SENT", "PENDING" -> "WAIT";
            case "ACCEPTED", "SETTLED" -> "SUCCESS";
            case "REJECTED", "TIMEOUT" -> "FAIL";
            case "MANUAL" -> "RETRY_EXHAUSTED".equals(responseCode) ? "FAIL" : "";
            default -> "";
        };
    }

    public record BusinessDay(LocalDate businessDate, Instant fromInclusive, Instant toExclusive) {}

    public record Report(LocalDate businessDate, Instant fromInclusive, Instant toExclusive, int cutoffHour,
                         List<LocalDate> availableDates, List<LocalDate> availableDatesAt00,
                         List<LocalDate> availableDatesAt06, String playerKind, String rebateMode,
                         Summary summary, List<Card> players, int detailLimit) {}

    public record Summary(int playerCount, long betCount, long settledBetCount, BigDecimal turnover,
                          BigDecimal netProfit, BigDecimal topUp, BigDecimal down,
                          BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal singleFlow,
                          BigDecimal doubleFlow, BigDecimal singleRebate, BigDecimal doubleRebate,
                          BigDecimal effectiveFlow, BigDecimal effectiveRebate, BigDecimal flyersWait,
                          BigDecimal flyersSuccess, BigDecimal flyersFail) {}

    public record Card(long userId, long accountId, String memberCode, String userCode, String displayName,
                       String playerKind, BigDecimal openingBalance, BigDecimal closingBalance,
                       long betCount, long settledBetCount, BigDecimal turnover, BigDecimal netProfit,
                       BigDecimal topUp, BigDecimal down, BigDecimal singleFlow, BigDecimal doubleFlow,
                       BigDecimal singleRebate, BigDecimal doubleRebate, BigDecimal effectiveFlow,
                        BigDecimal effectiveRebate, BigDecimal flyersWait, BigDecimal flyersSuccess,
                        BigDecimal flyersFail, String rebateMode, List<BetDetail> bets,
                       List<PointsOperation> pointOperations, List<BotAction> botActions) {}

    public record BetDetail(long id, String betCode, String issueNumber, int ballNumber, String playType,
                            String parametersText, BigDecimal stake, BigDecimal odds, String settlementStatus,
                            BigDecimal netProfit, String explanation, Instant createdAt, Instant settledAt,
                            String gameCode, String reportStatus, String reportResponseCode,
                            List<Integer> drawNumbers) {}

    public record PointsOperation(long id, String operationType, BigDecimal amount, BigDecimal balanceBefore,
                                  BigDecimal balanceAfter, Long operatorUserId, String operatorName,
                                  String idempotencyKey, Long relatedBetId, String issueNumber, String reason,
                                  Instant createdAt) {}

    public record BotAction(long id, String issueNumber, int actionNo, String actionType, String sourceText,
                            String status, int attempts, String errorCode, String errorMessage, Long messageId,
                            Long betId, Instant createdAt, Instant updatedAt) {}
}
