package com.xupan.server.system.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.BetBoardRepository;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class BetBoardService {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 500;
    private static final int HISTORY_SIZE = 18;

    private final BetBoardRepository repository;
    private final GameDataRepository gameRepository;
    private final PermissionService permissionService;

    public BetBoardService(BetBoardRepository repository, GameDataRepository gameRepository,
                           PermissionService permissionService) {
        this.repository = repository;
        this.gameRepository = gameRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public Board board(String kind, int limit, long operatorUserId) {
        requireAdmin(operatorUserId);
        String playerKind = normalizeKind(kind);
        int safeLimit = normalizeLimit(limit);
        GameDataRepository.IssueRecord issue = gameRepository.findCurrentIssue()
                .or(() -> gameRepository.findLatestIssue())
                .orElse(null);
        if (issue == null) {
            return new Board(null, null, Instant.now(), null, 0, 0, money("0"),
                    money("0"), List.of(), List.of());
        }

        BetBoardRepository.AggregateSummary summary = repository.summarize(issue.issueNumber());
        List<BetItem> items = repository.findBets(issue.issueNumber(), playerKind, safeLimit).stream()
                .map(BetBoardService::toItem)
                .toList();
        List<DrawHistory> history = gameRepository.findSettledIssues(HISTORY_SIZE).stream()
                .map(BetBoardService::toHistory)
                .toList();
        return new Board(issue.issueNumber(), issue.phase(), Instant.now(), phaseEndsAt(issue),
                summary.normal().count(), summary.bot().count(),
                money(summary.normal().totalStake()), money(summary.bot().totalStake()),
                items, history);
    }

    private void requireAdmin(long operatorUserId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, "USER_MANAGE")) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "没有用户管理权限");
        }
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!"NORMAL".equals(value) && !"BOT".equals(value)) {
            throw BusinessException.badRequest("BET_BOARD_KIND_INVALID", "玩家类型必须是 NORMAL 或 BOT");
        }
        return value;
    }

    private static int normalizeLimit(int limit) {
        int value = limit == 0 ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > MAX_LIMIT) {
            throw BusinessException.badRequest("BET_BOARD_LIMIT_INVALID", "下注榜条数必须在 1 到 500 之间");
        }
        return value;
    }

    private static Instant phaseEndsAt(GameDataRepository.IssueRecord issue) {
        if ("BETTING".equals(issue.phase())) return issue.bettingEndsAt();
        if ("DRAWING".equals(issue.phase())) return issue.drawEndsAt();
        return issue.settledAt();
    }

    private static BetItem toItem(BetBoardRepository.BetItem item) {
        PlayType playType = PlayType.valueOf(item.playType());
        List<Integer> parameters = parseParameters(item.parametersText());
        return new BetItem(item.id(), item.displayName(), item.playerKind(),
                renderBetText(playType, parameters, item.stake()), item.stake(),
                item.settlementStatus(), item.createdAt());
    }

    private static DrawHistory toHistory(GameDataRepository.IssueRecord issue) {
        List<DrawBall> balls = new ArrayList<>(8);
        for (int index = 0; index < issue.numbers().size(); index++) {
            Integer number = issue.numbers().get(index);
            if (number == null) continue;
            BallResult result = BallResult.fromNumber(number);
            balls.add(new DrawBall(index + 1, result.number(), result.fan()));
        }
        return new DrawHistory(issue.issueNumber(), "已开", issue.settledAt(), balls);
    }

    private static String renderBetText(PlayType playType, List<Integer> parameters, BigDecimal stake) {
        String amount = compact(stake);
        String value = String.join("", parameters.stream().map(String::valueOf).toList());
        return switch (playType) {
            case FAN -> value + "番/" + amount;
            case ANGLE -> value + "角/" + amount;
            case CAR -> formatCar(parameters, amount);
            case STRICT -> parameter(parameters, 0) + "念" + parameter(parameters, 1) + "/" + amount;
            case ADD -> parameter(parameters, 0) + "加"
                    + parameter(parameters, 1) + parameter(parameters, 2) + "/" + amount;
            case POSITIVE -> value + "正/" + amount;
            case TONG -> parameter(parameters, 0) + "通"
                    + parameter(parameters, 1) + parameter(parameters, 2) + "/" + amount;
            case NONE -> formatNone(parameters, amount);
            case ODD_EVEN -> (parameters.contains(1) ? "单" : "双") + "/" + amount;
            case BIG_SMALL -> (parameters.contains(1) ? "大" : "小") + "/" + amount;
            case SPECIAL -> String.join("/", parameters.stream().map(String::valueOf).toList())
                    + "特/" + amount;
        };
    }

    private static String formatCar(List<Integer> parameters, String amount) {
        int missingFan = java.util.stream.IntStream.rangeClosed(1, 4)
                .filter(value -> !parameters.contains(value))
                .findFirst()
                .orElse(0);
        return missingFan + "车" + amount;
    }

    private static String formatNone(List<Integer> parameters, String amount) {
        if (parameters.size() == 2) {
            return parameter(parameters, 0) + "无" + parameter(parameters, 1) + "/" + amount;
        }
        return parameter(parameters, 0) + parameter(parameters, 1) + "无"
                + parameter(parameters, 2) + "/" + amount;
    }

    private static String parameter(List<Integer> parameters, int index) {
        return index < parameters.size() ? String.valueOf(parameters.get(index)) : "";
    }

    private static List<Integer> parseParameters(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return java.util.Arrays.stream(value.split(","))
                    .filter(part -> !part.isBlank())
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException exception) {
            return List.of();
        }
    }

    private static String compact(BigDecimal value) {
        BigDecimal normalized = money(value);
        return normalized.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    public record Board(String issueNumber, String phase, Instant serverNow, Instant phaseEndsAt,
                        long normalCount, long botCount, BigDecimal normalStake, BigDecimal botStake,
                        List<BetItem> items, List<DrawHistory> history) {
    }

    public record BetItem(long id, String displayName, String playerKind, String betText,
                          BigDecimal stake, String settlementStatus, Instant createdAt) {
    }

    public record DrawHistory(String issueNumber, String status, Instant settledAt, List<DrawBall> balls) {
    }

    public record DrawBall(int position, int number, int fan) {
    }
}
