package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MacauFourMinuteDrawService {

    static final String READ_PERMISSION = "MACAU_FOUR_MINUTE_DRAW_READ";
    static final String PRESET_PERMISSION = "MACAU_FOUR_MINUTE_PRESET_MANAGE";
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalTime DAILY_DRAW_TIME = LocalTime.of(20, 30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;

    public MacauFourMinuteDrawService(JdbcTemplate jdbc, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public Page list(int page, int pageSize, long operator) {
        require(operator, READ_PERMISSION, "MACAU_FOUR_MINUTE_DRAW_FORBIDDEN", "没有澳门四分彩开奖查看权限");
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("MACAU_FOUR_MINUTE_QUERY_INVALID", "分页参数无效");
        }

        DrawHead pending = findPending();
        long historyCount = countDrawn();
        long offset = page == 1 ? 0 : (long) (page - 1) * pageSize - (pending == null ? 0 : 1);
        int historyLimit = page == 1 && pending != null ? pageSize - 1 : pageSize;
        List<DrawView> items = new ArrayList<>();
        if (page == 1 && pending != null) {
            items.add(toView(pending));
        }
        if (historyLimit > 0 && offset >= 0) {
            items.addAll(findHistory(historyLimit, offset));
        }
        long total = historyCount + (pending == null ? 0 : 1);
        return new Page(List.copyOf(items), page, pageSize, total);
    }

    @Transactional
    public DrawView savePreset(long issueNumber, List<Integer> numbers, long operator) {
        require(operator, PRESET_PERMISSION, "MACAU_FOUR_MINUTE_PRESET_FORBIDDEN", "没有澳门四分彩预设管理权限");
        validatePreset(numbers);

        DrawHead draw = jdbc.query("""
                SELECT id, issue_number, scheduled_at, drawn_at, status
                  FROM macau_four_minute_draw
                 WHERE issue_number = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new DrawHead(rs.getLong("id"), rs.getLong("issue_number"),
                rs.getTimestamp("scheduled_at").toInstant(), timestampInstant(rs.getTimestamp("drawn_at")),
                rs.getString("status")), issueNumber).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound(
                        "MACAU_FOUR_MINUTE_DRAW_NOT_FOUND", "未找到对应澳门四分彩期次"));

        DrawHead currentPending = findPending();
        if (!"PENDING".equals(draw.status()) || currentPending == null
                || currentPending.id() != draw.id() || !draw.scheduledAt().isAfter(Instant.now())) {
            throw BusinessException.conflict("MACAU_FOUR_MINUTE_PRESET_CLOSED", "该期已开奖或已到开奖时间，不能修改预设");
        }

        List<Integer> before = findNumbers(draw.id());
        if (before.size() != 7) {
            throw new IllegalStateException("澳门四分彩待开奖期必须包含七个球位");
        }
        for (int position = 1; position <= 7; position++) {
            int changed = jdbc.update("""
                    UPDATE macau_four_minute_draw_ball
                       SET number_value = ?
                     WHERE draw_id = ? AND ball_position = ?
                    """, numbers.get(position - 1), draw.id(), position);
            if (changed != 1) {
                throw new IllegalStateException("澳门四分彩球位记录不完整，预设事务回滚");
            }
        }
        audit(operator, issueNumber, before, numbers);
        return new DrawView(Long.toString(issueNumber), Collections.unmodifiableList(new ArrayList<>(numbers)),
                draw.scheduledAt(), null, "PENDING");
    }

    @Transactional(readOnly = true)
    public DisplayResponse getDisplay() {
        DrawHead latest = jdbc.query("""
                SELECT id, issue_number, scheduled_at, drawn_at, status
                  FROM macau_four_minute_draw
                 WHERE status = 'DRAWN'
                 ORDER BY issue_number DESC
                 LIMIT 1
                """, (rs, rowNum) -> readHead(rs)).stream().findFirst().orElse(null);
        DrawHead pending = findPending();
        LatestDraw latestDraw = latest == null ? null : new LatestDraw(
                Long.toString(latest.issueNumber()), findNumbers(latest.id()), latest.drawnAt());
        return new DisplayResponse(Instant.now(), latestDraw,
                pending == null ? null : new NextDraw(Long.toString(pending.issueNumber()),
                        pending.scheduledAt(), "PENDING"));
    }

    @Transactional
    public void ensureInitialDraw() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM macau_four_minute_draw", Long.class);
        if (count != null && count > 0) {
            return;
        }
        Instant scheduledAt = nextDailyDraw(Instant.now());
        insertPending(100, scheduledAt);
    }

    @Transactional
    public boolean processOneDueDraw() {
        Instant now = Instant.now();
        DrawHead due = jdbc.query("""
                SELECT id, issue_number, scheduled_at, drawn_at, status
                  FROM macau_four_minute_draw
                 WHERE status = 'PENDING' AND scheduled_at <= ?
                 ORDER BY scheduled_at, issue_number
                 LIMIT 1
                 FOR UPDATE
                """, (rs, rowNum) -> readHead(rs), Timestamp.from(now)).stream().findFirst().orElse(null);
        if (due == null) {
            return false;
        }

        List<Integer> preset = findNumbers(due.id());
        if (preset.size() != 7) {
            throw new IllegalStateException("澳门四分彩待开奖期必须包含七个球位");
        }
        List<Integer> result = completeNumbers(preset, RANDOM);
        for (int position = 1; position <= 7; position++) {
            jdbc.update("""
                    UPDATE macau_four_minute_draw_ball
                       SET number_value = ?
                     WHERE draw_id = ? AND ball_position = ?
                    """, result.get(position - 1), due.id(), position);
        }
        int changed = jdbc.update("""
                UPDATE macau_four_minute_draw
                   SET status = 'DRAWN', drawn_at = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(now), due.id());
        if (changed != 1) {
            throw new IllegalStateException("澳门四分彩期次状态已变化，开奖事务回滚");
        }

        long nextIssue = Math.addExact(due.issueNumber(), 1);
        Instant nextScheduledAt = due.scheduledAt().atZone(BUSINESS_ZONE).plusDays(1).toInstant();
        insertPending(nextIssue, nextScheduledAt);
        return true;
    }

    static void validatePreset(List<Integer> numbers) {
        if (numbers == null || numbers.size() != 7) {
            throw BusinessException.badRequest("MACAU_FOUR_MINUTE_PRESET_INVALID", "必须提供七个球位");
        }
        Set<Integer> selected = new HashSet<>();
        for (Integer number : numbers) {
            if (number == null) {
                continue;
            }
            if (number < 1 || number > 40) {
                throw BusinessException.badRequest("MACAU_FOUR_MINUTE_NUMBER_INVALID", "号码必须在 1 至 40 之间");
            }
            if (!selected.add(number)) {
                throw BusinessException.badRequest("MACAU_FOUR_MINUTE_NUMBER_DUPLICATED", "同一期预设号码不能重复");
            }
        }
    }

    static List<Integer> completeNumbers(List<Integer> preset, SecureRandom random) {
        validatePreset(preset);
        List<Integer> result = new ArrayList<>(preset);
        Set<Integer> selected = new HashSet<>(preset.stream().filter(number -> number != null).toList());
        for (int position = 0; position < result.size(); position++) {
            if (result.get(position) != null) {
                continue;
            }
            List<Integer> available = new ArrayList<>();
            for (int number = 1; number <= 40; number++) {
                if (!selected.contains(number)) {
                    available.add(number);
                }
            }
            int number = available.get(random.nextInt(available.size()));
            result.set(position, number);
            selected.add(number);
        }
        return List.copyOf(result);
    }

    static Instant nextDailyDraw(Instant now) {
        ZonedDateTime localNow = now.atZone(BUSINESS_ZONE);
        ZonedDateTime today = LocalDate.now(BUSINESS_ZONE).atTime(DAILY_DRAW_TIME).atZone(BUSINESS_ZONE);
        return (today.isAfter(localNow) ? today : today.plusDays(1)).toInstant();
    }

    private DrawHead findPending() {
        return jdbc.query("""
                SELECT id, issue_number, scheduled_at, drawn_at, status
                  FROM macau_four_minute_draw
                 WHERE status = 'PENDING'
                 ORDER BY issue_number
                 LIMIT 1
                """, (rs, rowNum) -> readHead(rs)).stream().findFirst().orElse(null);
    }

    private long countDrawn() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM macau_four_minute_draw WHERE status = 'DRAWN'", Long.class);
        return count == null ? 0 : count;
    }

    private List<DrawView> findHistory(int limit, long offset) {
        return jdbc.query("""
                SELECT id, issue_number, scheduled_at, drawn_at, status
                  FROM macau_four_minute_draw
                 WHERE status = 'DRAWN'
                 ORDER BY issue_number DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> toView(readHead(rs)), limit, offset);
    }

    private DrawView toView(DrawHead head) {
        return new DrawView(Long.toString(head.issueNumber()), findNumbers(head.id()),
                head.scheduledAt(), head.drawnAt(), head.status());
    }

    private List<Integer> findNumbers(long drawId) {
        return jdbc.query("""
                SELECT ball_position, number_value
                  FROM macau_four_minute_draw_ball
                 WHERE draw_id = ?
                 ORDER BY ball_position
                """, (rs, rowNum) -> {
            Number number = (Number) rs.getObject("number_value");
            return number == null ? null : number.intValue();
        }, drawId);
    }

    private void insertPending(long issueNumber, Instant scheduledAt) {
        jdbc.update("""
                INSERT INTO macau_four_minute_draw (issue_number, scheduled_at, status)
                VALUES (?, ?, 'PENDING')
                """, issueNumber, Timestamp.from(scheduledAt));
        Long drawId = jdbc.queryForObject(
                "SELECT id FROM macau_four_minute_draw WHERE issue_number = ?", Long.class, issueNumber);
        if (drawId == null) {
            throw new IllegalStateException("澳门四分彩待开奖期创建后未能读取");
        }
        for (int position = 1; position <= 7; position++) {
            jdbc.update("""
                    INSERT INTO macau_four_minute_draw_ball (draw_id, ball_position, number_value)
                    VALUES (?, ?, NULL)
                    """, drawId, position);
        }
    }

    private void audit(long operator, long issueNumber, List<Integer> before, List<Integer> after) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'PUT', ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, PRESET_PERMISSION,
                "/api/admin/macau-four-minute/draws/" + issueNumber + "/preset",
                Long.toString(issueNumber), "before=" + before + ";after=" + after);
    }

    private void require(long operator, String permission, String code, String message) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden(code, message);
        }
    }

    private static DrawHead readHead(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DrawHead(rs.getLong("id"), rs.getLong("issue_number"),
                rs.getTimestamp("scheduled_at").toInstant(), timestampInstant(rs.getTimestamp("drawn_at")),
                rs.getString("status"));
    }

    private static Instant timestampInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Page(List<DrawView> items, int page, int pageSize, long total) {
    }

    public record DrawView(String issueNumber, List<Integer> numbers, Instant scheduledAt,
                           Instant drawnAt, String status) {
    }

    public record NextDraw(String issueNumber, Instant scheduledAt, String status) {
    }

    public record LatestDraw(String issueNumber, List<Integer> numbers, Instant drawnAt) {
    }

    public record DisplayResponse(Instant serverTime, LatestDraw latestDraw, NextDraw nextDraw) {
    }

    private record DrawHead(long id, long issueNumber, Instant scheduledAt, Instant drawnAt, String status) {
    }

}
