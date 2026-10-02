package com.xupan.server.platformadmin.service;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.agent.service.AgentLedgerService;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class PlatformAdminService {

    private static final Instant MYSQL_TIMESTAMP_MAX = Instant.parse("2038-01-18T00:00:00Z");

    private final JdbcTemplate jdbc;
    private final AgentRepository agentRepository;
    private final AgentAdminService agentAdminService;
    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicy;
    private final PermissionService permissionService;
    private final SessionRepository sessionRepository;
    private final PlatformAdminAccessService accessService;
    private final AgentLedgerService agentLedgerService;

    public PlatformAdminService(JdbcTemplate jdbc, AgentRepository agentRepository,
                                AgentAdminService agentAdminService, UserRepository userRepository,
                                PasswordPolicyService passwordPolicy, PermissionService permissionService,
                                SessionRepository sessionRepository, PlatformAdminAccessService accessService,
                                AgentLedgerService agentLedgerService) {
        this.jdbc = jdbc;
        this.agentRepository = agentRepository;
        this.agentAdminService = agentAdminService;
        this.userRepository = userRepository;
        this.passwordPolicy = passwordPolicy;
        this.permissionService = permissionService;
        this.sessionRepository = sessionRepository;
        this.accessService = accessService;
        this.agentLedgerService = agentLedgerService;
    }

    @Transactional(readOnly = true)
    public List<SubAccountRow> listSubAccounts(long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        return jdbc.query("""
                SELECT g.id, g.group_code, g.username, g.display_name, g.score, g.expires_at, g.status,
                       g.sub_account_manage, g.machine_manage, g.unified_report_enabled,
                       g.report_username, g.report_network_code, g.report_route_code, g.created_at,
                       (SELECT COUNT(*) FROM agent a WHERE a.group_id = g.id AND a.system_owned = FALSE AND a.deleted_at IS NULL) machine_count
                  FROM agent_group g
                 WHERE g.deleted_at IS NULL
                 ORDER BY g.id
                """, (rs, n) -> new SubAccountRow(rs.getLong("id"), rs.getString("group_code"),
                rs.getString("username"), rs.getString("display_name"), rs.getBigDecimal("score"),
                instant(rs.getTimestamp("expires_at")), rs.getString("status"),
                rs.getBoolean("sub_account_manage"), rs.getBoolean("machine_manage"),
                rs.getBoolean("unified_report_enabled"), rs.getString("report_username"),
                rs.getString("report_network_code"),
                rs.getString("report_route_code"), rs.getLong("machine_count"),
                instant(rs.getTimestamp("created_at"))));
    }

    @Transactional
    public SubAccountRow createSubAccount(SubAccountInput input, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        validateExpiry(input.expiresAt());
        String username = required(input.username(), "子账号用户名不能为空");
        String displayName = required(input.displayName(), "子账号名称不能为空");
        if (exists("SELECT COUNT(*) FROM agent_group WHERE username = ?", username)
                || exists("SELECT COUNT(*) FROM sys_user WHERE username = ?", username)) {
            throw BusinessException.conflict("SUB_ACCOUNT_USERNAME_EXISTS", "子账号用户名已存在");
        }
        String code = "SUB_" + username.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (exists("SELECT COUNT(*) FROM agent_group WHERE group_code = ?", code)) {
            code = code + "_" + System.currentTimeMillis();
        }
        String passwordHash = passwordPolicy.encode(required(input.rawPassword(), "子账号密码不能为空"));
        try {
            long accountUserId = userRepository.insert(username, displayName, passwordHash, "ACTIVE");
            userRepository.assignRole(accountUserId, "SUB_ACCOUNT");
            jdbc.update("""
                    INSERT INTO agent_group
                        (group_code, display_name, status, created_by, username, password_hash,
                         expires_at, sub_account_manage, machine_manage, unified_report_enabled,
                         report_username, report_network_code, report_route_code, sys_user_id)
                    VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, code, displayName, operator, username, passwordHash,
                    timestamp(input.expiresAt()), input.subAccountManage(),
                    input.machineManage(), input.unifiedReportEnabled(), trimToNull(input.reportUsername()),
                    trimToNull(input.reportNetworkCode()), trimToNull(input.reportRouteCode()), accountUserId);
            Long id = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, code);
            // 账本原则 2：积分不在 INSERT 里直接写，统一由账本服务写余额 + 流水
            applyGroupScore(id, input.score(), AgentLedgerService.TYPE_SUB_ACCOUNT_INIT, operator);
            audit(operator, "SUB_ACCOUNT_MANAGE", "POST", "/api/admin/sub-accounts", id,
                    "username=" + username);
            return findSubAccount(id);
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("SUB_ACCOUNT_CREATE_CONFLICT", "子账号创建冲突");
        }
    }

    @Transactional
    public SubAccountRow updateSubAccount(long id, SubAccountInput input, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        validateExpiry(input.expiresAt());
        findSubAccount(id);
        Long accountUserId = findSubAccountSysUserId(id);
        String username = required(input.username(), "子账号用户名不能为空");
        String displayName = required(input.displayName(), "子账号名称不能为空");
        if (exists("SELECT COUNT(*) FROM agent_group WHERE username = ? AND id <> ?", username, id)
                || exists("SELECT COUNT(*) FROM sys_user WHERE username = ? AND id <> ?",
                        username, accountUserId == null ? -1L : accountUserId)) {
            throw BusinessException.conflict("SUB_ACCOUNT_USERNAME_EXISTS", "子账号用户名已存在");
        }
        String passwordHash = input.rawPassword() == null || input.rawPassword().isBlank()
                ? null : passwordPolicy.encode(input.rawPassword());
        if (passwordHash == null) {
            jdbc.update("""
                    UPDATE agent_group SET username=?, display_name=?, expires_at=?,
                       sub_account_manage=?, machine_manage=?, unified_report_enabled=?,
                       report_username=?, report_network_code=?, report_route_code=?, updated_at=CURRENT_TIMESTAMP
                     WHERE id=?
                    """, username, displayName,
                    timestamp(input.expiresAt()), input.subAccountManage(), input.machineManage(),
                    input.unifiedReportEnabled(), trimToNull(input.reportUsername()),
                    trimToNull(input.reportNetworkCode()), trimToNull(input.reportRouteCode()), id);
        } else {
            jdbc.update("""
                    UPDATE agent_group SET username=?, password_hash=?, display_name=?, expires_at=?,
                       sub_account_manage=?, machine_manage=?, unified_report_enabled=?,
                       report_username=?, report_network_code=?, report_route_code=?, updated_at=CURRENT_TIMESTAMP
                     WHERE id=?
                    """, username, passwordHash, displayName,
                    timestamp(input.expiresAt()), input.subAccountManage(),
                    input.machineManage(), input.unifiedReportEnabled(), trimToNull(input.reportUsername()),
                    trimToNull(input.reportNetworkCode()), trimToNull(input.reportRouteCode()), id);
        }
        // 账本原则 2：积分变动走账本服务（锁行 + 写流水），目标值未变时不产生账目
        applyGroupScore(id, input.score(), AgentLedgerService.TYPE_SUB_ACCOUNT_ADJUST, operator);
        if (accountUserId == null) {
            String currentHash = jdbc.queryForObject(
                    "SELECT password_hash FROM agent_group WHERE id = ?", String.class, id);
            accountUserId = userRepository.insert(username, displayName,
                    currentHash == null ? passwordPolicy.encodeUnusableCredential() : currentHash, "ACTIVE");
            userRepository.assignRole(accountUserId, "SUB_ACCOUNT");
            jdbc.update("UPDATE agent_group SET sys_user_id = ? WHERE id = ?", accountUserId, id);
        } else {
            jdbc.update("""
                    UPDATE sys_user SET username=?, display_name=?, updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, username, displayName, accountUserId);
            if (passwordHash != null) {
                userRepository.updatePasswordHash(accountUserId, passwordHash);
                sessionRepository.revokeAllActiveByUserId(accountUserId, Instant.now());
            }
        }
        audit(operator, "SUB_ACCOUNT_MANAGE", "PUT", "/api/admin/sub-accounts/" + id, id, "username=" + username);
        return findSubAccount(id);
    }

    @Transactional
    public void changeSubAccountStatus(long id, String status, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        Long accountUserId = findSubAccountSysUserId(id);
        String normalized = normalizeStatus(status);
        jdbc.update("UPDATE agent_group SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", normalized, id);
        if (accountUserId != null) {
            userRepository.updateManagedStatus(accountUserId, normalized);
            sessionRepository.revokeAllActiveByUserId(accountUserId, Instant.now());
        }
        audit(operator, "SUB_ACCOUNT_MANAGE", "PATCH", "/api/admin/sub-accounts/" + id + "/status", id,
                "status=" + normalized);
    }

    @Transactional(readOnly = true)
    public List<MachineRow> listMachines(long operator) {
        require(operator, "MACHINE_MANAGE");
        List<MachineRow> rows = jdbc.query("""
                SELECT a.id, a.agent_code, a.display_name, u.username account_username, a.account_user_id, a.group_id,
                       g.username group_username, a.score, a.expires_at, a.status, a.board_open,
                       a.robot_manage, a.chase_enabled, a.bot_count, a.close_seconds, a.cancel_seconds,
                       a.rebate_rate, a.odds_rate, a.special_rebate_rate, a.special_odds_rate,
                       a.total_limit, a.positive_limit, a.angle_limit, a.strict_limit, a.tong_limit,
                       a.car_limit, a.special_limit, a.odd_even_limit, a.big_small_limit, a.fan_limit,
                       a.add_limit, a.player_max_stake, a.player_min_stake,
                       COALESCE(a.rebate_mode, 'SINGLE') rebate_mode,
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='NORMAL' AND d.status <> 'DELETED') normal_count,
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='BOT' AND d.status <> 'DELETED') bot_count,
                       (SELECT GROUP_CONCAT(ag.game_code ORDER BY ag.game_code) FROM agent_game ag WHERE ag.agent_id=a.id) games
                  FROM agent a
                  LEFT JOIN sys_user u ON u.id=a.account_user_id
                  LEFT JOIN agent_group g ON g.id=a.group_id
                 WHERE a.system_owned=FALSE AND a.deleted_at IS NULL
                 ORDER BY a.id
                """, (rs, n) -> new MachineRow(rs.getLong("id"), rs.getString("agent_code"),
                rs.getString("display_name"), rs.getString("account_username"), rs.getLong("account_user_id"), nullableLong(rs, "group_id"),
                rs.getString("group_username"), rs.getBigDecimal("score"), instant(rs.getTimestamp("expires_at")),
                rs.getString("status"), rs.getBoolean("board_open"), rs.getBoolean("robot_manage"),
                rs.getBoolean("chase_enabled"), rs.getInt("bot_count"), rs.getInt("close_seconds"),
                rs.getInt("cancel_seconds"), rs.getBigDecimal("rebate_rate"), rs.getBigDecimal("odds_rate"),
                rs.getBigDecimal("special_rebate_rate"), rs.getBigDecimal("special_odds_rate"),
                rs.getBigDecimal("total_limit"), rs.getBigDecimal("positive_limit"), rs.getBigDecimal("angle_limit"),
                rs.getBigDecimal("strict_limit"), rs.getBigDecimal("tong_limit"), rs.getBigDecimal("car_limit"),
                rs.getBigDecimal("special_limit"), rs.getBigDecimal("odd_even_limit"), rs.getBigDecimal("big_small_limit"),
                rs.getBigDecimal("fan_limit"), rs.getBigDecimal("add_limit"), rs.getBigDecimal("player_max_stake"),
                rs.getBigDecimal("player_min_stake"), rs.getLong("normal_count"), rs.getLong("bot_count"),
                rs.getString("rebate_mode"), splitGames(rs.getString("games"))));
        Long scope = accessService.subAccountGroupId(operator).orElse(null);
        return scope == null ? rows : rows.stream().filter(row -> scope.equals(row.groupId())).toList();
    }

    @Transactional
    public MachineRow createMachine(MachineInput input, long operator) {
        require(operator, "MACHINE_MANAGE");
        String username = required(input.username(), "机器账号不能为空");
        String displayName = input.displayName() == null || input.displayName().isBlank() ? username : input.displayName().trim();
        Long effectiveGroupId = accessService.subAccountGroupId(operator).orElse(input.groupId());
        var created = agentAdminService.createAgent(username.toUpperCase(Locale.ROOT), username,
                displayName, required(input.rawPassword(), "机器密码不能为空"), effectiveGroupId, operator);
        applyMachine(input, created.id(), effectiveGroupId, operator, true);
        return findMachine(created.id());
    }

    @Transactional
    public MachineRow updateMachine(long id, MachineInput input, long operator) {
        require(operator, "MACHINE_MANAGE");
        accessService.requireMachineAccess(operator, id);
        MachineRow current = findMachine(id);
        if (input.rawPassword() != null && !input.rawPassword().isBlank()) {
            String hash = passwordPolicy.encode(input.rawPassword());
            userRepository.updatePasswordHash(current.accountUserId(), hash);
            sessionRepository.revokeAllActiveByUserId(current.accountUserId(), Instant.now());
        }
        Long effectiveGroupId = accessService.subAccountGroupId(operator).orElse(input.groupId());
        applyMachine(input, id, effectiveGroupId, operator, false);
        return findMachine(id);
    }

    @Transactional
    public void changeMachineStatus(long id, String status, long operator) {
        require(operator, "MACHINE_MANAGE");
        accessService.requireMachineAccess(operator, id);
        MachineRow machine = findMachine(id);
        String normalized = normalizeStatus(status);
        jdbc.update("UPDATE agent SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", normalized, id);
        if (machine.accountUserId() > 0) {
            userRepository.updateManagedStatus(machine.accountUserId(), normalized);
            sessionRepository.revokeAllActiveByUserId(machine.accountUserId(), Instant.now());
        }
        audit(operator, "MACHINE_MANAGE", "PATCH", "/api/admin/machines/" + id + "/status", id,
                "status=" + normalized);
    }

    @Transactional
    public void deleteMachine(long id, long operator) {
        require(operator, "MACHINE_MANAGE");
        accessService.requireMachineAccess(operator, id);
        MachineRow machine = findMachine(id);
        Long playerCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_user_account
                 WHERE agent_id = ? AND status <> 'DELETED'
                """, Long.class, id);
        if (playerCount != null && playerCount > 0) {
            throw BusinessException.conflict("MACHINE_HAS_PLAYERS", "请先处理该机器下的玩家和托");
        }
        int updated = jdbc.update("""
                UPDATE agent
                   SET status='DISABLED', board_open=FALSE, deleted_at=CURRENT_TIMESTAMP,
                       deleted_by=?, updated_at=CURRENT_TIMESTAMP,
                       -- 原则 8：释放上游账号占用，避免软删除的机器长期占位阻挡复用；
                       -- 同时清掉飞单密码密文，删除后的账号不应继续持有凭据。
                       report_username=NULL, report_password_ciphertext=NULL, report_online=FALSE
                 WHERE id=? AND deleted_at IS NULL
                """, operator, id);
        if (updated == 0) throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在");
        if (machine.accountUserId() > 0) {
            userRepository.softDelete(machine.accountUserId());
            sessionRepository.revokeAllActiveByUserId(machine.accountUserId(), Instant.now());
        }
        audit(operator, "MACHINE_MANAGE", "DELETE", "/api/admin/machines/" + id, id,
                "soft-delete machine");
    }

    @Transactional(readOnly = true)
    public List<AgentRepository.AgentPlayerRow> listMachinePlayers(long machineId, long operator) {
        require(operator, "MACHINE_MANAGE");
        accessService.requireMachineAccess(operator, machineId);
        findMachine(machineId);
        return agentRepository.findPlayersByAgent(machineId, null, null, 1, 100);
    }

    @Transactional
    public void deleteSubAccount(long id, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        findSubAccount(id);
        Long machineCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent
                 WHERE group_id = ? AND deleted_at IS NULL
                """, Long.class, id);
        if (machineCount != null && machineCount > 0) {
            throw BusinessException.conflict("SUB_ACCOUNT_HAS_MACHINES", "请先删除该子账号下的机器");
        }
        Long accountUserId = findSubAccountSysUserId(id);
        int updated = jdbc.update("""
                UPDATE agent_group
                   SET status='DISABLED', deleted_at=CURRENT_TIMESTAMP, deleted_by=?,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND deleted_at IS NULL
                """, operator, id);
        if (updated == 0) throw BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在");
        if (accountUserId != null) {
            userRepository.softDelete(accountUserId);
            sessionRepository.revokeAllActiveByUserId(accountUserId, Instant.now());
        }
        audit(operator, "SUB_ACCOUNT_MANAGE", "DELETE", "/api/admin/sub-accounts/" + id, id,
                "soft-delete sub-account");
    }

    private void applyMachine(MachineInput input, long id, Long groupId, long operator, boolean create) {
        validateExpiry(input.expiresAt());
        // 赔率、返水与限额由数据库 CHECK 兜底，这里先做服务端校验，避免把数据库错误暴露成 500。
        requireNonNegative(input.oddsRate(), "MACHINE_ODDS_RATE_INVALID", "机器赔率不能为负数");
        requireNonNegative(input.specialOddsRate(), "MACHINE_SPECIAL_ODDS_RATE_INVALID", "特码赔率不能为负数");
        requireNonNegative(input.rebateRate(), "MACHINE_REBATE_RATE_INVALID", "返水不能为负数");
        requireNonNegative(input.specialRebateRate(), "MACHINE_SPECIAL_REBATE_RATE_INVALID", "特码返水不能为负数");
        requireNonNegative(input.score(), "MACHINE_SCORE_INVALID", "机器积分不能为负数");
        jdbc.update("""
                UPDATE agent SET group_id=?, expires_at=?, board_open=?, robot_manage=?, chase_enabled=?,
                    bot_count=?, close_seconds=?, cancel_seconds=?, rebate_rate=?, odds_rate=?,
                    special_rebate_rate=?, special_odds_rate=?, total_limit=?, positive_limit=?, angle_limit=?,
                    strict_limit=?, tong_limit=?, car_limit=?, special_limit=?, odd_even_limit=?,
                    big_small_limit=?, fan_limit=?, add_limit=?, player_max_stake=?, player_min_stake=?,
                    rebate_mode=?,
                    updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, groupId, timestamp(input.expiresAt()), input.boardOpen(),
                input.robotManage(), input.chaseEnabled(), Math.max(0, input.botCount()),
                Math.max(0, input.closeSeconds()), Math.max(0, input.cancelSeconds()), amount(input.rebateRate()),
                amount(input.oddsRate()), amount(input.specialRebateRate()), amount(input.specialOddsRate()),
                amount(input.totalLimit()), amount(input.positiveLimit()), amount(input.angleLimit()),
                amount(input.strictLimit()), amount(input.tongLimit()), amount(input.carLimit()),
                amount(input.specialLimit()), amount(input.oddEvenLimit()), amount(input.bigSmallLimit()),
                amount(input.fanLimit()), amount(input.addLimit()), amount(input.playerMaxStake()),
                amount(input.playerMinStake()), normalizeRebateMode(input.rebateMode()), id);
        // 账本原则 2：机器积分不在上面的整值 UPDATE 里写死，统一由账本服务锁行改余额并写流水；
        // 目标值与当前值相同时不产生账目（重复提交同一次编辑天然幂等）。
        applyMachineScore(id, input.score(),
                create ? AgentLedgerService.TYPE_MACHINE_INIT : AgentLedgerService.TYPE_MACHINE_ADJUST, operator);
        // BY220 由超管在机器表单里勾选彩种（rb/form.html:42-50 写 x_robot.game）。
        // games 为 null 表示"本次不指定彩种"，必须保留已有配置；
        // 只有显式传入列表时才整体替换，避免编辑机器其它字段时把彩种清成只剩 AU8。
        if (input.games() != null) {
            jdbc.update("DELETE FROM agent_game WHERE agent_id=?", id);
            for (String game : input.games()) {
                if (game == null || game.isBlank()) continue;
                jdbc.update("INSERT INTO agent_game(agent_id, game_code) VALUES (?, ?)", id, game.trim());
            }
        }
        audit(operator, "MACHINE_MANAGE", create ? "POST" : "PUT",
                create ? "/api/admin/machines" : "/api/admin/machines/" + id, id, "machine");
    }

    /**
     * 机器积分的唯一写入点（账本原则 2、3）。
     *
     * <p>幂等键用随机值：超管这类"设置成某值"的操作靠**目标值相同即不写流水**保证幂等，
     * 而不是靠固定键（固定键会在余额反复涨落时撞唯一约束）。</p>
     */
    private void applyMachineScore(long agentId, BigDecimal target, String entryType, long operator) {
        agentLedgerService.setMachineScore(agentId, target == null ? BigDecimal.ZERO : target, entryType,
                "超管维护机器积分", operator,
                "MACHINE-" + entryType + "-" + java.util.UUID.randomUUID());
    }

    /** 子账号积分的唯一写入点（账本原则 2、3）。 */
    private void applyGroupScore(long groupId, BigDecimal target, String entryType, long operator) {
        agentLedgerService.setGroupScore(groupId, target == null ? BigDecimal.ZERO : target, entryType,
                "超管维护子账号积分", operator,
                "SUB-ACCOUNT-" + entryType + "-" + java.util.UUID.randomUUID());
    }

    private SubAccountRow findSubAccount(long id) {
        return listSubAccountsInternal(id).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在"));
    }

    private Long findSubAccountSysUserId(long id) {
        return jdbc.query("SELECT sys_user_id FROM agent_group WHERE id = ?",
                rs -> rs.next() ? rs.getObject("sys_user_id", Long.class) : null, id);
    }

    private List<SubAccountRow> listSubAccountsInternal(long id) {
        return jdbc.query("""
                SELECT g.id, g.group_code, g.username, g.display_name, g.score, g.expires_at, g.status,
                       g.sub_account_manage, g.machine_manage, g.unified_report_enabled,
                       g.report_username, g.report_network_code, g.report_route_code, g.created_at,
                       (SELECT COUNT(*) FROM agent a WHERE a.group_id=g.id AND a.system_owned=FALSE AND a.deleted_at IS NULL) machine_count
                  FROM agent_group g WHERE g.id=? AND g.deleted_at IS NULL
                """, (rs, n) -> new SubAccountRow(rs.getLong("id"), rs.getString("group_code"),
                rs.getString("username"), rs.getString("display_name"), rs.getBigDecimal("score"),
                instant(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getBoolean("sub_account_manage"),
                rs.getBoolean("machine_manage"), rs.getBoolean("unified_report_enabled"),
                rs.getString("report_username"), rs.getString("report_network_code"),
                rs.getString("report_route_code"),
                rs.getLong("machine_count"), instant(rs.getTimestamp("created_at"))), id);
    }

    private MachineRow findMachine(long id) {
        return listMachinesInternal(id).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在"));
    }

    /**
     * 把 {@code GROUP_CONCAT(agent_game.game_code)} 的结果拆成彩种列表（BY220 {@code x_robot.game} 逗号串语义）。
     *
     * <p>{@code null} 表示该机器没有任何彩种配置，此时返回空列表；保存时"空列表"与"未指定"由
     * {@link MachineInput#games()} 的 {@code null} 与否区分，避免编辑机器时误删已有彩种。</p>
     */
    private static List<String> splitGames(String games) {
        if (games == null || games.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : games.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    private List<MachineRow> listMachinesInternal(long id) {        return jdbc.query("""
                SELECT a.id, a.agent_code, a.display_name, u.username account_username, a.account_user_id, a.group_id,
                       g.username group_username, a.score, a.expires_at, a.status, a.board_open, a.robot_manage,
                       a.chase_enabled, a.bot_count, a.close_seconds, a.cancel_seconds, a.rebate_rate, a.odds_rate,
                       a.special_rebate_rate, a.special_odds_rate, a.total_limit, a.positive_limit, a.angle_limit,
                       a.strict_limit, a.tong_limit, a.car_limit, a.special_limit, a.odd_even_limit,
                       a.big_small_limit, a.fan_limit, a.add_limit, a.player_max_stake, a.player_min_stake,
                       COALESCE(a.rebate_mode, 'SINGLE') rebate_mode,
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='NORMAL' AND d.status <> 'DELETED') normal_count,
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='BOT' AND d.status <> 'DELETED') bot_count,
                       (SELECT GROUP_CONCAT(ag.game_code ORDER BY ag.game_code) FROM agent_game ag WHERE ag.agent_id=a.id) games
                  FROM agent a
                  LEFT JOIN sys_user u ON u.id=a.account_user_id
                  LEFT JOIN agent_group g ON g.id=a.group_id
                 WHERE a.id=? AND a.system_owned=FALSE AND a.deleted_at IS NULL
                """, (rs, n) -> new MachineRow(rs.getLong("id"), rs.getString("agent_code"),
                rs.getString("display_name"), rs.getString("account_username"), rs.getLong("account_user_id"),
                nullableLong(rs, "group_id"), rs.getString("group_username"), rs.getBigDecimal("score"),
                instant(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getBoolean("board_open"),
                rs.getBoolean("robot_manage"), rs.getBoolean("chase_enabled"), rs.getInt("bot_count"),
                rs.getInt("close_seconds"), rs.getInt("cancel_seconds"), rs.getBigDecimal("rebate_rate"),
                rs.getBigDecimal("odds_rate"), rs.getBigDecimal("special_rebate_rate"),
                rs.getBigDecimal("special_odds_rate"), rs.getBigDecimal("total_limit"),
                rs.getBigDecimal("positive_limit"), rs.getBigDecimal("angle_limit"), rs.getBigDecimal("strict_limit"),
                rs.getBigDecimal("tong_limit"), rs.getBigDecimal("car_limit"), rs.getBigDecimal("special_limit"),
                rs.getBigDecimal("odd_even_limit"), rs.getBigDecimal("big_small_limit"), rs.getBigDecimal("fan_limit"),
                rs.getBigDecimal("add_limit"), rs.getBigDecimal("player_max_stake"), rs.getBigDecimal("player_min_stake"),
                rs.getLong("normal_count"), rs.getLong("bot_count"), rs.getString("rebate_mode"),
                splitGames(rs.getString("games"))), id);
    }

    private void require(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("PLATFORM_ADMIN_FORBIDDEN", "没有超级管理后台操作权限");
        }
    }

    private void audit(long operator, String permission, String method, String path, long resourceId, String summary) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, permission, method, path, Long.toString(resourceId), summary);
    }

    private boolean exists(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count != null && count > 0;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw BusinessException.badRequest("PLATFORM_ADMIN_REQUEST_INVALID", message);
        return value.trim();
    }

    private static String normalizeStatus(String status) {
        String value = required(status, "状态不能为空").toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(value) && !"DISABLED".equals(value)) {
            throw BusinessException.badRequest("PLATFORM_ADMIN_STATUS_INVALID", "状态无效");
        }
        return value;
    }

    private static void validateExpiry(Instant expiresAt) {
        if (expiresAt != null && expiresAt.isAfter(MYSQL_TIMESTAMP_MAX)) {
            throw BusinessException.badRequest("PLATFORM_ADMIN_EXPIRY_OUT_OF_RANGE",
                    "失效时间不能晚于 2038-01-18");
        }
    }

    private static BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private static void requireNonNegative(BigDecimal value, String code, String message) {
        if (value != null && value.signum() < 0) {
            throw BusinessException.badRequest(code, message);
        }
    }

    /** BY220 x_robot.ls：单边/双边返水口径，未指定时保持单边。 */
    private static String normalizeRebateMode(String value) {
        return MachineBettingConfig.DOUBLE.equalsIgnoreCase(value == null ? null : value.trim())
                ? MachineBettingConfig.DOUBLE
                : MachineBettingConfig.SINGLE;
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record SubAccountInput(String username, String rawPassword, String displayName, BigDecimal score,
                                  Instant expiresAt, boolean subAccountManage, boolean machineManage,
                                  boolean unifiedReportEnabled, String reportUsername,
                                  String reportNetworkCode, String reportRouteCode) {}
    public record SubAccountRow(long id, String groupCode, String username, String displayName, BigDecimal score,
                                Instant expiresAt, String status, boolean subAccountManage, boolean machineManage,
                                boolean unifiedReportEnabled, String reportUsername, String reportNetworkCode,
                                String reportRouteCode, long machineCount, Instant createdAt) {}
    public record MachineInput(String username, String rawPassword, String displayName, Long groupId,
                               BigDecimal score, Instant expiresAt, boolean boardOpen, boolean robotManage,
                               boolean chaseEnabled, int botCount, int closeSeconds, int cancelSeconds,
                               BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                               BigDecimal specialOddsRate, BigDecimal totalLimit, BigDecimal positiveLimit,
                               BigDecimal angleLimit, BigDecimal strictLimit, BigDecimal tongLimit,
                               BigDecimal carLimit, BigDecimal specialLimit, BigDecimal oddEvenLimit,
                               BigDecimal bigSmallLimit, BigDecimal fanLimit, BigDecimal addLimit,
                               BigDecimal playerMaxStake, BigDecimal playerMinStake, List<String> games,
                               String rebateMode) {}
    public record MachineRow(long id, String code, String displayName, String accountUsername, long accountUserId,
                             Long groupId, String groupUsername, BigDecimal score, Instant expiresAt, String status,
                             boolean boardOpen, boolean robotManage, boolean chaseEnabled, int requestedBotCount,
                             int closeSeconds, int cancelSeconds, BigDecimal rebateRate, BigDecimal oddsRate,
                             BigDecimal specialRebateRate, BigDecimal specialOddsRate, BigDecimal totalLimit,
                             BigDecimal positiveLimit, BigDecimal angleLimit, BigDecimal strictLimit,
                             BigDecimal tongLimit, BigDecimal carLimit, BigDecimal specialLimit,
                             BigDecimal oddEvenLimit, BigDecimal bigSmallLimit, BigDecimal fanLimit,
                             BigDecimal addLimit, BigDecimal playerMaxStake, BigDecimal playerMinStake,
                             long normalCount, long botCount, String rebateMode, List<String> games) {
        /** BY220 {@code x_robot.game} 是逗号串；这里统一转成列表，null 表示"未指定"（保存时不改动已有彩种）。 */
        public MachineRow {
            games = games == null ? List.of() : List.copyOf(games);
        }
    }
}



