package com.xupan.server.system.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletStatistics;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.system.repository.PlayerDeskRepository;
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

    public PlayerDeskAdminService(PlayerDeskRepository repository, PermissionService permissionService,
                                  UserAdminService userAdminService, TestPlayerAdminService testPlayerAdminService,
                                  VirtualWalletService walletService, OperationAuditRepository auditRepository,
                                  TestPlayerBehaviorService behaviorService, ChatMessageService chatMessageService,
                                  JdbcTemplate jdbc, GameDataRepository gameDataRepository) {
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
    }

    @Transactional(readOnly = true)
    public Page page(String kind, String status, String keyword, int page, int pageSize, long operator) {
        requireAdmin(operator);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw BusinessException.badRequest("PLAYER_QUERY_INVALID", "分页参数无效");
        return new Page(repository.findPage(normalize(kind), normalize(status), normalize(keyword), page, pageSize), page, pageSize,
                repository.count(normalize(kind), normalize(status), normalize(keyword)));
    }

    @Transactional(readOnly = true)
    public PlayerDeskRepository.Summary summary(String kind, String status, String keyword, long operator) {
        requireAdmin(operator);
        return repository.summary(normalize(kind), normalize(status), normalize(keyword));
    }

    @Transactional(readOnly = true)
    public Detail detail(long userId, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId);
        VirtualWallet wallet = walletService.getForAdmin(userId);
        WalletStatistics statistics = walletService.statistics(userId);
        List<WalletLedgerEntry> ledger = walletService.ledger(userId, 20);
        return new Detail(player, repository.findBehavior(userId).orElse(null),
                statistics, ledger, repository.actions(userId, 20), gameDataRepository.findBetsByAccountId(wallet.accountId(), null, 20));
    }

    @Transactional
    public long createNormal(String username, String displayName, String password, long operator) {
        requireAdmin(operator);
        long id = userAdminService.createUser(username, displayName, password, operator);
        audit(operator, "POST", "/api/admin/player-desk/players/normal", Long.toString(id), "playerKind=NORMAL");
        return id;
    }

    @Transactional
    public long createBot(String userCode, String displayName, String avatarKey, long operator) {
        requireAdmin(operator);
        TestPlayerAdminService.TestPlayerAdminView view = testPlayerAdminService.create(userCode, displayName, avatarKey, operator);
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE sys_user_id=?", view.player().userId());
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

    @Transactional(readOnly = true)
    public PlayerDeskRepository.Behavior behavior(long userId, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId);
        if (!"BOT".equals(player.playerKind())) return null;
        return repository.findBehavior(userId).orElseGet(() -> repository.ensureBehavior(player.accountId()));
    }

    @Transactional
    public PlayerDeskRepository.Behavior updateBehavior(long userId, String mode, int bets, BigDecimal min,
                                                         BigDecimal max, boolean chat, int messages, long operator) {
        requireAdmin(operator);
        PlayerDeskRepository.PlayerRow player = row(userId);
        if (!"BOT".equals(player.playerKind())) throw BusinessException.badRequest("PLAYER_BEHAVIOR_NOT_APPLICABLE", "普通玩家不支持托行为配置");
        if (!isMode(mode) || bets < 0 || bets > 20 || messages < 0 || messages > 20 || min == null || max == null || min.signum() <= 0 || max.compareTo(min) < 0) {
            throw BusinessException.badRequest("PLAYER_BEHAVIOR_INVALID", "托行为配置无效");
        }
        PlayerDeskRepository.Behavior result = repository.updateBehavior(userId, mode, bets, min, max, chat, messages);
        audit(operator, "PUT", "/api/admin/player-desk/players/" + userId + "/behavior", Long.toString(userId), "mode=" + mode);
        return result;
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
        return repository.findByUserId(id).orElseThrow(() -> BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在"));
    }

    private void requireAdmin(long userId) {
        if (userId <= 0 || !permissionService.hasPermission(userId, "USER_MANAGE")) throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "没有用户管理权限");
    }

    private void audit(long operator, String method, String path, String resource, String summary) {
        auditRepository.record(operator, "USER_MANAGE", method, path, resource, "SUCCESS", null, summary, null, Instant.now());
    }

    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static boolean isMode(String mode) {
        try {
            TestPlayerBehaviorMode.valueOf(mode);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public record Page(List<PlayerDeskRepository.PlayerRow> items, int page, int pageSize, long total) {}
    public record Detail(PlayerDeskRepository.PlayerRow player, PlayerDeskRepository.Behavior behavior,
                         WalletStatistics walletStatistics, List<WalletLedgerEntry> ledger,
                         List<PlayerDeskRepository.ActionRow> actions, List<GameDataRepository.BetRecord> bets) {}
}
