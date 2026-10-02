package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GameSettingsService {
    private static final String READ_PERMISSION = "GAME_SETTINGS_READ";
    private static final String WRITE_PERMISSION = "GAME_SETTINGS_WRITE";
    private static final long PRIMARY_GAME_ID = 1L;

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;

    public GameSettingsService(JdbcTemplate jdbc, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<GameRow> list(long operator) {
        requirePermission(operator, READ_PERMISSION);
        return findGames();
    }

    @Transactional(readOnly = true)
    public GameRow get(long id, long operator) {
        requirePermission(operator, READ_PERMISSION);
        return requireGame(id);
    }

    @Transactional(readOnly = true)
    public List<GameCatalogItem> activeCatalog() {
        return jdbc.query("""
                SELECT game_code, display_name, sort_order
                  FROM game_definition
                 WHERE status = 'ACTIVE' AND deleted_at IS NULL
                 ORDER BY sort_order, id
                """, (rs, rowNum) -> new GameCatalogItem(rs.getString("game_code"),
                rs.getString("display_name"), rs.getInt("sort_order")));
    }

    @Transactional(readOnly = true)
    public boolean isBettingEnabled() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_definition
                 WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, Long.class, PRIMARY_GAME_ID) > 0;
    }

    @Transactional
    public GameRow create(GameInput input, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        ValidatedGame game = validate(input);
        if (existsByCode(game.gameCode(), null)) {
            throw BusinessException.conflict("GAME_SETTINGS_CODE_EXISTS", "彩种键名已存在");
        }
        long id = nextGameId();
        jdbc.update("""
                INSERT INTO game_definition
                    (id, game_code, display_name, ball_indexes, draw_source_url, sort_order,
                     algorithm, play_prefix, switch_enabled, special_enabled, special_model,
                     keyboard_enabled, status, version, created_by, updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, game.gameCode(), game.displayName(), game.ballIndexes(), game.drawSourceUrl(),
                game.sortOrder(), game.algorithm(), game.playPrefix(), game.switchEnabled(),
                game.specialEnabled(), game.specialModel(), game.keyboardEnabled(), game.status(),
                operator, operator);
        applyRouteOdds(id, input);
        audit(operator, "POST", "/api/admin/game-settings", id, "create gameCode=" + game.gameCode());
        return requireGame(id);
    }

    @Transactional
    public GameRow update(long id, GameInput input, long expectedVersion, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        GameRow current = requireGame(id);
        if (current.version() != expectedVersion) {
            throw BusinessException.conflict("GAME_SETTINGS_CONCURRENT_UPDATE", "游戏设置已被其他操作修改，请刷新后重试");
        }
        ValidatedGame game = validate(input);
        if (!current.gameCode().equals(game.gameCode())) {
            throw BusinessException.badRequest("GAME_SETTINGS_CODE_IMMUTABLE", "彩种键名创建后不能修改");
        }
        if (existsByCode(game.gameCode(), id)) {
            throw BusinessException.conflict("GAME_SETTINGS_CODE_EXISTS", "彩种键名已存在");
        }
        int updated = jdbc.update("""
                UPDATE game_definition
                   SET game_code = ?, display_name = ?, ball_indexes = ?, draw_source_url = ?,
                       sort_order = ?, algorithm = ?, play_prefix = ?, switch_enabled = ?,
                       special_enabled = ?, special_model = ?, keyboard_enabled = ?, status = ?,
                       version = version + 1, updated_by = ?, updated_at = ?
                 WHERE id = ? AND deleted_at IS NULL AND version = ?
                """, game.gameCode(), game.displayName(), game.ballIndexes(), game.drawSourceUrl(),
                game.sortOrder(), game.algorithm(), game.playPrefix(), game.switchEnabled(),
                game.specialEnabled(), game.specialModel(), game.keyboardEnabled(), game.status(),
                operator, Timestamp.from(Instant.now()), id, expectedVersion);
        if (updated != 1) {
            throw BusinessException.conflict("GAME_SETTINGS_CONCURRENT_UPDATE", "游戏设置已被其他操作修改，请刷新后重试");
        }
        applyRouteOdds(id, input);
        audit(operator, "PUT", "/api/admin/game-settings/" + id, id, "update gameCode=" + game.gameCode());
        return requireGame(id);
    }

    @Transactional
    public void delete(long id, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        if (id == PRIMARY_GAME_ID) {
            throw BusinessException.badRequest("GAME_PRIMARY_DELETE_FORBIDDEN", "主彩种不能删除");
        }
        requireGame(id);
        int updated = jdbc.update("""
                UPDATE game_definition
                   SET status = 'DISABLED', deleted_at = ?, version = version + 1,
                       updated_by = ?, updated_at = ?
                 WHERE id = ? AND deleted_at IS NULL
                """, Timestamp.from(Instant.now()), operator, Timestamp.from(Instant.now()), id);
        if (updated != 1) {
            throw BusinessException.notFound("GAME_SETTINGS_NOT_FOUND", "游戏设置不存在");
        }
        audit(operator, "DELETE", "/api/admin/game-settings/" + id, id, "soft-delete game");
    }

    private List<GameRow> findGames() {
        return jdbc.query("""
                SELECT id, game_code, display_name, ball_indexes, draw_source_url, sort_order,
                       algorithm, play_prefix, switch_enabled, special_enabled, special_model,
                       keyboard_enabled, status, version, updated_at
                  FROM game_definition
                 WHERE deleted_at IS NULL
                 ORDER BY sort_order, id
                """, (rs, rowNum) -> mapGame(rs)).stream()
                .map(row -> withOdds(row, loadOdds(row.id())))
                .toList();
    }

    private GameRow requireGame(long id) {
        List<GameRow> rows = jdbc.query("""
                SELECT id, game_code, display_name, ball_indexes, draw_source_url, sort_order,
                       algorithm, play_prefix, switch_enabled, special_enabled, special_model,
                       keyboard_enabled, status, version, updated_at
                  FROM game_definition
                 WHERE id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> mapGame(rs), id);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("GAME_SETTINGS_NOT_FOUND", "游戏设置不存在");
        }
        GameRow row = rows.get(0);
        return withOdds(row, loadOdds(id));
    }

    private Map<String, RouteOdds> loadOdds(long gameId) {
        return jdbc.query("""
                SELECT route_code, special_odds, big_small_odds
                  FROM game_route_odds
                 WHERE game_id = ?
                """, (rs, rowNum) -> new RouteOdds(rs.getString("route_code"),
                rs.getBigDecimal("special_odds"), rs.getBigDecimal("big_small_odds")), gameId)
                .stream().collect(Collectors.toMap(RouteOdds::routeCode, odds -> odds));
    }

    private static GameRow mapGame(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GameRow(rs.getLong("id"), rs.getString("game_code"), rs.getString("display_name"),
                rs.getString("ball_indexes"), rs.getString("draw_source_url"), rs.getInt("sort_order"),
                rs.getString("algorithm"), rs.getString("play_prefix"), rs.getBoolean("switch_enabled"),
                rs.getBoolean("special_enabled"), rs.getString("special_model"),
                rs.getBoolean("keyboard_enabled"), rs.getString("status"), rs.getLong("version"),
                instant(rs.getTimestamp("updated_at")), null, null, null, null, null, null, null, null);
    }

    private static GameRow withOdds(GameRow row, Map<String, RouteOdds> odds) {
        return new GameRow(row.id(), row.gameCode(), row.displayName(), row.ballIndexes(), row.drawSourceUrl(),
                row.sortOrder(), row.algorithm(), row.playPrefix(), row.switchEnabled(), row.specialEnabled(),
                row.specialModel(), row.keyboardEnabled(), row.status(), row.version(), row.updatedAt(),
                value(odds, "A", true), value(odds, "A", false), value(odds, "B", true),
                value(odds, "B", false), value(odds, "C", true), value(odds, "C", false),
                value(odds, "D", true), value(odds, "D", false));
    }

    private static BigDecimal value(Map<String, RouteOdds> odds, String route, boolean special) {
        RouteOdds row = odds.get(route);
        if (row == null) return BigDecimal.ZERO.setScale(2);
        return special ? row.specialOdds() : row.bigSmallOdds();
    }

    private void applyRouteOdds(long gameId, GameInput input) {
        applyRouteOdds(gameId, "A", input.oddsAte(), input.oddsAdx());
        applyRouteOdds(gameId, "B", input.oddsBte(), input.oddsBdx());
        applyRouteOdds(gameId, "C", input.oddsCte(), input.oddsCdx());
        applyRouteOdds(gameId, "D", input.oddsDte(), input.oddsDdx());
    }

    private void applyRouteOdds(long gameId, String route, BigDecimal special, BigDecimal bigSmall) {
        BigDecimal specialOdds = normalizeOdds(special, "特码赔率");
        BigDecimal bigSmallOdds = normalizeOdds(bigSmall, "大小单双赔率");
        int updated = jdbc.update("""
                UPDATE game_route_odds
                   SET special_odds = ?, big_small_odds = ?, updated_at = ?
                 WHERE game_id = ? AND route_code = ?
                """, specialOdds, bigSmallOdds, Timestamp.from(Instant.now()), gameId, route);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO game_route_odds
                        (game_id, route_code, special_odds, big_small_odds, created_at, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, gameId, route, specialOdds, bigSmallOdds);
        }
    }

    private long nextGameId() {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM game_definition", Long.class);
        if (id == null) throw new IllegalStateException("无法生成彩种主键");
        return id;
    }

    private boolean existsByCode(String code, Long excludeId) {
        if (excludeId == null) {
            return count("SELECT COUNT(*) FROM game_definition WHERE game_code = ? AND deleted_at IS NULL", code) > 0;
        }
        return count("SELECT COUNT(*) FROM game_definition WHERE game_code = ? AND deleted_at IS NULL AND id <> ?",
                code, excludeId) > 0;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private ValidatedGame validate(GameInput input) {
        String name = required(input.displayName(), "彩种名称不能为空", 128);
        String code = required(input.gameCode(), "彩种键名不能为空", 32).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]+")) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", "彩种键名只能包含字母、数字、下划线或短横线");
        }
        String ballIndexes = normalizeBallIndexes(input.ballIndexes());
        String drawUrl = optionalUrl(input.drawSourceUrl());
        String algorithm = enumValue(input.algorithm(), Set.of("SUM", "CONCAT"), "算法无效");
        String specialModel = enumValue(input.specialModel(), Set.of("MODEL_ONE", "MODEL_TWO"), "特模型无效");
        String status = enumValue(input.status(), Set.of("ACTIVE", "DISABLED"), "状态无效");
        if (input.sortOrder() < 0 || input.sortOrder() > 100000) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", "排序必须是 0 到 100000");
        }
        String prefix = optional(input.playPrefix(), 32, "玩法前缀不能超过 32 个字符");
        return new ValidatedGame(code, name, ballIndexes, drawUrl, input.sortOrder(), algorithm, prefix,
                input.switchEnabled(), input.specialEnabled(), specialModel, input.keyboardEnabled(), status);
    }

    private void requirePermission(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("GAME_SETTINGS_FORBIDDEN", "没有游戏设置权限");
        }
    }

    private void audit(long operator, String method, String path, long resourceId, String summary) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, WRITE_PERMISSION, method, path, Long.toString(resourceId), summary);
    }

    private static String normalizeBallIndexes(String value) {
        String raw = required(value, "开奖球号不能为空", 255);
        List<Integer> values;
        try {
            values = Arrays.stream(raw.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                    .map(Integer::valueOf).toList();
        } catch (NumberFormatException exception) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", "开奖球号必须是逗号分隔的数字");
        }
        Set<Integer> unique = new LinkedHashSet<>(values);
        if (values.isEmpty() || unique.size() != values.size()
                || values.stream().anyMatch(item -> item < 1 || item > 8)) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", "开奖球号只能是 1-8 且不能重复");
        }
        return values.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private static BigDecimal normalizeOdds(BigDecimal value, String label) {
        if (value == null) return BigDecimal.ZERO.setScale(2);
        if (value.signum() < 0 || value.compareTo(new BigDecimal("10000")) > 0) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", label + "必须在 0 到 10000 之间");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String optionalUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String url = required(value, "采集链接无效", 512);
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("GAME_SETTINGS_URL_INVALID", "采集链接必须是有效的 http/https 地址或留空");
        }
    }

    private static String enumValue(String value, Set<String> allowed, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", message);
        }
        return normalized;
    }

    private static String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", message);
        }
        return value.trim();
    }

    private static String optional(String value, int maxLength, String message) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw BusinessException.badRequest("GAME_SETTINGS_INVALID", message);
        }
        return trimmed;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record ValidatedGame(String gameCode, String displayName, String ballIndexes, String drawSourceUrl,
                                 int sortOrder, String algorithm, String playPrefix, boolean switchEnabled,
                                 boolean specialEnabled, String specialModel, boolean keyboardEnabled,
                                 String status) {}

    private record RouteOdds(String routeCode, BigDecimal specialOdds, BigDecimal bigSmallOdds) {}

    public record GameCatalogItem(String gameCode, String displayName, int sortOrder) {}

    public record GameInput(String gameCode, String displayName, String ballIndexes, String drawSourceUrl,
                            int sortOrder, String algorithm, String playPrefix, boolean switchEnabled,
                            boolean specialEnabled, String specialModel, boolean keyboardEnabled,
                            String status, BigDecimal oddsAte, BigDecimal oddsAdx,
                            BigDecimal oddsBte, BigDecimal oddsBdx, BigDecimal oddsCte,
                            BigDecimal oddsCdx, BigDecimal oddsDte, BigDecimal oddsDdx) {}

    public record GameRow(long id, String gameCode, String displayName, String ballIndexes,
                          String drawSourceUrl, int sortOrder, String algorithm, String playPrefix,
                          boolean switchEnabled, boolean specialEnabled, String specialModel,
                          boolean keyboardEnabled, String status, long version, Instant updatedAt,
                          BigDecimal oddsAte, BigDecimal oddsAdx, BigDecimal oddsBte,
                          BigDecimal oddsBdx, BigDecimal oddsCte, BigDecimal oddsCdx,
                          BigDecimal oddsDte, BigDecimal oddsDdx) {}
}
