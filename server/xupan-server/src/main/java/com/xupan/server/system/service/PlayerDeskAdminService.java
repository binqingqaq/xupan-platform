package com.xupan.server.system.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletStatistics;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.system.repository.PlayerDeskRepository;
import com.xupan.server.playerauth.domain.PlayerAccessLink;
import com.xupan.server.playerauth.repository.PlayerAccessLinkRepository;
import com.xupan.server.playerauth.service.PlayerLinkAuthenticationService;
import com.xupan.server.system.domain.TestPlayerBehaviorMode;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class PlayerDeskAdminService {
    private final PlayerDeskRepository repository;
    private final PermissionService permissionService;
    private final UserAdminService userAdminService;
    private final TestPlayerAdminService testPlayerAdminService;
    private final VirtualWalletService walletService;
    private final OperationAuditRepository auditRepository;
    private final TestPlayerBehaviorService behaviorService;
    private final ChatMessageService chatMessageService;
    private final JdbcTemplate jdbc;
    private final GameDataRepository gameDataRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PlayerAccessLinkRepository accessLinkRepository;
    private final PlayerLinkAuthenticationService playerLinkAuthenticationService;

    public PlayerDeskAdminService(PlayerDeskRepository repository, PermissionService permissionService,
                                  UserAdminService userAdminService, TestPlayerAdminService testPlayerAdminService,
                                  VirtualWalletService walletService, OperationAuditRepository auditRepository,
                                  TestPlayerBehaviorService behaviorService, ChatMessageService chatMessageService,
                                  JdbcTemplate jdbc, GameDataRepository gameDataRepository,
                                  UserRepository userRepository, SessionRepository sessionRepository,
                                  PlayerAccessLinkRepository accessLinkRepository,
                                  PlayerLinkAuthenticationService playerLinkAuthenticationService) {
        this.repository = repository;
        this.permissionService = permissionService;
        this.userAdminService = userAdminService;
        this.testPlayerAdminService = testPlayerAdminService;
        this.walletService = walletService;
        this.auditRepository = auditRepository;
        this.behaviorService = behaviorService;
        this.chatMessageService = chatMessageService;
        this.jdbc = jdbc;
        this.gameDataRepository = gameDataRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.accessLinkRepository = accessLinkRepository;
        this.playerLinkAuthenticationService = playerLinkAuthenticationService;
    }

    @Transactional(readOnly = true)
    public Page page(String kind, String status, String keyword, int page, int pageSize, long operator) {
        return page(kind, status, keyword, page, pageSize, false, operator);
    }

    @Transactional(readOnly = true)
    public Page page(String kind, String status, String keyword, int page, int pageSize,
                     boolean includeDeleted, long operator) {
        requireAdmin(operator);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw BusinessException.badRequest("PLAYER_QUERY_INVALID", "分页参数无效");
        validateDeletedQuery(status, includeDeleted);
        return new Page(repository.findPage(normalize(kind), normalize(status), normalize(keyword), page, pageSize, includeDeleted), page, pageSize,
                repository.count(normalize(kind), normalize(status), normalize(keyword), includeDeleted));
    }

    @Transactional(readOnly = true)
    public PlayerDeskRepository.Summary summary(String kind, String status, String keyword, long operator) {
        return summary(kind, status, keyword, false, operator);
    }

    @Transactional(readOnly = true)
    public PlayerDeskRepository.Summary summary(String kind, String status, String keyword,
                                                boolean includeDeleted, long operator) {
        requireAdmin(operator);
        validateDeletedQuery(status, includeDeleted);
        return repository.summary(normalize(kind), normalize(status), normalize(keyword), includeDeleted);
    }

    @Transactional(readOnly = true)
    public Detail detail(long userId, long operator) {
        return detail(userId, false, operator);
    }

    @Transactional(readOnly = true)
    public Detail detail(long userId, boolean includeDeleted, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, includeDeleted);
        VirtualWallet wallet = walletService.getForAdminHistory(userId);
        WalletStatistics statistics = walletService.statisticsForAdminHistory(userId);
        List<WalletLedgerEntry> ledger = walletService.ledgerForAdminHistory(userId, 20);
        PlayerAccessLink link = accessLinkRepository.findLatestByUserId(userId).orElse(null);
        LinkStatus linkStatus = link == null ? null : new LinkStatus(link.id(), link.scope(), link.expiresAt(), link.revokedAt(), link.lastUsedAt(),
                link.revokedAt() == null && link.expiresAt().isAfter(Instant.now()), accessLinkRepository.findConfiguredDays(userId, 7));
        PlayerDeskRepository.Behavior behavior = repository.findBehavior(userId).orElse(null);
        return new Detail(player, linkStatus,
                behavior == null ? null : new BehaviorConfig(behavior, repository.findPlayTypes(behavior.id())),
                statistics, ledger, repository.actions(userId, 20), gameDataRepository.findBetsByAccountId(wallet.accountId(), null, 20));
    }

    @Transactional
    public long createNormal(String displayName, long operator) {
        requireAdmin(operator);
        long id = userAdminService.createPlayerLinkUser(displayName, operator);
        playerLinkAuthenticationService.issue(id, operator);
        audit(operator, "POST", "/api/admin/player-desk/players/normal", Long.toString(id), "playerKind=NORMAL");
        return id;
    }

    @Transactional
    public long createBot(String userCode, String displayName, String avatarKey, long operator) {
        requireAdmin(operator);
        TestPlayerAdminService.TestPlayerAdminView view = testPlayerAdminService.create(userCode, displayName, avatarKey, operator);
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE sys_user_id=?", view.player().userId());
        jdbc.update("UPDATE sys_user SET auth_mode='BOT_SERVICE' WHERE id=?", view.player().userId());
        playerLinkAuthenticationService.issue(view.player().userId(), operator);
        repository.ensureBehavior(view.player().id());
        audit(operator, "POST", "/api/admin/player-desk/players/bot", Long.toString(view.player().userId()), "playerKind=BOT");
        return view.player().userId();
    }

    @Transactional
    public Detail status(long userId, String status, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId);
        if ("BOT".equals(player.playerKind())) {
            testPlayerAdminService.changeStatus(player.userCode(), status, operator);
        } else {
            userAdminService.changeStatus(userId, status, operator);
        }
        return detail(userId, operator);
    }

    @Transactional
    public Detail delete(long userId, long operator) {
        requireAdmin(operator);
        if (userId == operator) {
            throw BusinessException.conflict("PLAYER_SELF_OPERATION_FORBIDDEN", "不能删除当前登录管理员");
        }
        PlayerDeskRepository.PlayerRow player = row(userId, true);
        if (permissionService.hasPermission(userId, "USER_MANAGE")) {
            throw BusinessException.forbidden("PLAYER_DELETE_FORBIDDEN", "不能通过玩家工作台删除管理员");
        }
        if ("DELETED".equals(player.userStatus()) && "DELETED".equals(player.accountStatus())) {
            return detail(userId, true, operator);
        }
        userRepository.softDelete(userId);
        repository.softDelete(userId);
        repository.disableBehavior(userId);
        Instant now = Instant.now();
        sessionRepository.revokeAllActiveByUserId(userId, now);
        accessLinkRepository.revokeAllByUserId(userId, now);
        audit(operator, "DELETE", "/api/admin/player-desk/players/" + userId,
                player.internalCode(), "userId=" + userId + ",internalCode=" + player.internalCode()
                        + ",memberCode=" + player.memberCode() + ",displayName=" + player.displayName() + ",status=DELETED");
        return detail(userId, true, operator);
    }

    @Transactional
    public Detail grant(long userId, BigDecimal amount, String reason, String key, long operator) {
        requireAdmin(operator);
        walletService.grant(operator, userId, amount, reason, key);
        return detail(userId, operator);
    }

    @Transactional
    public Detail adjust(long userId, BigDecimal amount, String reason, String key, long operator) {
        requireAdmin(operator);
        walletService.adjust(operator, userId, amount, reason, key);
        return detail(userId, operator);
    }

    @Transactional
    public Detail updateNickname(long userId, String displayName, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId);
        String nextName = displayName == null ? "" : displayName.trim();
        if (nextName.isBlank() || nextName.length() > 128) {
            throw BusinessException.badRequest("PLAYER_NICKNAME_INVALID", "昵称不能为空且不能超过128个字符");
        }
        if (nextName.equals(player.displayName())) return detail(userId, operator);
        long changesToday = jdbc.queryForObject("SELECT COUNT(*) FROM player_name_change_record WHERE user_id=? AND changed_at >= CURRENT_DATE", Long.class, userId);
        if (changesToday >= 3) {
            throw BusinessException.badRequest("PLAYER_NICKNAME_DAILY_LIMIT", "今日换名次数已用完");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE (display_name=? OR username=?) AND id<>? AND status<>'DELETED'", Integer.class, nextName, nextName, userId) > 0) {
            throw BusinessException.conflict("PLAYER_NICKNAME_EXISTS", "昵称已存在");
        }
        jdbc.update("UPDATE sys_user SET display_name=?, username=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", nextName, nextName, userId);
        jdbc.update("UPDATE demo_user_account SET display_name=?, updated_at=CURRENT_TIMESTAMP WHERE sys_user_id=?", nextName, userId);
        jdbc.update("INSERT INTO player_name_change_record(user_id, old_display_name, new_display_name) VALUES (?, ?, ?)",
                userId, player.displayName(), nextName);
        audit(operator, "PUT", "/api/admin/player-desk/players/" + userId + "/nickname", Long.toString(userId),
                "oldName=" + player.displayName() + ",newName=" + nextName);
        return detail(userId, operator);
    }

    @Transactional(readOnly = true)
    public NameHistory nameHistory(long userId, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, true);
        long changesToday = jdbc.queryForObject("SELECT COUNT(*) FROM player_name_change_record WHERE user_id=? AND changed_at >= CURRENT_DATE", Long.class, userId);
        List<NameChange> records = jdbc.query("SELECT id, old_display_name, new_display_name, changed_at FROM player_name_change_record WHERE user_id=? ORDER BY id DESC LIMIT 30",
                (rs, n) -> new NameChange(rs.getLong("id"), rs.getString("old_display_name"), rs.getString("new_display_name"), rs.getTimestamp("changed_at").toInstant()), userId);
        return new NameHistory(player.displayName(), Math.max(0, 3 - changesToday), records);
    }

    @Transactional(readOnly = true)
    public BehaviorConfig behavior(long userId, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId);
        if (!"BOT".equals(player.playerKind())) return null;
        PlayerDeskRepository.Behavior behavior = repository.findBehavior(userId)
                .orElseGet(() -> repository.ensureBehavior(player.accountId()));
        return new BehaviorConfig(behavior, repository.findPlayTypes(behavior.id()));
    }

    @Transactional
    public BehaviorConfig updateBehavior(long userId, String mode, int bets, String stakeRangeCode,
                                         String stakeRoundTen, int activityPercent, boolean playRandom,
                                         List<String> playTypes, int topupProbabilityPercent,
                                         BigDecimal topupMin, BigDecimal topupMax, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId);
        if (!"BOT".equals(player.playerKind())) throw BusinessException.badRequest("PLAYER_BEHAVIOR_NOT_APPLICABLE", "普通玩家不支持托行为配置");
        BigDecimal[] bounds = stakeBounds(stakeRangeCode);
        List<String> selected = playTypes == null ? List.of() : playTypes.stream().distinct().toList();
        if (!isMode(mode) || bets < 0 || bets > 20 || bounds == null
                || !isRoundTen(stakeRoundTen) || activityPercent < 0 || activityPercent > 100
                || selected.isEmpty() && !playRandom
                || selected.stream().anyMatch(playType -> !isPlayType(playType))
                || topupProbabilityPercent < 0 || topupProbabilityPercent > 100
                || topupMin == null || topupMax == null || topupMin.signum() <= 0
                || topupMax.compareTo(topupMin) < 0) {
            throw BusinessException.badRequest("PLAYER_BEHAVIOR_INVALID", "托行为配置无效");
        }
        PlayerDeskRepository.Behavior result = repository.updateBehavior(userId, mode, bets, bounds[0], bounds[1],
                false, 0, stakeRangeCode, stakeRoundTen, activityPercent, playRandom,
                topupProbabilityPercent, topupMin, topupMax);
        repository.replacePlayTypes(result.id(), selected);
        audit(operator, "PUT", "/api/admin/player-desk/players/" + userId + "/behavior", Long.toString(userId), "mode=" + mode);
        return new BehaviorConfig(result, repository.findPlayTypes(result.id()));
    }

    @Transactional
    public List<PlayerDeskRepository.ActionRow> runNow(long userId, long operator) {
        requireAdmin(operator);
        List<PlayerDeskRepository.ActionRow> result = behaviorService.dispatch(userId, true);
        audit(operator, "POST", "/api/admin/player-desk/players/" + userId + "/behavior/run-now", Long.toString(userId), "actions=" + result.size());
        return result;
    }

    public List<PlayerDeskRepository.ActionRow> actions(long userId, int limit, long operator) {
        requireAdmin(operator);
        row(userId);
        return repository.actions(userId, limit);
    }

    @Transactional
    public ChatMessageService.ChatMessageSendOutcome sendMessage(long userId, String content, String clientMessageId, long operator) {
        requireAdmin(operator);
        return behaviorService.sendManualMessage(userId, clientMessageId, content);
    }

    private PlayerDeskRepository.PlayerRow row(long id) {
        return row(id, false);
    }

    private PlayerDeskRepository.PlayerRow row(long id, boolean includeDeleted) {
        return repository.findByUserId(id, includeDeleted).orElseThrow(() -> BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在"));
    }

    private void requireAdmin(long userId) {
        if (userId <= 0 || !permissionService.hasPermission(userId, "USER_MANAGE")) throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "没有用户管理权限");
    }

    private void audit(long operator, String method, String path, String resource, String summary) {
        auditRepository.record(operator, "USER_MANAGE", method, path, resource, "SUCCESS", null, summary, null, Instant.now());
    }

    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static void validateDeletedQuery(String status, boolean includeDeleted) {
        if ("DELETED".equalsIgnoreCase(normalize(status)) && !includeDeleted) {
            throw BusinessException.badRequest("PLAYER_QUERY_INVALID", "查询已删除玩家必须显式开启历史查询");
        }
    }

    private static boolean isMode(String mode) {
        try {
            TestPlayerBehaviorMode.valueOf(mode);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 下注范围档位；RANDOM 表示在全局最小到最大之间随机。 */
    private static BigDecimal[] stakeBounds(String code) {
        if (code == null) return null;
        return switch (code) {
            case "RANDOM" -> new BigDecimal[]{new BigDecimal("30"), new BigDecimal("30000")};
            case "30-300" -> new BigDecimal[]{new BigDecimal("30"), new BigDecimal("300")};
            case "300-1000" -> new BigDecimal[]{new BigDecimal("300"), new BigDecimal("1000")};
            case "1000-3000" -> new BigDecimal[]{new BigDecimal("1000"), new BigDecimal("3000")};
            case "3000-10000" -> new BigDecimal[]{new BigDecimal("3000"), new BigDecimal("10000")};
            case "10000-30000" -> new BigDecimal[]{new BigDecimal("10000"), new BigDecimal("30000")};
            default -> null;
        };
    }

    private static boolean isRoundTen(String value) {
        return "RANDOM".equals(value) || "OFF".equals(value) || "ON".equals(value);
    }

    private static boolean isPlayType(String value) {
        try {
            com.xupan.server.game.domain.PlayType.valueOf(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public record Page(List<PlayerDeskRepository.PlayerRow> items, int page, int pageSize, long total) {}
    public record BehaviorConfig(PlayerDeskRepository.Behavior behavior, List<String> playTypes) {}
    public record Detail(PlayerDeskRepository.PlayerRow player, LinkStatus linkStatus, BehaviorConfig behavior,
                         WalletStatistics walletStatistics, List<WalletLedgerEntry> ledger,
                         List<PlayerDeskRepository.ActionRow> actions, List<GameDataRepository.BetRecord> bets) {}
    public record LinkStatus(long linkId, String scope, Instant expiresAt, Instant revokedAt,
                             Instant lastUsedAt, boolean active, int configuredDays) {}
    public record NameHistory(String currentName, long remainingToday, List<NameChange> records) {}
    public record NameChange(long id, String oldName, String newName, Instant changedAt) {}
}
