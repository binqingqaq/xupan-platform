package com.xupan.server.platformadmin.service;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.agent.service.AgentLedgerService;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.playerauth.service.PlayerLinkTokenCipher;
import com.xupan.server.report.service.ReportService;
import com.xupan.server.web.BusinessException;
import com.xupan.server.web.MoneyAmounts;
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
    /** BY220 {@code x_admin.feidanpwd} 的明文长度上限（与机器报网配置一致，见 ReportService）。 */
    private static final int MAX_REPORT_PASSWORD_LENGTH = 128;
    /**
     * BY220 {@code x_admin.feidanurl}（上游地址）的长度上限，与列宽 {@code report_url VARCHAR(512)}、
     * 机器端 {@code ReportService#MAX_URL_LENGTH} 一致。
     */
    private static final int MAX_REPORT_URL_LENGTH = 512;

    private final JdbcTemplate jdbc;
    private final AgentRepository agentRepository;
    private final AgentAdminService agentAdminService;
    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicy;
    private final PermissionService permissionService;
    private final SessionRepository sessionRepository;
    private final PlatformAdminAccessService accessService;
    private final AgentLedgerService agentLedgerService;
    private final AccountPurgeService accountPurgeService;
    private final PlayerLinkTokenCipher reportPasswordCipher;

    public PlatformAdminService(JdbcTemplate jdbc, AgentRepository agentRepository,
                                AgentAdminService agentAdminService, UserRepository userRepository,
                                PasswordPolicyService passwordPolicy, PermissionService permissionService,
                                SessionRepository sessionRepository, PlatformAdminAccessService accessService,
                                AgentLedgerService agentLedgerService, AccountPurgeService accountPurgeService,
                                PlayerLinkTokenCipher reportPasswordCipher) {
        this.jdbc = jdbc;
        this.agentRepository = agentRepository;
        this.agentAdminService = agentAdminService;
        this.userRepository = userRepository;
        this.passwordPolicy = passwordPolicy;
        this.permissionService = permissionService;
        this.sessionRepository = sessionRepository;
        this.accessService = accessService;
        this.agentLedgerService = agentLedgerService;
        this.accountPurgeService = accountPurgeService;
        this.reportPasswordCipher = reportPasswordCipher;
    }

    /**
     * 子账号列表。
     *
     * <p>BY220 {@code Users.php::GetUserList()} 对非根管理员用
     * {@code where('UserName', session('user_id'))} —— 子账号登录后**只能看到自己那一行**；
     * 根管理员（{@code user_uid == 1}）才看到全部。这里按同一口径收窄：
     * 操作者本身是子账号时只返回它自己（安全上比 BY220 更严：BY220 的编辑接口没有归属校验，
     * 本项目不允许子账号改别人的账号，见 {@link #requireSubAccountOwnership}）。</p>
     *
     * <p><b>BY220 读取副作用（照抄）</b>：{@code Users.php:147-149} 在读取列表时对"统一飞单配置不完整"
     * 的子账号执行 {@code update(['feidanonline' => 0])}。本方法先跑同一口径的归一化
     * （见 {@link #normalizeReportOnline}），再读取；因为这里带写操作，事务**不能声明为只读**。</p>
     */
    @Transactional
    public List<SubAccountRow> listSubAccounts(long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        Long ownGroupId = accessService.subAccountGroupId(operator).orElse(null);
        // BY220 Users.php:148 的读取时副作用：配置不完整 → 子账号网盘状态强制 0。
        normalizeReportOnline(ownGroupId);
        List<SubAccountRow> rows = jdbc.query("""
                SELECT g.id, g.group_code, g.username, g.display_name, g.score, g.expires_at, g.status,
                       u.status account_status,
                       g.sub_account_manage, g.machine_manage, g.unified_report_enabled,
                       g.report_username, g.report_network_code, g.report_route_code, g.report_type, g.created_at,
                       g.report_url, g.report_online, g.report_balance, g.report_stake,
                       g.online, g.logtime,
                       CASE WHEN g.report_password_ciphertext IS NULL
                                 OR g.report_password_ciphertext = '' THEN FALSE ELSE TRUE END has_report_password,
                       (SELECT COUNT(*) FROM agent a WHERE a.group_id = g.id AND a.system_owned = FALSE AND a.deleted_at IS NULL) machine_count
                  FROM agent_group g
                  LEFT JOIN sys_user u ON u.id = g.sys_user_id
                 WHERE g.deleted_at IS NULL
                 ORDER BY g.id
                """, (rs, n) -> new SubAccountRow(rs.getLong("id"), rs.getString("group_code"),
                rs.getString("username"), rs.getString("display_name"), rs.getBigDecimal("score"),
                instant(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getString("account_status"),
                rs.getBoolean("sub_account_manage"), rs.getBoolean("machine_manage"),
                rs.getBoolean("unified_report_enabled"), rs.getString("report_username"),
                rs.getString("report_network_code"),
                rs.getString("report_route_code"), rs.getString("report_type"),
                rs.getString("report_url"), rs.getBoolean("report_online"),
                rs.getBigDecimal("report_balance"), rs.getBigDecimal("report_stake"),
                rs.getLong("machine_count"),
                instant(rs.getTimestamp("created_at")),
                rs.getBoolean("online"), instant(rs.getTimestamp("logtime")),
                rs.getBoolean("has_report_password")));
        return ownGroupId == null ? rows
                : rows.stream().filter(row -> row.id() == ownGroupId).toList();
    }

    /**
     * BY220 {@code Users.php:147-149} 的读取副作用：统一飞单配置不完整 → 网盘状态强制离线。
     *
     * <p>判定口径与 BY220 的四个条件一一对应：飞单开关（{@code feidan} →
     * {@code unified_report_enabled}）、飞单账号（{@code feidanname} → {@code report_username}）、
     * 飞单密码（{@code feidanpwd} → {@code report_password_ciphertext}）、网盘（{@code feidanid} →
     * {@code report_network_code}）任一缺失即视为配置不完整。</p>
     *
     * <p>只更新 {@code report_online = TRUE} 且确实配置不完整的行，避免每次列表读取都制造无谓写放大；
     * 余额与下注读数**保留**（BY220 也只改 {@code feidanonline}）。</p>
     *
     * @param ownGroupId 非空时只归一化该子账号自己的行（BY220 子账号登录后只看得到自己那一行，
     *                   其读取副作用自然也只会落到自己行上）
     */
    private void normalizeReportOnline(Long ownGroupId) {
        String sql = """
                UPDATE agent_group SET report_online=FALSE, updated_at=CURRENT_TIMESTAMP
                 WHERE deleted_at IS NULL AND report_online=TRUE
                   AND (unified_report_enabled=FALSE
                        OR report_username IS NULL OR report_username=''
                        OR report_password_ciphertext IS NULL OR report_password_ciphertext=''
                        OR report_network_code IS NULL OR report_network_code='')
                """;
        if (ownGroupId == null) {
            jdbc.update(sql);
        } else {
            jdbc.update(sql + " AND id=?", ownGroupId);
        }
    }

    /**
     * 子账号操作者的归属校验（比 BY220 更严的安全偏离）。
     *
     * <p>BY220 {@code Users.php::add()} 里针对非根管理员的 {@code subxiugai} 校验被注释掉了，
     * 等于子账号可以改到别人的账号——照抄会留下越权口子，因此本项目要求：
     * 操作者本身是子账号时，只能操作**自己那一行**；根管理员不受限。</p>
     */
    private void requireSubAccountOwnership(long operator, long groupId) {
        accessService.subAccountGroupId(operator).ifPresent(ownGroupId -> {
            if (ownGroupId != groupId) {
                throw BusinessException.forbidden("SUB_ACCOUNT_SCOPE_FORBIDDEN", "不能操作其他子账号");
            }
        });
    }


    /**
     * 新建子账号。BY220 {@code Users.php::add()} 新增分支用 {@code explode(',', $data['UserName'])}
     * 拆开用户名后循环 {@code AdminModel::create($data)}，所以**一次提交可以批量建号**：
     * 密码（缺省 123456）、显示名、权限、失效时间、初始积分对每个名字完全相同；
     * 用户名重复返回「用户名已存在！」。本方法在**同一个事务**里逐个创建，任何一个失败全部回滚，
     * 并返回**第一个**创建成功的子账号（保持原有返回类型）。
     */
    @Transactional
    public SubAccountRow createSubAccount(SubAccountInput input, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        validateExpiry(input.expiresAt());
        // BY220 x_admin 是积分源头：新建时 score 直接落库，不从别处扣（没有子账号池的概念）。
        List<String> usernames = splitUsernames(input.username());
        String displayName = required(input.displayName(), "子账号名称不能为空");
        for (String username : usernames) {
            if (exists("SELECT COUNT(*) FROM agent_group WHERE username = ?", username)
                    || exists("SELECT COUNT(*) FROM sys_user WHERE username = ?", username)) {
                throw BusinessException.conflict("SUB_ACCOUNT_USERNAME_EXISTS", "子账号用户名已存在");
            }
        }
        String passwordHash = passwordPolicy.encode(required(input.rawPassword(), "子账号密码不能为空"));
        // BY220 x_admin.feidanpwd：飞单密码与飞单账号/网盘/盘口是一组配置，只存密文、不回显。
        String reportPassword = reportPasswordCiphertext(input.reportPassword());
        // 统一飞单的报网类型（V73 agent_group.report_type）：空表示"未设置"，非空必须在允许集合内。
        String reportType = resolveReportType(input.reportType());
        // 上游地址（V74 agent_group.report_url，BY220 x_admin.feidanurl）：只保存不请求。
        String reportUrl = resolveReportUrl(input.reportUrl());
        try {
            Long firstId = null;
            for (String username : usernames) {
                String code = uniqueGroupCode(username);
                long accountUserId = userRepository.insert(username, displayName, passwordHash, "ACTIVE");
                userRepository.assignRole(accountUserId, "SUB_ACCOUNT");
                jdbc.update("""
                        INSERT INTO agent_group
                            (group_code, display_name, status, created_by, username, password_hash,
                             expires_at, sub_account_manage, machine_manage, unified_report_enabled,
                             report_username, report_password_ciphertext, report_network_code,
                             report_route_code, report_type, report_url, sys_user_id)
                        VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, code, displayName, operator, username, passwordHash,
                        timestamp(input.expiresAt()), input.subAccountManage(),
                        input.machineManage(), input.unifiedReportEnabled(), trimToNull(input.reportUsername()),
                        reportPassword, trimToNull(input.reportNetworkCode()),
                        trimToNull(input.reportRouteCode()), reportType, reportUrl, accountUserId);
                Long id = jdbc.queryForObject("SELECT id FROM agent_group WHERE group_code = ?", Long.class, code);
                // 账本原则 2：积分不在 INSERT 里直接写，统一由账本服务写余额 + 流水
                applyGroupScore(id, input.score(), AgentLedgerService.TYPE_SUB_ACCOUNT_INIT, operator);
                if (firstId == null) {
                    firstId = id;
                }
            }
            audit(operator, "SUB_ACCOUNT_MANAGE", "POST", "/api/admin/sub-accounts", firstId,
                    "username=" + String.join(",", usernames) + ",created=" + usernames.size());
            return findSubAccount(firstId);
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("SUB_ACCOUNT_CREATE_CONFLICT", "子账号创建冲突");
        }
    }

    @Transactional
    public SubAccountRow updateSubAccount(long id, SubAccountInput input, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        requireSubAccountOwnership(operator, id);
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
        // BY220 sub/form.html 的「上分 / 下分」是相对当前余额的增减（Users.php 第 62-72 行），
        // 且用 !empty 判定 → null 与 0 都表示"本次不调整"。
        BigDecimal up = adjustAmount(input.up());
        BigDecimal down = adjustAmount(input.down());
        if (up != null && down != null) {
            throw BusinessException.badRequest("SUB_ACCOUNT_SCORE_ADJUST_INVALID", "上分与下分不能同时提交");
        }
        String passwordHash = input.rawPassword() == null || input.rawPassword().isBlank()
                ? null : passwordPolicy.encode(input.rawPassword());
        // 飞单密码（BY220 x_admin.feidanpwd）：只在本次传了非空明文时覆盖，不传则保留已存密文。
        String reportPassword = reportPasswordCiphertext(input.reportPassword());
        // 统一飞单的报网类型（V73 agent_group.report_type）：与 reportUsername/reportNetworkCode/
        // reportRouteCode 同样按**整表单覆盖**语义写入（BY220 `Users.php::add()` 编辑分支就是
        // 整表单 `update($data)`），因此不传即清空；空串统一落 NULL 表示"未设置"。
        String reportType = resolveReportType(input.reportType());
        // 上游地址（V74 agent_group.report_url，BY220 x_admin.feidanurl）：与上面几个飞单字段同样按
        // 整表单覆盖写入；空串统一落 NULL 表示"未设置"。只保存不请求（与机器端 report_url 同语义）。
        String reportUrl = resolveReportUrl(input.reportUrl());
        // 注意：report_online / report_balance / report_stake 是**上游状态**（BY220
        // x_admin.feidanonline / feidanmoney / feidanbetting），BY220 的子账号表单没有这三个字段，
        // 因此这里刻意不接受表单设定，只由报网登录/退出与列表读取归一化维护。
        StringBuilder assignment = new StringBuilder("""
                username=?, display_name=?, expires_at=?,
                   sub_account_manage=?, machine_manage=?, unified_report_enabled=?,
                   report_username=?, report_network_code=?, report_route_code=?, report_type=?, report_url=?
                """);
        List<Object> args = new ArrayList<>();
        args.add(username);
        args.add(displayName);
        args.add(timestamp(input.expiresAt()));
        args.add(input.subAccountManage());
        args.add(input.machineManage());
        args.add(input.unifiedReportEnabled());
        args.add(trimToNull(input.reportUsername()));
        args.add(trimToNull(input.reportNetworkCode()));
        args.add(trimToNull(input.reportRouteCode()));
        args.add(reportType);
        args.add(reportUrl);
        if (passwordHash != null) {
            assignment.append(", password_hash=?");
            args.add(passwordHash);
        }
        if (reportPassword != null) {
            assignment.append(", report_password_ciphertext=?");
            args.add(reportPassword);
        }
        args.add(id);
        jdbc.update("UPDATE agent_group SET " + assignment + ", updated_at=CURRENT_TIMESTAMP WHERE id=?",
                args.toArray());
        // BY220 sub/form.html:104-111 的「在线/下线」开关（Users.php 第 55 行 online => 1/0 直接落库）。
        // x_admin.online 只是在线状态展示，BY220 登录时不校验它（Login.php::check() 只看密码与失效时间），
        // 所以这里只写状态、不参与任何登录拦截。
        if (input.online() != null) {
            jdbc.update("UPDATE agent_group SET online=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    input.online() ? 1 : 0, id);
        }
        // 账本原则 2：积分变动走账本服务（锁行 + 写流水），目标值未变时不产生账目。
        // BY220 编辑分支 `Users.php:79` 明确 `unset($data['score'], ...)` —— 编辑表单里的 score 是
        // readonly，**编辑期根本不接受直接设定**，改积分只能走上面的 up/down。
        // 这里按 BY220 原样：即使调用方传了 score 也忽略（避免绕过 up/down 的余额校验）。
        // BY220 上分 setInc('score', up)，下分先校验余额再 setDec('score', down)；
        // 余额不足由账本服务抛 409 SUB_ACCOUNT_SCORE_DOWN_INSUFFICIENT（文案：该子帐号积分不足以下分！）。
        if (up != null) {
            agentLedgerService.changeGroupScore(id, up, AgentLedgerService.TYPE_SUB_ACCOUNT_ADJUST,
                    "超管为子账号上分", operator, "SUB-ACCOUNT-UP-" + java.util.UUID.randomUUID());
        }
        if (down != null) {
            agentLedgerService.changeGroupScore(id, down.negate(), AgentLedgerService.TYPE_SUB_ACCOUNT_ADJUST,
                    "超管为子账号下分", operator, "SUB-ACCOUNT-DOWN-" + java.util.UUID.randomUUID());
        }
        // BY220 Users.php:73-78：本次开启统一飞单（feidan == 1）时，把该子账号名下所有 robot
        // 对应的 rbfly.flyers_online 置 0 —— 即名下机器的**上游在线状态重置为离线**，
        // 等下一次飞单心跳/重新登录后再上线。这里映射到 agent.report_online。
        int reportOnlineReset = 0;
        if (input.unifiedReportEnabled()) {
            reportOnlineReset = jdbc.update("""
                    UPDATE agent SET report_online=FALSE, updated_at=CURRENT_TIMESTAMP
                     WHERE group_id=? AND system_owned=FALSE AND deleted_at IS NULL AND report_online=TRUE
                    """, id);
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
        StringBuilder summary = new StringBuilder("username=").append(username);
        if (up != null) {
            summary.append(",up=").append(up.toPlainString());
        }
        if (down != null) {
            summary.append(",down=").append(down.toPlainString());
        }
        if (input.online() != null) {
            summary.append(",online=").append(input.online());
        }
        if (input.unifiedReportEnabled()) {
            summary.append(",reportOnlineReset=").append(reportOnlineReset);
        }
        // 审计只记录"是否更新过飞单凭据"，绝不记录明文或密文本身。
        summary.append(",reportCredentialUpdated=").append(reportPassword != null);
        audit(operator, "SUB_ACCOUNT_MANAGE", "PUT", "/api/admin/sub-accounts/" + id, id, summary.toString());
        return findSubAccount(id);
    }

    @Transactional
    public void changeSubAccountStatus(long id, String status, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        requireSubAccountOwnership(operator, id);
        Long accountUserId = findSubAccountSysUserId(id);
        String normalized = normalizeStatus(status);
        String previousAccountStatus = jdbc.query(
                "SELECT status FROM sys_user WHERE id = ?",
                rs -> rs.next() ? rs.getString("status") : null, accountUserId);
        jdbc.update("UPDATE agent_group SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", normalized, id);
        if (accountUserId != null) {
            userRepository.updateManagedStatus(accountUserId, normalized);
            sessionRepository.revokeAllActiveByUserId(accountUserId, Instant.now());
        }
        audit(operator, "SUB_ACCOUNT_MANAGE", "PATCH", "/api/admin/sub-accounts/" + id + "/status", id,
                "status=" + normalized + (isAccountLocked(previousAccountStatus) ? ",unlockAccount=true" : ""));
    }

    @Transactional(readOnly = true)
    public List<MachineRow> listMachines(long operator) {
        require(operator, "MACHINE_MANAGE");
        List<MachineRow> rows = jdbc.query("""
                SELECT a.id, a.agent_code, a.display_name, u.username account_username, a.account_user_id, a.group_id,
                       g.username group_username, a.score, a.expires_at, a.status, u.status account_status, a.board_open,
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
                rs.getString("status"), rs.getString("account_status"), rs.getBoolean("board_open"), rs.getBoolean("robot_manage"),
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
        applyMachine(input, created.id(), null, effectiveGroupId, operator, true);
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
        applyMachine(input, id, current.groupId(), effectiveGroupId, operator, false);
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
                "status=" + normalized + (isAccountLocked(machine.accountStatus()) ? ",unlockAccount=true" : ""));
    }

    /**
     * 删除机器账号：**物理删除**，连同名下玩家/托、上报订单、积分流水与彩种配置一起清掉（对齐 BY220）。
     *
     * <p>BY220 {@code admin.php} 删除机器是 {@code DELETE x_robot} 并连带 {@code x_rbuser}（玩家/托）、
     * {@code x_record}、{@code x_rbfly}。软删除会永久占用登录名与机器编码并持续干扰列表/报表，
     * 使用者 2026-10-03 已确认改为「完全物理删除、不要保留」，具体清理范围见
     * {@link AccountPurgeService}。删除后同名与机器编码可以立刻重建。</p>
     */
    @Transactional
    public void deleteMachine(long id, long operator) {
        require(operator, "MACHINE_MANAGE");
        accessService.requireMachineAccess(operator, id);
        findMachine(id);
        String agentCode = jdbc.queryForObject("SELECT agent_code FROM agent WHERE id = ?", String.class, id);
        long purgedPlayers = accountPurgeService.purgeMachine(id);
        audit(operator, "MACHINE_MANAGE", "DELETE", "/api/admin/machines/" + id, id,
                "physical delete machine code=" + agentCode + ",purgedPlayers=" + purgedPlayers);
    }

    @Transactional(readOnly = true)
    public List<AgentRepository.AgentPlayerRow> listMachinePlayers(long machineId, long operator) {
        require(operator, "MACHINE_MANAGE");
        accessService.requireMachineAccess(operator, machineId);
        findMachine(machineId);
        return agentRepository.findPlayersByAgent(machineId, null, null, 1, 100);
    }

    /**
     * 删除子账号：**物理删除**，连带其名下所有机器（对齐 BY220 {@code Users.php} 删除子账号会删掉其机器人）。
     *
     * <p>删除后同名可以立刻重建；清理范围见 {@link AccountPurgeService}。</p>
     */
    @Transactional
    public void deleteSubAccount(long id, long operator) {
        require(operator, "SUB_ACCOUNT_MANAGE");
        requireSubAccountOwnership(operator, id);
        findSubAccount(id);
        String groupCode = jdbc.queryForObject(
                "SELECT group_code FROM agent_group WHERE id = ?", String.class, id);
        long purgedMachines = accountPurgeService.purgeSubAccount(id);
        audit(operator, "SUB_ACCOUNT_MANAGE", "DELETE", "/api/admin/sub-accounts/" + id, id,
                "physical delete sub-account code=" + groupCode + ",purgedMachines=" + purgedMachines);
    }

    private void applyMachine(MachineInput input, long id, Long fromGroupId, Long groupId, long operator,
                              boolean create) {
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
        // BY220 语义：子账号是名下机器的上游资金池——建机器与改机器积分都要从子账号划出/退回
        // （见 AgentLedgerService#assignMachineScore）。目标值与当前值相同时不产生账目，天然幂等。
        agentLedgerService.assignMachineScore(id, fromGroupId, groupId, input.score(),
                create ? AgentLedgerService.TYPE_MACHINE_INIT : AgentLedgerService.TYPE_MACHINE_ADJUST,
                create ? "新建机器账号" : "超管维护机器积分", operator);
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

    /** 子账号积分的唯一写入点（账本原则 2、3）。 */
    private void applyGroupScore(long groupId, BigDecimal target, String entryType, long operator) {
        agentLedgerService.setGroupScore(groupId, target == null ? BigDecimal.ZERO : target, entryType,
                "超管维护子账号积分", operator,
                "SUB-ACCOUNT-" + entryType + "-" + java.util.UUID.randomUUID());
    }

    /**
     * 飞单密码（BY220 {@code x_admin.feidanpwd}）的唯一处理点：非空明文即加密，明文不落库、不入日志、不入审计。
     *
     * <p>{@code null} / 空白表示"本次不改动已保存的密文"，与机器报网配置同语义
     * （见 {@code ReportService#passwordCiphertext}）。加密器复用玩家链接的
     * {@link PlayerLinkTokenCipher}（AES-GCM），与 {@code ReportService} 保持同一套实现。</p>
     */
    private String reportPasswordCiphertext(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return null;
        }
        if (rawPassword.length() > MAX_REPORT_PASSWORD_LENGTH) {
            throw BusinessException.badRequest("REPORT_CREDENTIAL_INVALID", "飞单密码长度无效");
        }
        return reportPasswordCipher.encrypt(rawPassword);
    }

    /**
     * 解析新建子账号的批量用户名。
     *
     * <p>BY220 {@code Users.php::add()} 新增分支：{@code $arr = explode(',', $data['UserName'])}，
     * 然后逐个 {@code AdminModel::create($data)} —— 所以 `UserName` 支持逗号分隔批量建号；
     * 空段忽略，请求内重复的名字只建一次（与逐个插入时撞唯一约束的 BY220 结果等价）。</p>
     */
    private static List<String> splitUsernames(String value) {
        String raw = required(value, "子账号用户名不能为空");
        List<String> usernames = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !usernames.contains(trimmed)) {
                usernames.add(trimmed);
            }
        }
        if (usernames.isEmpty()) {
            throw BusinessException.badRequest("PLATFORM_ADMIN_REQUEST_INVALID", "子账号用户名不能为空");
        }
        return usernames;
    }

    /**
     * 生成子账号分组编码。
     *
     * <p>BY220 {@code x_admin} 没有这一列，分组编码是 xupan 的工程字段，按用户名派生；
     * 批量建号时可能出现同名派生（例如用户名里只有一个标点差异），因此撞码时补时间戳与序号。</p>
     */
    private String uniqueGroupCode(String username) {
        String base = "SUB_" + username.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        String code = base;
        int suffix = 1;
        while (exists("SELECT COUNT(*) FROM agent_group WHERE group_code = ?", code)) {
            code = base + "_" + System.currentTimeMillis() + "_" + suffix;
            suffix++;
        }
        return code;
    }

    /**
     * 解析上分/下分参数。
     *
     * <p>BY220 用 {@code !empty($data['up'])} / {@code !empty($data['down'])} 判定，因此 {@code null}
     * 与 {@code 0} 都表示"本次不调整"。BY220 表单没有 min 校验，但账本只接受"方向由字段决定"的正数增减，
     * 负值语义有歧义，一律 400 {@code SUB_ACCOUNT_SCORE_INVALID}。</p>
     *
     * <p>精度口径（计划 §3.5）：修复前这里是 {@code setScale(2, HALF_UP)}，会把 0.001 **静默**吞成
     * 0.00 并被账本当成"本次不改动"；现在改为 {@link MoneyAmounts#requireTwoDecimals}，
     * 超精度一律 400 {@code SUB_ACCOUNT_SCORE_INVALID}「子账号积分最多两位小数」。
     * 注意：这里的 {@code value} 是**调用方传入的原始输入**，不是差额，所以必须严格校验。</p>
     */
    private static BigDecimal adjustAmount(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return null;
        }
        BigDecimal normalized = MoneyAmounts.requireTwoDecimals(value, "SUB_ACCOUNT_SCORE_INVALID",
                "子账号积分不能为负数", "子账号积分最多两位小数");
        if (normalized.signum() < 0) {
            throw BusinessException.badRequest("SUB_ACCOUNT_SCORE_INVALID", "子账号积分不能为负数");
        }
        return normalized;
    }

    /**
     * 统一飞单的报网类型（V73 {@code agent_group.report_type}）校验与归一化。
     *
     * <p><b>BY220 依据</b>：BY220 机器的 {@code x_robot.flyers_type} 由所选网盘的 {@code code} 决定
     * （{@code rbt/controller/Index.php:745}），而网盘表 {@code x_rbwp} 没有类型列
     * （{@code admin/controller/Index.php:42-98}）；本项目把"网盘编码"与"报网类型"拆成两列，
     * 因此在子账号侧显式保存统一飞单的类型，取值集合与机器端
     * {@code agent.report_type}（V56 {@code ck_agent_report_type}、
     * {@link ReportService#REPORT_TYPES}）完全一致，两列才能互相回退。</p>
     *
     * <p>语义：{@code null} / 空串 / 纯空白都表示"子账号未设置"（落 {@code NULL}），
     * 机器读取时回退机器自身的 {@code report_type}；显式的"无报网"是取值 {@code NONE}，
     * 不能与"未设置"混为一谈。不在允许集合内 → 400 {@code SUB_ACCOUNT_REPORT_TYPE_INVALID}。</p>
     */
    private static String resolveReportType(String value) {
        String type = trimToNull(value);
        if (type == null) {
            return null;
        }
        String normalized = type.toUpperCase(Locale.ROOT);
        if (!ReportService.REPORT_TYPES.contains(normalized)) {
            throw BusinessException.badRequest("SUB_ACCOUNT_REPORT_TYPE_INVALID", "报网类型无效");
        }
        return normalized;
    }

    /**
     * 上游地址（V74 {@code agent_group.report_url}，BY220 {@code x_admin.feidanurl}）的校验与归一化。
     *
     * <p><b>BY220 依据</b>：{@code Users.php:112-115}（被注释掉的 subLogin 分支）把 {@code feidanurl}
     * 当作 {@code new K28(['host' => $sub['feidanurl']])} 的上游地址使用；
     * {@code admin/view/sub/form.html} 里没有该字段，BY220 也不对它做任何格式校验 —— 本轮只做
     * 与列宽一致的长度上限，**不做 http/https 前缀校验、不请求、不解析 DNS**（本地模拟边界）。</p>
     *
     * <p>语义：{@code null} / 空串 / 纯空白都表示"未设置"（落 {@code NULL}，与机器端
     * {@code report_url} 的"空串即清空"一致）；长度 &gt; 512 → 400
     * {@code SUB_ACCOUNT_REPORT_URL_INVALID}「上游地址无效」。</p>
     */
    private static String resolveReportUrl(String value) {
        String url = trimToNull(value);
        if (url == null) {
            return null;
        }
        if (url.length() > MAX_REPORT_URL_LENGTH) {
            throw BusinessException.badRequest("SUB_ACCOUNT_REPORT_URL_INVALID", "上游地址无效");
        }
        return url;
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
                       u.status account_status,
                       g.sub_account_manage, g.machine_manage, g.unified_report_enabled,
                       g.report_username, g.report_network_code, g.report_route_code, g.report_type, g.created_at,
                       g.report_url, g.report_online, g.report_balance, g.report_stake,
                       g.online, g.logtime,
                       CASE WHEN g.report_password_ciphertext IS NULL
                                 OR g.report_password_ciphertext = '' THEN FALSE ELSE TRUE END has_report_password,
                       (SELECT COUNT(*) FROM agent a WHERE a.group_id=g.id AND a.system_owned=FALSE AND a.deleted_at IS NULL) machine_count
                  FROM agent_group g
                  LEFT JOIN sys_user u ON u.id=g.sys_user_id
                 WHERE g.id=? AND g.deleted_at IS NULL
                """, (rs, n) -> new SubAccountRow(rs.getLong("id"), rs.getString("group_code"),
                rs.getString("username"), rs.getString("display_name"), rs.getBigDecimal("score"),
                instant(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getString("account_status"),
                rs.getBoolean("sub_account_manage"),
                rs.getBoolean("machine_manage"), rs.getBoolean("unified_report_enabled"),
                rs.getString("report_username"), rs.getString("report_network_code"),
                rs.getString("report_route_code"), rs.getString("report_type"),
                rs.getString("report_url"), rs.getBoolean("report_online"),
                rs.getBigDecimal("report_balance"), rs.getBigDecimal("report_stake"),
                rs.getLong("machine_count"), instant(rs.getTimestamp("created_at")),
                rs.getBoolean("online"), instant(rs.getTimestamp("logtime")),
                rs.getBoolean("has_report_password")), id);
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
                       g.username group_username, a.score, a.expires_at, a.status, u.status account_status, a.board_open, a.robot_manage,
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
                instant(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getString("account_status"),
                rs.getBoolean("board_open"),
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

    /**
     * 登录账号是否处于「锁定」状态。
     *
     * <p>{@code sys_user.status = 'LOCKED'}（且 {@code locked_until} 为空）时 {@code UserAccount#canLogin}
     * 永远返回 false，登录只会提示"用户名或密码错误"，且不会自动解锁；管理端把状态置回 ACTIVE
     * 会顺带清空 {@code locked_until} 与失败次数，这就是「解锁」的实现方式。</p>
     */
    private static boolean isAccountLocked(String accountStatus) {
        return "LOCKED".equals(accountStatus);
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

    /**
     * 子账号写入参数。
     *
     * <p>{@code score} 可空：新建时为空按 BY220 默认 0 落库；编辑时为空表示"本次不改动积分"
     * （BY220 编辑表单里 {@code score} 是 readonly，编辑期改积分走 {@code up}/{@code down}）。
     * {@code up}/{@code down} 是相对当前余额的增减，{@code online} 为空表示不切换在线状态；
     * {@code reportPassword} 是 BY220 {@code x_admin.feidanpwd} 的明文入参，为空表示不改动已存密文。
     * {@code reportType} 是统一飞单的报网类型（V73 {@code agent_group.report_type}），为空/空串表示
     * "子账号未设置"（落 {@code NULL}），非空必须在允许集合内，否则 400
     * {@code SUB_ACCOUNT_REPORT_TYPE_INVALID}。
     * {@code reportUrl} 是上游地址（V74 {@code agent_group.report_url}，BY220 {@code x_admin.feidanurl}），
     * 空串落 {@code NULL}，长度 &gt; 512 → 400 {@code SUB_ACCOUNT_REPORT_URL_INVALID}；**只保存不请求**。
     * {@code reportOnline}/{@code reportBalance}/{@code reportStake} 是 BY220
     * {@code x_admin.feidanonline/feidanmoney/feidanbetting} 的**上游状态读数**：
     * BY220 的子账号表单没有这三个字段，因此本入参**不接受表单设定**（调用方固定传 {@code null}），
     * 它们只由报网登录/退出与列表读取归一化维护，保留在这里只是为了让"上游量不是表单字段"这件事显式可见。</p>
     */
    public record SubAccountInput(String username, String rawPassword, String displayName, BigDecimal score,
                                  Instant expiresAt, boolean subAccountManage, boolean machineManage,
                                  boolean unifiedReportEnabled, String reportUsername,
                                  String reportNetworkCode, String reportRouteCode, String reportType,
                                  String reportUrl, Boolean reportOnline, BigDecimal reportBalance,
                                  BigDecimal reportStake,
                                  BigDecimal up, BigDecimal down, Boolean online, String reportPassword) {}
    /**
     * 子账号行。
     *
     * <p>{@code status} 是 BY220 {@code x_admin.status}（正常/停用）；{@code accountStatus} 是登录账号
     * {@code sys_user.status} 的真实值，用于把「锁定」这种登录态暴露给超管并允许解锁
     * （{@code LOCKED} + {@code locked_until} 为空时不会自动解锁，见 {@code UserAccount#canLogin}）。
     * {@code online}/{@code logtime} 是 BY220 {@code x_admin.online}/{@code x_admin.logtime}：
     * 只表示在线状态与最近登录时间，不是启用/禁用开关。
     * {@code hasReportPassword} 只暴露"是否已设置飞单密码"（BY220 {@code x_admin.feidanpwd}），
     * **绝不返回密文或明文**。
     * {@code reportType} 是统一飞单的报网类型（V73 {@code agent_group.report_type}）：
     * BY220 里由网盘 {@code code} 兼任机器的 {@code x_robot.flyers_type}，本项目显式保存；
     * {@code null} 表示子账号未设置（机器读取时回退机器自身的 {@code agent.report_type}）。
     * {@code reportUrl}/{@code reportOnline}/{@code reportBalance}/{@code reportStake} 是 V74 的
     * 「统一飞单上游状态」，对应 BY220 {@code x_admin.feidanurl / feidanonline / feidanmoney /
     * feidanbetting}（子账号列表的网盘状态/网盘余额/网盘下注三列，见
     * {@code admin/view/sub/list.html:59-61} 与 {@code Users.php:162}）：
     * 它们是**本地模拟的上游读数**（登录/退出/列表归一化维护），不是超管可编辑的表单字段；
     * {@code reportUrl} 只保存不请求。</p>
     */
    public record SubAccountRow(long id, String groupCode, String username, String displayName, BigDecimal score,
                                Instant expiresAt, String status, String accountStatus, boolean subAccountManage,
                                boolean machineManage,
                                boolean unifiedReportEnabled, String reportUsername, String reportNetworkCode,
                                String reportRouteCode, String reportType, String reportUrl, boolean reportOnline,
                                BigDecimal reportBalance, BigDecimal reportStake,
                                long machineCount, Instant createdAt,
                                boolean online, Instant logtime, boolean hasReportPassword) {}
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
    /**
     * 机器行。
     *
     * <p>{@code status} 是 BY220 {@code x_robot.status}（正常/停用）；{@code accountStatus} 是登录账号
     * {@code sys_user.status} 的真实值。两者可能不一致：账号被置为 {@code LOCKED} 且 {@code locked_until}
     * 为空时永远不会自动解锁（{@code UserAccount#canLogin}），表现为"密码明明对却提示用户名或密码错误"，
     * 所以必须把登录态独立暴露出来，超管才能一键解锁。</p>
     */
    public record MachineRow(long id, String code, String displayName, String accountUsername, long accountUserId,
                             Long groupId, String groupUsername, BigDecimal score, Instant expiresAt, String status,
                             String accountStatus,
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



