package com.xupan.server.platformadmin.service;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
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

    public PlatformAdminService(JdbcTemplate jdbc, AgentRepository agentRepository,
                                AgentAdminService agentAdminService, UserRepository userRepository,
                                PasswordPolicyService passwordPolicy, PermissionService permissionService,
                                SessionRepository sessionRepository, PlatformAdminAccessService accessService) {
        this.jdbc = jdbc;
        this.agentRepository = agentRepository;
        this.agentAdminService = agentAdminService;
        this.userRepository = userRepository;
        this.passwordPolicy = passwordPolicy;
        this.permissionService = permissionService;
        this.sessionRepository = sessionRepository;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public List<SubAccountRow> listSubAccounts(long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        return jdbc.query("""
                SELECT g.id, g.group_code, g.username, g.display_name, g.score, g.expires_at, g.status,
                       g.sub_account_manage, g.machine_manage, g.unified_report_enabled,
                       g.report_network_code, g.report_route_code, g.created_at,
                       (SELECT COUNT(*) FROM agent a WHERE a.group_id = g.id AND a.system_owned = FALSE AND a.deleted_at IS NULL) machine_count
                  FROM agent_group g
                 WHERE g.deleted_at IS NULL
                 ORDER BY g.id
                """, (rs, n) -> new SubAccountRow(rs.getLong("id"), rs.getString("group_code"),
                rs.getString("username"), rs.getString("display_name"), rs.getBigDecimal("score"),
                instant(rs.getTimestamp("expires_at")), rs.getString("status"),
                rs.getBoolean("sub_account_manage"), rs.getBoolean("machine_manage"),
                rs.getBoolean("unified_report_enabled"), rs.getString("report_network_code"),
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
                        (group_code, display_name, status, created_by, username, password_hash, score,
                         expires_at, sub_account_manage, machine_manage, unified_report_enabled,
                         report_username, report_network_code, report_route_code, sys_user_id)
                    VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, code, displayName, operator, username, passwordHash,
                    amount(input.score()), timestamp(input.expiresAt()), input.subAccountManage(),
                    input.machineManage(), input.unifiedReportEnabled(), trimToNull(input.reportUsername()),
                    trimToNull(input.reportNetworkCode()), trimToNull(input.reportRouteCode()), accountUserId);
            Long id = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, code);
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
                    UPDATE agent_group SET username=?, display_name=?, score=?, expires_at=?,
                       sub_account_manage=?, machine_manage=?, unified_report_enabled=?,
                       report_username=?, report_network_code=?, report_route_code=?, updated_at=CURRENT_TIMESTAMP
                     WHERE id=?
                    """, username, displayName, amount(input.score()),
                    timestamp(input.expiresAt()), input.subAccountManage(), input.machineManage(),
                    input.unifiedReportEnabled(), trimToNull(input.reportUsername()),
                    trimToNull(input.reportNetworkCode()), trimToNull(input.reportRouteCode()), id);
        } else {
            jdbc.update("""
                    UPDATE agent_group SET username=?, password_hash=?, display_name=?, score=?, expires_at=?,
                       sub_account_manage=?, machine_manage=?, unified_report_enabled=?,
                       report_username=?, report_network_code=?, report_route_code=?, updated_at=CURRENT_TIMESTAMP
                     WHERE id=?
                    """, username, passwordHash, displayName,
                    amount(input.score()), timestamp(input.expiresAt()), input.subAccountManage(),
                    input.machineManage(), input.unifiedReportEnabled(), trimToNull(input.reportUsername()),
                    trimToNull(input.reportNetworkCode()), trimToNull(input.reportRouteCode()), id);
        }
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
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='NORMAL' AND d.status <> 'DELETED') normal_count,
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='BOT' AND d.status <> 'DELETED') bot_count
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
                rs.getBigDecimal("player_min_stake"), rs.getLong("normal_count"), rs.getLong("bot_count")));
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
                       deleted_by=?, updated_at=CURRENT_TIMESTAMP
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
        jdbc.update("""
                UPDATE agent SET group_id=?, score=?, expires_at=?, board_open=?, robot_manage=?, chase_enabled=?,
                    bot_count=?, close_seconds=?, cancel_seconds=?, rebate_rate=?, odds_rate=?,
                    special_rebate_rate=?, special_odds_rate=?, total_limit=?, positive_limit=?, angle_limit=?,
                    strict_limit=?, tong_limit=?, car_limit=?, special_limit=?, odd_even_limit=?,
                    big_small_limit=?, fan_limit=?, add_limit=?, player_max_stake=?, player_min_stake=?,
                    updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, groupId, amount(input.score()), timestamp(input.expiresAt()), input.boardOpen(),
                input.robotManage(), input.chaseEnabled(), Math.max(0, input.botCount()),
                Math.max(0, input.closeSeconds()), Math.max(0, input.cancelSeconds()), amount(input.rebateRate()),
                amount(input.oddsRate()), amount(input.specialRebateRate()), amount(input.specialOddsRate()),
                amount(input.totalLimit()), amount(input.positiveLimit()), amount(input.angleLimit()),
                amount(input.strictLimit()), amount(input.tongLimit()), amount(input.carLimit()),
                amount(input.specialLimit()), amount(input.oddEvenLimit()), amount(input.bigSmallLimit()),
                amount(input.fanLimit()), amount(input.addLimit()), amount(input.playerMaxStake()),
                amount(input.playerMinStake()), id);
        jdbc.update("DELETE FROM agent_game WHERE agent_id=?", id);
        if (input.games() != null) {
            for (String game : input.games()) {
                if (game == null || game.isBlank()) continue;
                jdbc.update("INSERT INTO agent_game(agent_id, game_code) VALUES (?, ?)", id, game.trim());
            }
        }
        audit(operator, "MACHINE_MANAGE", create ? "POST" : "PUT",
                create ? "/api/admin/machines" : "/api/admin/machines/" + id, id, "machine");
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
                       g.report_network_code, g.report_route_code, g.created_at,
                       (SELECT COUNT(*) FROM agent a WHERE a.group_id=g.id AND a.system_owned=FALSE AND a.deleted_at IS NULL) machine_count
                  FROM agent_group g WHERE g.id=? AND g.deleted_at IS NULL
                """, (rs, n) -> new SubAccountRow(rs.getLong("id"), rs.getString("group_code"),
                rs.getString("username"), rs.getString("display_name"), rs.getBigDecimal("score"),
                instant(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getBoolean("sub_account_manage"),
                rs.getBoolean("machine_manage"), rs.getBoolean("unified_report_enabled"),
                rs.getString("report_network_code"), rs.getString("report_route_code"),
                rs.getLong("machine_count"), instant(rs.getTimestamp("created_at"))), id);
    }

    private MachineRow findMachine(long id) {
        return listMachinesInternal(id).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在"));
    }

    private List<MachineRow> listMachinesInternal(long id) {
        return jdbc.query("""
                SELECT a.id, a.agent_code, a.display_name, u.username account_username, a.account_user_id, a.group_id,
                       g.username group_username, a.score, a.expires_at, a.status, a.board_open, a.robot_manage,
                       a.chase_enabled, a.bot_count, a.close_seconds, a.cancel_seconds, a.rebate_rate, a.odds_rate,
                       a.special_rebate_rate, a.special_odds_rate, a.total_limit, a.positive_limit, a.angle_limit,
                       a.strict_limit, a.tong_limit, a.car_limit, a.special_limit, a.odd_even_limit,
                       a.big_small_limit, a.fan_limit, a.add_limit, a.player_max_stake, a.player_min_stake,
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='NORMAL' AND d.status <> 'DELETED') normal_count,
                       (SELECT COUNT(*) FROM demo_user_account d WHERE d.agent_id=a.id AND d.player_kind='BOT' AND d.status <> 'DELETED') bot_count
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
                rs.getLong("normal_count"), rs.getLong("bot_count")), id);
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
                                boolean unifiedReportEnabled, String reportNetworkCode, String reportRouteCode,
                                long machineCount, Instant createdAt) {}
    public record MachineInput(String username, String rawPassword, String displayName, Long groupId,
                               BigDecimal score, Instant expiresAt, boolean boardOpen, boolean robotManage,
                               boolean chaseEnabled, int botCount, int closeSeconds, int cancelSeconds,
                               BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                               BigDecimal specialOddsRate, BigDecimal totalLimit, BigDecimal positiveLimit,
                               BigDecimal angleLimit, BigDecimal strictLimit, BigDecimal tongLimit,
                               BigDecimal carLimit, BigDecimal specialLimit, BigDecimal oddEvenLimit,
                               BigDecimal bigSmallLimit, BigDecimal fanLimit, BigDecimal addLimit,
                               BigDecimal playerMaxStake, BigDecimal playerMinStake, List<String> games) {}
    public record MachineRow(long id, String code, String displayName, String accountUsername, long accountUserId,
                             Long groupId, String groupUsername, BigDecimal score, Instant expiresAt, String status,
                             boolean boardOpen, boolean robotManage, boolean chaseEnabled, int requestedBotCount,
                             int closeSeconds, int cancelSeconds, BigDecimal rebateRate, BigDecimal oddsRate,
                             BigDecimal specialRebateRate, BigDecimal specialOddsRate, BigDecimal totalLimit,
                             BigDecimal positiveLimit, BigDecimal angleLimit, BigDecimal strictLimit,
                             BigDecimal tongLimit, BigDecimal carLimit, BigDecimal specialLimit,
                             BigDecimal oddEvenLimit, BigDecimal bigSmallLimit, BigDecimal fanLimit,
                             BigDecimal addLimit, BigDecimal playerMaxStake, BigDecimal playerMinStake,
                             long normalCount, long botCount) {}
}



