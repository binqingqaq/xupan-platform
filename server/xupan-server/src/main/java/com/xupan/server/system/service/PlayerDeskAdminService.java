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
    private final com.xupan.server.agent.repository.AgentRepository agentRepository;
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
    private final PlayerPhysicalDeleteService playerPhysicalDeleteService;

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
                                  com.xupan.server.agent.service.AgentConsoleService agentConsoleService,
                                  com.xupan.server.agent.repository.AgentRepository agentRepository,
                                  PlayerPhysicalDeleteService playerPhysicalDeleteService) {
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
        this.agentRepository = agentRepository;
        this.playerPhysicalDeleteService = playerPhysicalDeleteService;
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
                SELECT ag.id, ag.agent_code, ag.display_name, ag.manual_betting_open,
                       ag.robot_manage, ag.chase_enabled, ag.bot_count,
                       ag.countdown_visible,
                       ag.close_seconds, ag.cancel_seconds, ag.rebate_rate, ag.odds_rate,
                       ag.special_rebate_rate, ag.special_odds_rate, ag.rebate_mode,
                       ag.total_limit, ag.positive_limit, ag.angle_limit, ag.strict_limit, ag.tong_limit,
                       ag.car_limit, ag.special_limit, ag.odd_even_limit, ag.big_small_limit,
                       ag.fan_limit, ag.add_limit, ag.player_min_stake, ag.player_max_stake,
                       ag.betting_config_version
                  FROM agent ag WHERE ag.id = ? AND ag.deleted_at IS NULL
                """, (rs, rowNum) -> new MachineConfig(rs.getLong("id"), rs.getString("agent_code"),
                rs.getString("display_name"), rs.getBoolean("manual_betting_open"), rs.getBoolean("robot_manage"),
                rs.getBoolean("chase_enabled"),
                rs.getBoolean("countdown_visible"), rs.getInt("bot_count"), rs.getInt("close_seconds"),
                rs.getInt("cancel_seconds"),
                rs.getBigDecimal("rebate_rate"), rs.getBigDecimal("odds_rate"),
                rs.getBigDecimal("special_rebate_rate"), rs.getBigDecimal("special_odds_rate"),
                rs.getString("rebate_mode"), rs.getBigDecimal("total_limit"), rs.getBigDecimal("positive_limit"),
                rs.getBigDecimal("angle_limit"), rs.getBigDecimal("strict_limit"), rs.getBigDecimal("tong_limit"),
                rs.getBigDecimal("car_limit"), rs.getBigDecimal("special_limit"), rs.getBigDecimal("odd_even_limit"),
                rs.getBigDecimal("big_small_limit"), rs.getBigDecimal("fan_limit"), rs.getBigDecimal("add_limit"),
                rs.getBigDecimal("player_min_stake"), rs.getBigDecimal("player_max_stake"),
                rs.getInt("betting_config_version")), targetAgentId)
                .stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或已停用"));
        return config;
    }

    /** 代理机器账号仅可手动切换自己的下注开关；状态锁与下注事务共用 agent 行。 */
    @Transactional
    public MachineBettingState updateMachineBettingState(long operator, Long machineId, Boolean open) {
        if (open == null) {
            throw BusinessException.badRequest("MACHINE_BETTING_STATE_REQUIRED", "机器开盘状态不能为空");
        }
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (!(scope instanceof PlayerDeskScopeService.Scope.Machine machine)) {
            throw BusinessException.forbidden("MACHINE_BETTING_STATE_FORBIDDEN", "只有机器账号可以切换本机开盘状态");
        }
        long targetAgentId = machineIdOf(scope, machineId);
        List<ManualBettingStateRow> rows = jdbc.query("""
                SELECT manual_betting_open, status, expires_at
                  FROM agent
                 WHERE id = ? AND account_user_id = ? AND deleted_at IS NULL
                 FOR UPDATE
                """, (rs, rowNum) -> new ManualBettingStateRow(rs.getBoolean("manual_betting_open"),
                rs.getString("status"), instant(rs.getTimestamp("expires_at"))), targetAgentId, operator);
        ManualBettingStateRow current = rows.stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或已停用"));
        Instant now = Instant.now();
        if (!"ACTIVE".equals(current.status())
                || (current.expiresAt() != null && !current.expiresAt().isAfter(now))) {
            throw BusinessException.conflict("MACHINE_NOT_ACTIVE", "机器已停用或过期，不能切换开盘状态");
        }
        if (current.manualBettingOpen() == open) {
            return new MachineBettingState(current.manualBettingOpen());
        }
        int changed = jdbc.update("""
                UPDATE agent SET manual_betting_open = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND account_user_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
                   AND (expires_at IS NULL OR expires_at > ?)
                """, open, targetAgentId, operator, java.sql.Timestamp.from(now));
        if (changed != 1) {
            throw BusinessException.conflict("MACHINE_NOT_ACTIVE", "机器已停用或过期，不能切换开盘状态");
        }
        auditRepository.record(operator, "AGENT_PLAYER_MANAGE", "PUT",
                "/api/admin/player-desk/machine-betting-state", Long.toString(targetAgentId), "SUCCESS", null,
                "machineId=" + targetAgentId + ",manualBettingOpen=" + open, null, Instant.now());
        return new MachineBettingState(open);
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
        if (update.expectedVersion() == null || update.expectedVersion() < 1) {
            throw BusinessException.badRequest("MACHINE_CONFIG_VERSION_REQUIRED", "机器配置版本无效，请刷新后重试");
        }
        int changed = jdbc.update("""
                UPDATE agent SET chase_enabled=?, bot_count=?, close_seconds=?, cancel_seconds=?, countdown_visible=?,
                    rebate_rate=?, odds_rate=?, special_rebate_rate=?, special_odds_rate=?, rebate_mode=?,
                    total_limit=?, positive_limit=?, angle_limit=?, strict_limit=?, tong_limit=?, car_limit=?,
                    special_limit=?, odd_even_limit=?, big_small_limit=?, fan_limit=?, add_limit=?,
                    player_min_stake=?, player_max_stake=?, betting_config_version=betting_config_version+1,
                    updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND deleted_at IS NULL AND betting_config_version=?
                """, update.chaseEnabled(), Math.max(0, update.botCount()),
                Math.max(0, update.closeSeconds()), Math.max(0, update.cancelSeconds()), update.countdownVisible(), amount(update.rebateRate()),
                amount(update.oddsRate()), amount(update.specialRebateRate()), amount(update.specialOddsRate()),
                rebateMode, limit(update.totalLimit()), limit(update.positiveLimit()), limit(update.angleLimit()),
                limit(update.strictLimit()), limit(update.tongLimit()), limit(update.carLimit()),
                limit(update.specialLimit()), limit(update.oddEvenLimit()), limit(update.bigSmallLimit()),
                limit(update.fanLimit()), limit(update.addLimit()), minStake, maxStake,
                targetAgentId, update.expectedVersion());
        if (changed != 1) {
            throw BusinessException.conflict("MACHINE_CONFIG_VERSION_CONFLICT", "机器配置已被其他操作者修改，请刷新后重试");
        }
        auditRepository.record(operator, "AGENT_MACHINE_CONFIG", "PUT", "/api/admin/player-desk/machine-config",
                Long.toString(targetAgentId), "SUCCESS", null,
                "machineId=" + targetAgentId + ",oddsRate=" + update.oddsRate() + ",specialOddsRate="
                        + update.specialOddsRate() + ",rebateRate=" + update.rebateRate() + ",specialRebateRate="
                        + update.specialRebateRate() + ",rebateMode=" + rebateMode + ",closeSeconds="
                        + update.closeSeconds() + ",cancelSeconds=" + update.cancelSeconds()
                        + ",countdownVisible=" + update.countdownVisible(), null, Instant.now());
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

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static BigDecimal limit(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0 || normalized.scale() > 2
                || normalized.precision() - normalized.scale() > 16) {
            throw BusinessException.badRequest("MACHINE_LIMIT_INVALID", "机器限额必须是非负且最多两位小数的有效金额");
        }
        return normalized.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** 机器级配置的读模型（BY220 {@code robot.php} 顶部状态栏与底部控制区）。 */
    public record MachineConfig(long machineId, String machineCode, String machineName,
                                boolean manualBettingOpen, boolean robotManage,
                                boolean chaseEnabled, boolean countdownVisible, int botLimit, int closeSeconds, int cancelSeconds,
                                BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                                BigDecimal specialOddsRate, String rebateMode, BigDecimal totalLimit,
                                BigDecimal positiveLimit, BigDecimal angleLimit, BigDecimal strictLimit,
                                BigDecimal tongLimit, BigDecimal carLimit, BigDecimal specialLimit,
                                BigDecimal oddEvenLimit, BigDecimal bigSmallLimit, BigDecimal fanLimit,
                                BigDecimal addLimit, BigDecimal playerMinStake, BigDecimal playerMaxStake,
                                int configVersion) {
    }

    /** 机器级配置的写模型；字段与读模型一一对应。 */
    public record MachineConfigUpdate(boolean chaseEnabled, int botCount, int closeSeconds,
                                      int cancelSeconds, boolean countdownVisible, BigDecimal rebateRate, BigDecimal oddsRate,
                                      BigDecimal specialRebateRate, BigDecimal specialOddsRate, String rebateMode,
                                      BigDecimal totalLimit, BigDecimal positiveLimit, BigDecimal angleLimit,
                                      BigDecimal strictLimit, BigDecimal tongLimit, BigDecimal carLimit,
                                      BigDecimal specialLimit, BigDecimal oddEvenLimit, BigDecimal bigSmallLimit,
                                      BigDecimal fanLimit, BigDecimal addLimit, BigDecimal playerMinStake,
                                      BigDecimal playerMaxStake, Integer expectedVersion) {
    }

    public record MachineBettingState(boolean manualBettingOpen) {
    }

    private record ManualBettingStateRow(boolean manualBettingOpen, String status, Instant expiresAt) {
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
                behavior == null ? null : new BehaviorConfig(behavior),
                statistics, ledger, repository.actions(userId, 20), gameDataRepository.findBetsByAccountId(wallet.accountId(), null, 20));
    }

    @Transactional
    public long createNormal(String displayName, Long machineId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        long targetAgentId = scopeService.requireTargetAgentId(scope, machineId);
        long id = userAdminService.createPlayerLinkUser(displayName, operator);
        assignToAgent(id, targetAgentId);
        copyMachineRebateRate(id, targetAgentId);
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
        jdbc.update("""
                UPDATE demo_user_account
                   SET player_kind='BOT', agent_id=?,
                       rebate_rate=(SELECT rebate_rate FROM agent WHERE id=?),
                       updated_at=CURRENT_TIMESTAMP
                 WHERE sys_user_id=?
                """, targetAgentId, targetAgentId, view.player().userId());
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

    /**
     * 删除玩家：**严格对齐 BY220 的物理删除**（{@code application/rbt/controller/Index.php:235-243}）。
     *
     * <p>①玩家余额退回归属机器（写 {@code agent_ledger}，超集）②物理删除玩家行
     * ③不再重算持久化统计（我们的人数/余额统计本来就是实时派生，删除后自然收敛）。
     * 注单、余额流水、改单记录、上下分申请与换名记录**全部保留**，删除前把玩家与机器快照写入。</p>
     *
     * <p>幂等：目标已经不存在时按成功返回且**不二次退款**；不是玩家（例如管理员账号）
     * 或不在自己机器范围内仍然 404。权限不变：{@code /api/admin/player-desk/**} 只认机器账号，
     * 且数据范围收敛为 {@code MACHINE}。</p>
     */
    @Transactional
    public PlayerPhysicalDeleteService.Outcome delete(long userId, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (userId == operator) {
            throw BusinessException.conflict("PLAYER_SELF_OPERATION_FORBIDDEN", "不能删除当前登录管理员");
        }
        PlayerDeskRepository.PlayerRow player = repository.findByUserId(userId, true, scope).orElse(null);
        if (player == null) {
            // 已物理删除 → 幂等成功（第二次删除不得再退款、也不得再报错）；
            // 账号行/登录账号还在却查不到，说明不是玩家或不在自己机器范围内 → 沿用 404。
            if (playerPhysicalDeleteService.isPurged(userId)) {
                return PlayerPhysicalDeleteService.Outcome.alreadyDeleted(userId);
            }
            throw BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在");
        }
        if (permissionService.hasPermission(userId, "USER_MANAGE")) {
            throw BusinessException.forbidden("PLAYER_DELETE_FORBIDDEN", "不能通过玩家工作台删除管理员");
        }
        avatarPresetService.release(userId);
        PlayerPhysicalDeleteService.Outcome outcome = playerPhysicalDeleteService.delete(userId, operator);
        audit(operator, "DELETE", "/api/admin/player-desk/players/" + userId,
                player.internalCode(), "userId=" + userId + ",internalCode=" + player.internalCode()
                        + ",memberCode=" + player.memberCode() + ",displayName=" + player.displayName()
                        + ",status=DELETED_PHYSICAL,refundedAmount=" + outcome.refundedAmount()
                        + ",machineId=" + outcome.machineId()
                        + ",alreadyDeleted=" + outcome.alreadyDeleted());
        return outcome;
    }

    @Transactional
    public Detail grant(long userId, BigDecimal amount, String reason, String key, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        row(userId, false, scope);
        if (scope instanceof PlayerDeskScopeService.Scope.Machine) {
            // BY220 robot.php：机器账号给普通玩家上分要先扣自己的代理积分；托不联动（由共用服务判定）。
            agentConsoleService.changeScore(operator, userId, "TOP_UP", amount, key);
        } else {
            // 计划 §3.5「Q1=A1 的实现口径」/ 需求基线 §3.1：平台侧（超管 GLOBAL / 子账号 GROUP）
            // 也必须沿「机器 → 玩家」这条链走，按**玩家归属机器**扣池（R1=做法①，操作者不选机器），
            // 修复前"凭空给玩家钱包加分、不扣任何池"是既有偏离。
            agentConsoleService.changeScoreForAgent(operator, ownerMachineId(userId), userId, "TOP_UP", amount, key);
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
            // 平台侧下分：与 MACHINE 分支同一协议口径（收负数金额），把分回补到玩家归属机器。
            BigDecimal magnitude = amount == null ? BigDecimal.ZERO : amount.abs();
            agentConsoleService.changeScoreForAgent(operator, ownerMachineId(userId), userId, "DOWN", magnitude, key);
        }
        return detail(userId, false, scope);
    }

    /**
     * 平台侧上下分要扣/回的机器：按**玩家归属**自动判定（计划 §3.5【R1=做法①】，操作者不选机器）。
     *
     * <p>玩家没有归属机器时**拒绝**而不是退回"直接改钱包"的老路径：BY220 里分只能沿
     * 「子账号池/超管 → 机器 → 玩家」单向流转，没有"平台直接给玩家发分"这条链路
     * （需求基线 §3.1、§3.6）。退回老路径会把刚修掉的"凭空发分"重新引回来，
     * 使机器池与玩家钱包总数对不平；这里宁可 400 明确报错。正常数据不会走到这一步——
     * 建玩家/托时必然写入 {@code agent_id}（平台直属玩家落到 {@code PLATFORM_DIRECT} 机器）。</p>
     */
    private long ownerMachineId(long userId) {
        return agentRepository.findCurrentAgentId(userId)
                .orElseThrow(() -> BusinessException.badRequest("PLAYER_DESK_MACHINE_REQUIRED",
                        "该玩家未归属任何机器，无法从机器积分池扣减"));
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
        return new BehaviorConfig(behavior);
    }

    @Transactional
    public BehaviorConfig updateBehavior(long userId, String mode, String stakeRangeCode,
                                         boolean chaseEnabled, boolean feidanEnabled, long operator) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        PlayerDeskRepository.PlayerRow player = row(userId, false, scope);
        if (!"BOT".equals(player.playerKind())) {
            throw BusinessException.badRequest("PLAYER_BEHAVIOR_NOT_APPLICABLE", "普通玩家不支持托行为配置");
        }
        BigDecimal[] bounds = stakeBounds(stakeRangeCode);
        if (!isMode(mode) || bounds == null) {
            throw BusinessException.badRequest("PLAYER_BEHAVIOR_INVALID", "托行为配置无效");
        }
        PlayerDeskRepository.Behavior current = repository.findBehavior(userId)
                .orElseGet(() -> repository.ensureBehavior(player.accountId()));
        if (chaseEnabled && !current.machineChaseEnabled()) {
            throw BusinessException.badRequest("PLAYER_CHASE_NOT_ENABLED", "机器账号尚未开通追单权限");
        }
        PlayerDeskRepository.Behavior result = repository.updateBehavior(userId, mode, bounds[0], bounds[1],
                stakeRangeCode, chaseEnabled, feidanEnabled);
        audit(operator, "PUT", "/api/admin/player-desk/players/" + userId + "/behavior", Long.toString(userId),
                "mode=" + mode + ",chase=" + chaseEnabled + ",feidan=" + feidanEnabled);
        return new BehaviorConfig(result);
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

    private void copyMachineRebateRate(long userId, long agentId) {
        jdbc.update("""
                UPDATE demo_user_account
                   SET rebate_rate=(SELECT rebate_rate FROM agent WHERE id=?),
                       updated_at=CURRENT_TIMESTAMP
                 WHERE sys_user_id=? AND agent_id=?
                """, agentId, userId, agentId);
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

    /** BY220 固定的五档托下注范围。 */
    private static BigDecimal[] stakeBounds(String code) {
        if (code == null) return null;
        return switch (code) {
            case "30-300" -> new BigDecimal[]{new BigDecimal("30"), new BigDecimal("300")};
            case "300-1000" -> new BigDecimal[]{new BigDecimal("300"), new BigDecimal("1000")};
            case "1000-3000" -> new BigDecimal[]{new BigDecimal("1000"), new BigDecimal("3000")};
            case "3000-10000" -> new BigDecimal[]{new BigDecimal("3000"), new BigDecimal("10000")};
            case "10000-30000" -> new BigDecimal[]{new BigDecimal("10000"), new BigDecimal("30000")};
            default -> null;
        };
    }

    public record Page(List<PlayerDeskRepository.PlayerRow> items, int page, int pageSize, long total) {}
    public record BehaviorConfig(PlayerDeskRepository.Behavior behavior) {}
    public record Detail(PlayerDeskRepository.PlayerRow player, LinkStatus linkStatus, BehaviorConfig behavior,
                         WalletStatistics walletStatistics, List<WalletLedgerEntry> ledger,
                         List<PlayerDeskRepository.ActionRow> actions, List<GameDataRepository.BetRecord> bets) {}
    public record LinkStatus(long linkId, String scope, Instant expiresAt, Instant revokedAt,
                             Instant lastUsedAt, boolean active, int configuredDays) {}
    public record NameHistory(String currentName, long remainingToday, List<NameChange> records) {}
    public record NameChange(long id, String oldName, String newName, Instant changedAt) {}
}
