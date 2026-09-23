package com.xupan.server.system.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.system.repository.PlayerPointsReportRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private static final int DEFAULT_DETAIL_LIMIT = 500;
    private static final int MAX_DETAIL_LIMIT = 1000;

    private final PlayerPointsReportRepository repository;
    private final PermissionService permissionService;

    public PlayerPointsReportService(PlayerPointsReportRepository repository, PermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public Report report(String kind, String date, int detailLimit, long operator) {
        requireAdmin(operator);
        String playerKind = normalizeKind(kind);
        int safeDetailLimit = normalizeDetailLimit(detailLimit);
        BusinessDay businessDay = resolveBusinessDay(date);

        List<PlayerPointsReportRepository.PlayerAccount> players = repository.findPlayers(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive());
        Map<Long, CardBuilder> cards = new LinkedHashMap<>();
        for (PlayerPointsReportRepository.PlayerAccount player : players) {
            cards.put(player.accountId(), new CardBuilder(player));
        }

        List<PlayerPointsReportRepository.BetEvent> bets = repository.findBetEvents(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), safeDetailLimit);
        for (PlayerPointsReportRepository.BetEvent bet : bets) {
            CardBuilder card = cards.get(bet.accountId());
            if (card != null) card.addBet(bet);
        }

        List<PlayerPointsReportRepository.LedgerEvent> ledgers = repository.findLedgerEvents(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), safeDetailLimit);
        for (PlayerPointsReportRepository.LedgerEvent ledger : ledgers) {
            CardBuilder card = cards.get(ledger.accountId());
            if (card != null) card.addLedger(ledger);
        }

        List<PlayerPointsReportRepository.ActionEvent> actions = repository.findActionEvents(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), safeDetailLimit);
        for (PlayerPointsReportRepository.ActionEvent action : actions) {
            CardBuilder card = cards.get(action.accountId());
            if (card != null) card.addAction(action);
        }

        List<Card> resultCards = cards.values().stream().map(CardBuilder::build).toList();
        return new Report(businessDay.businessDate(), businessDay.fromInclusive(), businessDay.toExclusive(),
                availableDates(businessDay.businessDate()), playerKind, summary(resultCards), resultCards, safeDetailLimit);
    }

    private static List<LocalDate> availableDates(LocalDate selectedDate) {
        LocalDate current = currentBusinessDate();
        List<LocalDate> dates = IntStream.rangeClosed(0, 6)
                .mapToObj(current::minusDays)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!dates.contains(selectedDate)) dates.add(selectedDate);
        dates.sort(Comparator.reverseOrder());
        return List.copyOf(dates);
    }

    static BusinessDay resolveBusinessDay(String requestedDate) {
        LocalDate currentBusinessDate = currentBusinessDate();
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
        ZonedDateTime start = date.atTime(BUSINESS_CUTOFF).atZone(BUSINESS_ZONE);
        return new BusinessDay(date, start.toInstant(), start.plusDays(1).toInstant());
    }

    static LocalDate currentBusinessDate() {
        ZonedDateTime now = Instant.now().atZone(BUSINESS_ZONE);
        return now.toLocalDate().minusDays(now.toLocalTime().isBefore(BUSINESS_CUTOFF) ? 1 : 0);
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

    private void requireAdmin(long operator) {
        if (operator <= 0 || !permissionService.hasPermission(operator, "USER_MANAGE")) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "没有用户管理权限");
        }
    }

    private static Summary summary(List<Card> cards) {
        SummaryBuilder result = new SummaryBuilder();
        for (Card card : cards) result.add(card);
        return result.build(cards.size());
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2);
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

        void add(Card card) {
            betCount += card.betCount();
            settledBetCount += card.settledBetCount();
            turnover = turnover.add(card.turnover());
            netProfit = netProfit.add(card.netProfit());
            topUp = topUp.add(card.topUp());
            down = down.add(card.down());
            openingBalance = openingBalance.add(card.openingBalance());
            closingBalance = closingBalance.add(card.closingBalance());
        }

        Summary build(int playerCount) {
            return new Summary(playerCount, betCount, settledBetCount, money(turnover), money(netProfit),
                    money(topUp), money(down), money(openingBalance), money(closingBalance));
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
        private final List<BetDetail> bets = new ArrayList<>();
        private final List<PointsOperation> pointOperations = new ArrayList<>();
        private final List<BotAction> botActions = new ArrayList<>();

        CardBuilder(PlayerPointsReportRepository.PlayerAccount player) { this.player = player; }

        void addBet(PlayerPointsReportRepository.BetEvent value) {
            betCount++;
            turnover = turnover.add(value.stake());
            if (!"PENDING".equals(value.settlementStatus())) {
                settledBetCount++;
                netProfit = netProfit.add(value.netProfit());
            }
            bets.add(new BetDetail(value.id(), value.betCode(), value.issueNumber(), value.ballNumber(),
                    value.playType(), value.parametersText(), value.stake(), value.odds(), value.settlementStatus(),
                    value.netProfit(), value.explanation(), value.createdAt(), value.settledAt()));
        }

        void addLedger(PlayerPointsReportRepository.LedgerEvent value) {
            if (("ADMIN_GRANT".equals(value.operationType()) || "ADMIN_ADJUST".equals(value.operationType()))) {
                if (value.amount().signum() > 0) topUp = topUp.add(value.amount());
                else down = down.add(value.amount().abs());
            }
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
            return new Card(player.userId(), player.accountId(), player.memberCode(), player.userCode(),
                    player.displayName(), player.playerKind(), money(player.openingBalance()), money(player.closingBalance()),
                    betCount, settledBetCount, money(turnover), money(netProfit), money(topUp), money(down),
                    bets, pointOperations, botActions);
        }
    }

    public record BusinessDay(LocalDate businessDate, Instant fromInclusive, Instant toExclusive) {}

    public record Report(LocalDate businessDate, Instant fromInclusive, Instant toExclusive, List<LocalDate> availableDates, String playerKind,
                         Summary summary, List<Card> players, int detailLimit) {}

    public record Summary(int playerCount, long betCount, long settledBetCount, BigDecimal turnover,
                          BigDecimal netProfit, BigDecimal topUp, BigDecimal down,
                          BigDecimal openingBalance, BigDecimal closingBalance) {}

    public record Card(long userId, long accountId, String memberCode, String userCode, String displayName,
                       String playerKind, BigDecimal openingBalance, BigDecimal closingBalance,
                       long betCount, long settledBetCount, BigDecimal turnover, BigDecimal netProfit,
                       BigDecimal topUp, BigDecimal down, List<BetDetail> bets,
                       List<PointsOperation> pointOperations, List<BotAction> botActions) {}

    public record BetDetail(long id, String betCode, String issueNumber, int ballNumber, String playType,
                            String parametersText, BigDecimal stake, BigDecimal odds, String settlementStatus,
                            BigDecimal netProfit, String explanation, Instant createdAt, Instant settledAt) {}

    public record PointsOperation(long id, String operationType, BigDecimal amount, BigDecimal balanceBefore,
                                  BigDecimal balanceAfter, Long operatorUserId, String operatorName,
                                  String idempotencyKey, Long relatedBetId, String issueNumber, String reason,
                                  Instant createdAt) {}

    public record BotAction(long id, String issueNumber, int actionNo, String actionType, String sourceText,
                            String status, int attempts, String errorCode, String errorMessage, Long messageId,
                            Long betId, Instant createdAt, Instant updatedAt) {}
}
