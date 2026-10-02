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
import com.xupan.server.media.AvatarPresetService;
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
    private final PlayerDeskScopeService scopeService;
    private final UserAdminService userAdminService;
    private final TestPlayerAdminService testPlayerAdminService;
    private final VirtualWalletService walletService;
    private final com.xupan.server.agent.service.AgentConsoleService agentConsoleService;
    private final OperationAuditRepository auditRepository;
    private final TestPlayerBehaviorService behaviorService;
    private final ChatMessageService chatMessageService;
    private final JdbcTemplate jdbc;
    private final GameDataRepository gameDataRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PlayerAccessLinkRepository accessLinkRepository;
    private final PlayerLinkAuthenticationService playerLinkAuthenticationService;
    private final AvatarPresetService avatarPresetService;

    public PlayerDeskAdminService(PlayerDeskRepository repository, PermissionService permissionService,
                                  PlayerDeskScopeService scopeService,
                                  UserAdminService userAdminService, TestPlayerAdminService testPlayerAdminService,
                                  VirtualWalletService walletService, OperationAuditRepository auditRepository,
                                  TestPlayerBehaviorService behaviorService, ChatMessageService chatMessageService,
                                  JdbcTemplate jdbc, GameDataRepository gameDataRepository,
                                  UserRepository userRepository, SessionRepository sessionRepository,
                                  PlayerAccessLinkRepository accessLinkRepository,
                                  PlayerLinkAuthenticationService playerLinkAuthenticationService,
                                  AvatarPresetService avatarPresetService,
                                  com.xupan.server.agent.service.AgentConsoleService agentConsoleService) {
        this.repository = repository;
        this.permissionService = permissionService;
        this.scopeService = scopeService;
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
        this.avatarPresetService = avatarPresetService;
        this.agentConsoleService = agentConsoleService;
    }

    @Transactional(readOnly = true)
    public List<AvatarPresetService.AvatarPresetOption> avatarPresets(Long selectedUserId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (selectedUserId != null) {
            row(selectedUserId, false, scope);
        }
        return avatarPresetService.list(selectedUserId);
    }

    @Transactional
    public Detail replaceAvatar(long userId, String avatarKey, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, false, scope);
        if ("DELETED".equals(player.userStatus()) || "DELETED".equals(player.accountStatus())) {
            throw BusinessException.conflict("PLAYER_AVATAR_FORBIDDEN", "已删除玩家不能更换头像");
        }
        avatarPresetService.replaceForUser(userId, avatarKey);
        audit(operator, "PUT", "/api/admin/player-desk/players/" + userId + "/avatar",
                Long.toString(userId), "avatarKey=" + avatarKey);
        return detail(userId, false, scope);
    }
    @Transactional(readOnly = true)
    public Page page(String kind, String status, String keyword, int page, int pageSize, long operator) {
        return page(kind, status, keyword, page, pageSize, false, operator);
    }

    /**
     * 读取机器级配置（BY220 {@code robot.php} 顶部状态栏与底部控制区）。
     *
     * <p>机器账号固定读自己；管理员与子账号必须传 {@code machineId} 且该机器在自己的范围内。
     * 字段集合与 {@code /platform-admin/machines} 的机器配置一致，保证"代理自设"和"超管设定"是同一份事实。</p>
     */
    @Transactional(readOnly = true)
    public MachineConfig machineConfig(long operator, Long machineId) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        long targetAgentId = machineIdOf(scope, machineId);
        MachineConfig config = jdbc.query("""
                SELECT ag.id, ag.agent_code, ag.display_name, ag.board_open, ag.chase_enabled, ag.bot_count,
                       ag.close_seconds, ag.cancel_seconds, ag.rebate_rate, ag.odds_rate,
                       ag.special_rebate_rate, ag.special_odds_rate, ag.rebate_mode,
                       ag.total_limit, ag.positive_limit, ag.angle_limit, ag.strict_limit, ag.tong_limit,
                       ag.car_limit, ag.special_limit, ag.odd_even_limit, ag.big_small_limit,
                       ag.fan_limit, ag.add_limit, ag.player_min_stake, ag.player_max_stake
                  FROM agent ag WHERE ag.id = ? AND ag.deleted_at IS NULL
                """, (rs, rowNum) -> new MachineConfig(rs.getLong("id"), rs.getString("agent_code"),
                rs.getString("display_name"), rs.getBoolean("board_open"), rs.getBoolean("chase_enabled"),
                rs.getInt("bot_count"), rs.getInt("close_seconds"), rs.getInt("cancel_seconds"),
                rs.getBigDecimal("rebate_rate"), rs.getBigDecimal("odds_rate"),
                rs.getBigDecimal("special_rebate_rate"), rs.getBigDecimal("special_odds_rate"),
                rs.getString("rebate_mode"), rs.getBigDecimal("total_limit"), rs.getBigDecimal("positive_limit"),
                rs.getBigDecimal("angle_limit"), rs.getBigDecimal("strict_limit"), rs.getBigDecimal("tong_limit"),
                rs.getBigDecimal("car_limit"), rs.getBigDecimal("special_limit"), rs.getBigDecimal("odd_even_limit"),
                rs.getBigDecimal("big_small_limit"), rs.getBigDecimal("fan_limit"), rs.getBigDecimal("add_limit"),
                rs.getBigDecimal("player_min_stake"), rs.getBigDecimal("player_max_stake")), targetAgentId)
                .stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或已停用"));
        return config;
    }

    /**
     * 保存机器级配置。机器账号只能改自己那台；管理员与子账号按范围校验。
     *
     * <p>与超级后台机器管理写同一张 {@code agent} 表，但**不改彩种归属**（{@code agent_game} 属于超级后台机器管理），
     * 并写独立审计动作，便于区分是代理自设还是超管设定。</p>
     */
    @Transactional
    public MachineConfig updateMachineConfig(long operator, Long machineId, MachineConfigUpdate update) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (!scope.global() && !permissionService.hasPermission(operator, "AGENT_PLAYER_MANAGE")) {
            throw BusinessException.forbidden("MACHINE_CONFIG_FORBIDDEN", "没有机器配置权限");
        }
        long targetAgentId = machineIdOf(scope, machineId);
        requireNonNegative(update.oddsRate(), "MACHINE_ODDS_RATE_INVALID", "机器赔率不能为负数");
        requireNonNegative(update.specialOddsRate(), "MACHINE_SPECIAL_ODDS_RATE_INVALID", "特码赔率不能为负数");
        requireNonNegative(update.rebateRate(), "MACHINE_REBATE_RATE_INVALID", "返水不能为负数");
        requireNonNegative(update.specialRebateRate(), "MACHINE_SPECIAL_REBATE_RATE_INVALID", "特码返水不能为负数");
        String rebateMode = update.rebateMode() == null ? "SINGLE" : update.rebateMode().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"SINGLE".equals(rebateMode) && !"DOUBLE".equals(rebateMode)) {
            throw BusinessException.badRequest("MACHINE_REBATE_MODE_INVALID", "返水口径无效");
        }
        BigDecimal minStake = update.playerMinStake() == null ? BigDecimal.ZERO : update.playerMinStake();
        BigDecimal maxStake = update.playerMaxStake() == null ? BigDecimal.ZERO : update.playerMaxStake();
        if (minStake.signum() < 0 || maxStake.signum() < 0 || (maxStake.signum() > 0 && maxStake.compareTo(minStake) < 0)) {
            throw BusinessException.badRequest("MACHINE_STAKE_RANGE_INVALID", "玩家注额范围无效");
        }
        jdbc.update("""
                UPDATE agent SET board_open=?, chase_enabled=?, bot_count=?, close_seconds=?, cancel_seconds=?,
                    rebate_rate=?, odds_rate=?, special_rebate_rate=?, special_odds_rate=?, rebate_mode=?,
                    total_limit=?, positive_limit=?, angle_limit=?, strict_limit=?, tong_limit=?, car_limit=?,
                    special_limit=?, odd_even_limit=?, big_small_limit=?, fan_limit=?, add_limit=?,
                    player_min_stake=?, player_max_stake=?, updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND deleted_at IS NULL
                """, update.boardOpen(), update.chaseEnabled(), Math.max(0, update.botCount()),
                Math.max(0, update.closeSeconds()), Math.max(0, update.cancelSeconds()), amount(update.rebateRate()),
                amount(update.oddsRate()), amount(update.specialRebateRate()), amount(update.specialOddsRate()),
                rebateMode, limit(update.totalLimit()), limit(update.positiveLimit()), limit(update.angleLimit()),
                limit(update.strictLimit()), limit(update.tongLimit()), limit(update.carLimit()),
                limit(update.specialLimit()), limit(update.oddEvenLimit()), limit(update.bigSmallLimit()),
                limit(update.fanLimit()), limit(update.addLimit()), minStake, maxStake, targetAgentId);
        auditRepository.record(operator, "AGENT_MACHINE_CONFIG", "PUT", "/api/admin/player-desk/machine-config",
                Long.toString(targetAgentId), "SUCCESS", null,
                "machineId=" + targetAgentId + ",oddsRate=" + update.oddsRate() + ",specialOddsRate="
                        + update.specialOddsRate() + ",rebateRate=" + update.rebateRate() + ",specialRebateRate="
                        + update.specialRebateRate() + ",rebateMode=" + rebateMode + ",closeSeconds="
                        + update.closeSeconds() + ",cancelSeconds=" + update.cancelSeconds() + ",boardOpen="
                        + update.boardOpen(), null, Instant.now());
        return machineConfig(operator, targetAgentId);
    }

    private long machineIdOf(PlayerDeskScopeService.Scope scope, Long machineId) {
        if (scope instanceof PlayerDeskScopeService.Scope.Machine machine) {
            // 机器账号只能操作自己那台；显式传了别人的机器按"不存在"处理，既不静默改成自己，
            // 也不通过 403 暴露别的机器是否存在（与报网 /report 的范围语义保持一致）。
            if (machineId != null && machineId != machine.agentId()) {
                throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或已停用");
            }
            return machine.agentId();
        }
        if (machineId == null) {
            throw BusinessException.badRequest("PLAYER_DESK_MACHINE_REQUIRED", "请先选择机器");
        }
        return scopeService.requireTargetAgentId(scope, machineId);
    }

    private static void requireNonNegative(BigDecimal value, String code, String message) {
        if (value != null && value.signum() < 0) throw BusinessException.badRequest(code, message);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal limit(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    /** 机器级配置的读模型（BY220 {@code robot.php} 顶部状态栏与底部控制区）。 */
    public record MachineConfig(long machineId, String machineCode, String machineName, boolean boardOpen,
                                boolean chaseEnabled, int botLimit, int closeSeconds, int cancelSeconds,
                                BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                                BigDecimal specialOddsRate, String rebateMode, BigDecimal totalLimit,
                                BigDecimal positiveLimit, BigDecimal angleLimit, BigDecimal strictLimit,
                                BigDecimal tongLimit, BigDecimal carLimit, BigDecimal specialLimit,
                                BigDecimal oddEvenLimit, BigDecimal bigSmallLimit, BigDecimal fanLimit,
                                BigDecimal addLimit, BigDecimal playerMinStake, BigDecimal playerMaxStake) {
    }

    /** 机器级配置的写模型；字段与读模型一一对应。 */
    public record MachineConfigUpdate(boolean boardOpen, boolean chaseEnabled, int botCount, int closeSeconds,
                                      int cancelSeconds, BigDecimal rebateRate, BigDecimal oddsRate,
                                      BigDecimal specialRebateRate, BigDecimal specialOddsRate, String rebateMode,
                                      BigDecimal totalLimit, BigDecimal positiveLimit, BigDecimal angleLimit,
                                      BigDecimal strictLimit, BigDecimal tongLimit, BigDecimal carLimit,
                                      BigDecimal specialLimit, BigDecimal oddEvenLimit, BigDecimal bigSmallLimit,
                                      BigDecimal fanLimit, BigDecimal addLimit, BigDecimal playerMinStake,
                                      BigDecimal playerMaxStake) {
    }

    @Transactional(readOnly = true)
    public Page page(String kind, String status, String keyword, int page, int pageSize,
                     boolean includeDeleted, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw BusinessException.badRequest("PLAYER_QUERY_INVALID", "分页参数无效");
        validateDeletedQuery(status, includeDeleted);
        return new Page(repository.findPage(normalize(kind), normalize(status), normalize(keyword), page, pageSize, includeDeleted, scope), page, pageSize,
                repository.count(normalize(kind), normalize(status), normalize(keyword), includeDeleted, scope));
    }

    @Transactional(readOnly = true)
    public PlayerDeskRepository.Summary summary(String kind, String status, String keyword, long operator) {
        return summary(kind, status, keyword, false, operator);
    }

    @Transactional(readOnly = true)
    public PlayerDeskRepository.Summary summary(String kind, String status, String keyword,
                                                boolean includeDeleted, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        validateDeletedQuery(status, includeDeleted);
        return repository.summary(normalize(kind), normalize(status), normalize(keyword), includeDeleted, scope);
    }

    @Transactional(readOnly = true)
    public Detail detail(long userId, long operator) {
        return detail(userId, false, operator);
    }

    @Transactional(readOnly = true)
    public Detail detail(long userId, boolean includeDeleted, long operator) {
        return detail(userId, includeDeleted, scopeService.requireAccess(operator));
    }

    private Detail detail(long userId, boolean includeDeleted, PlayerDeskScopeService.Scope scope) {
        PlayerDeskRepository.PlayerRow player = row(userId, includeDeleted, scope);
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
    public long createNormal(String displayName, Long machineId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        long targetAgentId = scopeService.requireTargetAgentId(scope, machineId);
        long id = userAdminService.createPlayerLinkUser(displayName, operator);
        assignToAgent(id, targetAgentId);
        playerLinkAuthenticationService.issue(id, operator);
        audit(operator, "POST", "/api/admin/player-desk/players/normal", Long.toString(id),
                "playerKind=NORMAL,agentId=" + targetAgentId);
        return id;
    }

    @Transactional
    public long createBot(String userCode, String displayName, Long machineId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        long targetAgentId = scopeService.requireTargetAgentId(scope, machineId);
        if (scope instanceof PlayerDeskScopeService.Scope.Machine machine) {
            // BY220 robot.php：机器账号创建托受自己机器托上限约束，复用代理后台同一实现与同一错误语义。
            long userId = agentConsoleService.createBot(operator, userCode, displayName).userId();
            audit(operator, "POST", "/api/admin/player-desk/players/bot", Long.toString(userId),
                    "playerKind=BOT,agentId=" + machine.agentId());
            return userId;
        }
        TestPlayerAdminService.TestPlayerAdminView view = scope.global()
                ? testPlayerAdminService.create(userCode, displayName, operator)
                : testPlayerAdminService.createForAgent(userCode, displayName, operator);
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT', agent_id=? WHERE sys_user_id=?",
                targetAgentId, view.player().userId());
        jdbc.update("UPDATE sys_user SET auth_mode='BOT_SERVICE' WHERE id=?", view.player().userId());
        playerLinkAuthenticationService.issue(view.player().userId(), operator);
        repository.ensureBehavior(view.player().id());
        audit(operator, "POST", "/api/admin/player-desk/players/bot", Long.toString(view.player().userId()),
                "playerKind=BOT,agentId=" + targetAgentId);
        return view.player().userId();
    }

    @Transactional
    public Detail status(long userId, String status, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, false, scope);
        if ("BOT".equals(player.playerKind())) {
            testPlayerAdminService.changeStatus(player.userCode(), status, operator);
        } else {
            userAdminService.changeStatus(userId, status, operator);
        }
        return detail(userId, false, scope);
    }

    @Transactional
    public Detail delete(long userId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (userId == operator) {
            throw BusinessException.conflict("PLAYER_SELF_OPERATION_FORBIDDEN", "不能删除当前登录管理员");
        }
        PlayerDeskRepository.PlayerRow player = row(userId, true, scope);
        if (permissionService.hasPermission(userId, "USER_MANAGE")) {
            throw BusinessException.forbidden("PLAYER_DELETE_FORBIDDEN", "不能通过玩家工作台删除管理员");
        }
        avatarPresetService.release(userId);
        if ("DELETED".equals(player.userStatus()) && "DELETED".equals(player.accountStatus())) {
            return detail(userId, true, scope);
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
        return detail(userId, true, scope);
    }

    @Transactional
    public Detail grant(long userId, BigDecimal amount, String reason, String key, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        row(userId, false, scope);
        if (scope instanceof PlayerDeskScopeService.Scope.Machine) {
            // BY220 robot.php：机器账号给普通玩家上分要先扣自己的代理积分；托不联动（由共用服务判定）。
            agentConsoleService.changeScore(operator, userId, "TOP_UP", amount, key);
        } else {
            walletService.grant(operator, userId, amount, reason, key);
        }
        return detail(userId, false, scope);
    }

    @Transactional
    public Detail adjust(long userId, BigDecimal amount, String reason, String key, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        row(userId, false, scope);
        if (scope instanceof PlayerDeskScopeService.Scope.Machine) {
            // 下分接口按协议接收负数金额；代理积分只对普通玩家回补，托不联动。
            BigDecimal magnitude = amount == null ? BigDecimal.ZERO : amount.abs();
            agentConsoleService.changeScore(operator, userId, "DOWN", magnitude, key);
        } else {
            walletService.adjust(operator, userId, amount, reason, key);
        }
        return detail(userId, false, scope);
    }

    @Transactional
    public Detail updateNickname(long userId, String displayName, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, false, scope);
        String nextName = displayName == null ? "" : displayName.trim();
        if (nextName.isBlank() || nextName.length() > 128) {
            throw BusinessException.badRequest("PLAYER_NICKNAME_INVALID", "昵称不能为空且不能超过128个字符");
        }
        if (nextName.equals(player.displayName())) return detail(userId, false, scope);
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
        return detail(userId, false, scope);
    }

    @Transactional(readOnly = true)
    public NameHistory nameHistory(long userId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, true, scope);
        long changesToday = jdbc.queryForObject("SELECT COUNT(*) FROM player_name_change_record WHERE user_id=? AND changed_at >= CURRENT_DATE", Long.class, userId);
        List<NameChange> records = jdbc.query("SELECT id, old_display_name, new_display_name, changed_at FROM player_name_change_record WHERE user_id=? ORDER BY id DESC LIMIT 30",
                (rs, n) -> new NameChange(rs.getLong("id"), rs.getString("old_display_name"), rs.getString("new_display_name"), rs.getTimestamp("changed_at").toInstant()), userId);
        return new NameHistory(player.displayName(), Math.max(0, 3 - changesToday), records);
    }

    @Transactional(readOnly = true)
    public BehaviorConfig behavior(long userId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, false, scope);
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
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, false, scope);
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
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        row(userId, false, scope);
        List<PlayerDeskRepository.ActionRow> result = behaviorService.dispatch(userId, true);
        audit(operator, "POST", "/api/admin/player-desk/players/" + userId + "/behavior/run-now", Long.toString(userId), "actions=" + result.size());
        return result;
    }

    @Transactional(readOnly = true)
    public List<PlayerDeskRepository.ActionRow> actions(long userId, int limit, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        row(userId, false, scope);
        return repository.actions(userId, limit);
    }

    @Transactional
    public ChatMessageService.ChatMessageSendOutcome sendMessage(long userId, String content, String clientMessageId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        row(userId, false, scope);
        return behaviorService.sendManualMessage(userId, clientMessageId, content);
    }

    /** 把新建玩家/托归属到解析出来的机器；平台直属代理是默认值，跳过多余写入。 */
    private void assignToAgent(long userId, long agentId) {
        Long current = jdbc.queryForObject("SELECT agent_id FROM demo_user_account WHERE sys_user_id=?", Long.class, userId);
        if (current != null && current == agentId) {
            return;
        }
        jdbc.update("UPDATE demo_user_account SET agent_id=?, updated_at=CURRENT_TIMESTAMP WHERE sys_user_id=?",
                agentId, userId);
    }

    private PlayerDeskRepository.PlayerRow row(long id, boolean includeDeleted, PlayerDeskScopeService.Scope scope) {
        return repository.findByUserId(id, includeDeleted, scope)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在"));
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
