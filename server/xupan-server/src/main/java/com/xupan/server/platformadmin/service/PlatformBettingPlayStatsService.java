package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PlatformBettingPlayStatsService {

    private static final String READ_PERMISSION = "BETTING_PLAY_STATS_READ";
    private static final String PRESET_PERMISSION = "BETTING_DRAW_PRESET_MANAGE";

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;

    public PlatformBettingPlayStatsService(JdbcTemplate jdbc, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public CurrentStats current(String gameCode, long operator) {
        require(operator, READ_PERMISSION);
        String normalizedGameCode = normalizeGameCode(gameCode);
        GameInfo game = jdbc.query("""
                SELECT display_name, next_issue_ball_count
                  FROM game_definition
                 WHERE game_code = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, rs -> rs.next() ? new GameInfo(rs.getString("display_name"),
                rs.getInt("next_issue_ball_count")) : null,
                normalizedGameCode);
        if (game == null) {
            throw BusinessException.notFound("BETTING_PLAY_STATS_GAME_NOT_FOUND", "未找到可查看的彩种");
        }
        if (game.nextIssueBallCount() < 1 || game.nextIssueBallCount() > 8) {
            throw new IllegalStateException("彩种球数配置无效");
        }

        CurrentIssue issue = currentIssue(normalizedGameCode);
        Instant now = Instant.now();
        if (issue == null) {
            return new CurrentStats(normalizedGameCode, game.displayName(), null, "NONE", game.nextIssueBallCount(),
                    null, null, now, List.of());
        }

        List<PlayRow> playRows = jdbc.query("""
                SELECT b.ball_number, b.play_type, COUNT(*) AS order_count,
                       COALESCE(SUM(b.stake), 0) AS total_stake
                  FROM game_bet b
                  LEFT JOIN demo_user_account player_account ON player_account.id = b.user_id
                 WHERE b.game_code = ? AND b.issue_number = ? AND b.settlement_status = 'PENDING'
                   AND COALESCE(player_account.agent_id, b.agent_id_snapshot) IS NOT NULL
                   AND COALESCE(player_account.player_kind, b.player_kind_snapshot) = 'NORMAL'
                   AND b.ball_number BETWEEN 1 AND ?
                 GROUP BY b.ball_number, b.play_type
                """, (rs, rowNum) -> new PlayRow(rs.getInt("ball_number"), rs.getString("play_type"),
                rs.getLong("order_count"), money(rs.getBigDecimal("total_stake"))),
                normalizedGameCode, issue.issueNumber(), issue.ballCount());

        Map<Integer, List<PlayStats>> playsByBall = new LinkedHashMap<>();
        for (int ballNumber = 1; ballNumber <= issue.ballCount(); ballNumber++) {
            playsByBall.put(ballNumber, new ArrayList<>());
        }
        for (PlayRow row : playRows) {
            List<PlayStats> ballPlays = playsByBall.get(row.ballNumber());
            if (ballPlays != null) {
                ballPlays.add(new PlayStats(row.playType(), playName(row.playType()), row.orderCount(), row.totalStake()));
            }
        }

        Comparator<PlayStats> ranking = Comparator.comparingLong(PlayStats::orderCount).reversed()
                .thenComparing(PlayStats::totalStake, Comparator.reverseOrder())
                .thenComparingInt(play -> playOrder(play.playType()));
        List<BallStats> balls = new ArrayList<>();
        for (int ballNumber = 1; ballNumber <= issue.ballCount(); ballNumber++) {
            List<PlayStats> ranked = playsByBall.get(ballNumber);
            ranked.sort(ranking);
            long orderCount = ranked.stream().mapToLong(PlayStats::orderCount).sum();
            BigDecimal totalStake = ranked.stream().map(PlayStats::totalStake)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            balls.add(new BallStats(ballNumber, issue.numbers().get(ballNumber - 1),
                    orderCount, totalStake, List.copyOf(ranked)));
        }
        return new CurrentStats(normalizedGameCode, game.displayName(), issue.issueNumber(), issue.phase(),
                issue.ballCount(),
                issue.bettingEndsAt(), issue.drawEndsAt(), now, List.copyOf(balls));
    }

    @Transactional
    public PresetSummary preset(String expectedGameCode, String expectedIssueNumber,
                                int ballNumber, Integer number, long operator) {
        require(operator, PRESET_PERMISSION);
        String normalizedGameCode = normalizeGameCode(expectedGameCode);
        String normalizedIssueNumber = requiredIssueNumber(expectedIssueNumber);
        if (ballNumber < 1 || ballNumber > 8) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_BALL_INVALID", "球位必须在 1 到 8 之间");
        }
        if (number != null && (number < 1 || number > 20)) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_NUMBER_INVALID", "预设号码必须在 1 到 20 之间");
        }

        CurrentIssue issue = currentIssueForUpdate(normalizedGameCode);
        if (issue == null) {
            throw BusinessException.notFound("BETTING_DRAW_PRESET_ISSUE_NOT_FOUND", "当前没有可预设的开奖期次");
        }
        if (!issue.issueNumber().equals(normalizedIssueNumber)) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_STALE_ISSUE",
                    "当前期号已变化，请刷新后重新选择期次");
        }
        if (ballNumber > issue.ballCount()) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_BALL_INVALID", "球位超出当前期球数范围");
        }
        if (issue.drawEndsAt() == null || !Instant.now().isBefore(issue.drawEndsAt())) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_CLOSED", "已到开奖时间，不能再修改预设号码");
        }

        Integer previous = issue.numbers().get(ballNumber - 1);
        String column = "number_" + ballNumber;
        int updated = jdbc.update("UPDATE game_issue SET " + column + " = ?, "
                + "draw_result_version = draw_result_version + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE game_code = ? AND issue_number = ? AND phase IN ('BETTING', 'DRAWING') "
                + "AND draw_confirmed_at IS NULL", number, issue.gameCode(), issue.issueNumber());
        if (updated != 1) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_CLOSED", "开奖结果已确认或期次已结束，不能修改预设");
        }
        audit(operator, issue, ballNumber, previous, number);
        return new PresetSummary(issue.gameCode(), issue.issueNumber(), issue.phase(), ballNumber,
                number, issue.drawEndsAt(), Instant.now());
    }

    private CurrentIssue currentIssue(String gameCode) {
        List<CurrentIssue> rows = jdbc.query("""
                SELECT game_code, issue_number, phase, ball_count,
                       number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                       betting_ends_at, draw_ends_at, draw_confirmed_at
                  FROM game_issue
                 WHERE game_code = ? AND phase IN ('BETTING', 'DRAWING')
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, rowNum) -> mapIssue(rs.getString("game_code"), rs.getString("issue_number"),
                rs.getString("phase"), rs.getInt("ball_count"), readNumbers(rs, rs.getInt("ball_count")),
                instant(rs.getTimestamp("betting_ends_at")), instant(rs.getTimestamp("draw_ends_at")),
                rs.getTimestamp("draw_confirmed_at") != null), gameCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CurrentIssue currentIssueForUpdate(String gameCode) {
        List<CurrentIssue> rows = jdbc.query("""
                SELECT game_code, issue_number, phase, ball_count,
                       number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                       betting_ends_at, draw_ends_at, draw_confirmed_at
                  FROM game_issue
                 WHERE game_code = ? AND phase IN ('BETTING', 'DRAWING')
                 ORDER BY id DESC
                 LIMIT 1
                 FOR UPDATE
                """, (rs, rowNum) -> mapIssue(rs.getString("game_code"), rs.getString("issue_number"),
                rs.getString("phase"), rs.getInt("ball_count"), readNumbers(rs, rs.getInt("ball_count")),
                instant(rs.getTimestamp("betting_ends_at")), instant(rs.getTimestamp("draw_ends_at")),
                rs.getTimestamp("draw_confirmed_at") != null), gameCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CurrentIssue mapIssue(String gameCode, String issueNumber, String phase, int ballCount,
                                  List<Integer> numbers, Instant bettingEndsAt, Instant drawEndsAt,
                                  boolean confirmed) {
        if (ballCount < 1 || ballCount > 8 || numbers.size() != ballCount) {
            throw new IllegalStateException("当前期球数或开奖号码列不合法");
        }
        if (confirmed) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_CONFIRMED", "开奖结果已确认，不能修改预设");
        }
        return new CurrentIssue(gameCode, issueNumber, phase, ballCount, numbers, bettingEndsAt, drawEndsAt);
    }

    private void audit(long operator, CurrentIssue issue, int ballNumber, Integer previous, Integer next) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'PUT', ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, PRESET_PERMISSION,
                "/api/admin/betting-play-stats/current/balls/" + ballNumber,
                issue.gameCode() + ":" + issue.issueNumber(),
                "ball=" + ballNumber + ",before=" + value(previous) + ",after=" + value(next));
    }

    private void require(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("BETTING_PLAY_STATS_FORBIDDEN", "没有当前期玩法统计或开奖结果预设权限");
        }
    }

    private static String normalizeGameCode(String gameCode) {
        String normalized = gameCode == null || gameCode.isBlank()
                ? GameDataRepository.DEFAULT_GAME_CODE : gameCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[A-Z0-9_-]+")) {
            throw BusinessException.badRequest("BETTING_PLAY_STATS_GAME_INVALID", "彩种编码格式不正确");
        }
        return normalized;
    }

    private static String requiredIssueNumber(String issueNumber) {
        if (issueNumber == null || issueNumber.isBlank() || issueNumber.trim().length() > 64) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_ISSUE_INVALID", "期号不能为空且不能超过 64 个字符");
        }
        return issueNumber.trim();
    }

    private static List<Integer> readNumbers(java.sql.ResultSet rs, int ballCount) throws java.sql.SQLException {
        List<Integer> numbers = new ArrayList<>(ballCount);
        for (int index = 1; index <= ballCount; index++) {
            numbers.add(rs.getObject("number_" + index, Integer.class));
        }
        return java.util.Collections.unmodifiableList(numbers);
    }

    private static String playName(String playType) {
        try {
            return switch (PlayType.valueOf(playType)) {
                case FAN -> "番";
                case ANGLE -> "角";
                case CAR -> "车";
                case STRICT -> "严";
                case ADD -> "加";
                case POSITIVE -> "正";
                case TONG -> "通";
                case NONE -> "无";
                case ODD_EVEN -> "单双";
                case BIG_SMALL -> "大小";
                case SPECIAL -> "特";
            };
        } catch (IllegalArgumentException | NullPointerException exception) {
            return playType;
        }
    }

    private static int playOrder(String playType) {
        try {
            return PlayType.valueOf(playType).ordinal();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static String value(Integer number) {
        return number == null ? "RANDOM" : number.toString();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record CurrentStats(String gameCode, String gameName, String issueNumber, String phase, int ballCount,
                               Instant bettingEndsAt, Instant drawEndsAt, Instant serverNow,
                               List<BallStats> balls) {
    }

    public record BallStats(int ballNumber, Integer presetNumber, long orderCount, BigDecimal totalStake,
                            List<PlayStats> plays) {
    }

    public record PlayStats(String playType, String playName, long orderCount, BigDecimal totalStake) {
    }

    public record PresetSummary(String gameCode, String issueNumber, String phase, int ballNumber,
                                Integer presetNumber, Instant drawEndsAt, Instant serverNow) {
    }

    private record GameInfo(String displayName, int nextIssueBallCount) {
    }

    private record PlayRow(int ballNumber, String playType, long orderCount, BigDecimal totalStake) {
    }

    private record CurrentIssue(String gameCode, String issueNumber, String phase, int ballCount,
                                List<Integer> numbers, Instant bettingEndsAt, Instant drawEndsAt) {
    }
}
