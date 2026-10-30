package com.xupan.server.platformadmin.service;

import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号物理清除：机器、子账号、玩家/托及它们的登录账号与业务数据。
 *
 * <p><b>为什么是物理删除：</b>使用者 2026-10-03 明确确认「完全对齐 BY220、不要保留」。
 * BY220 {@code admin.php} 删除机器是 {@code DELETE x_robot} 并连带 {@code x_rbuser}（玩家/托）、
 * {@code x_record}（单据）、{@code x_rbfly}（上游配置）；删除子账号是 {@code DELETE x_admin}
 * 并连带其名下 {@code x_robot}；「删除全量账号」{@code Gd.php::userDels} 也是直接 DELETE
 * （只保护根管理员与平台直属机器）。软删除留下的账号行会永久占用登录名与机器编码，
 * 并且一直干扰列表和报表，所以这里全部改为物理删除。</p>
 *
 * <p><b>删除顺序：</b>先删引用方再删被引用方。玩家侧：注单改单记录 → 机器人行为与动作 →
 * 余额流水 → 注单 → 玩家账号行 → 登录账号；机器侧：中心机器会话 → 玩家与上报订单 →
 * 机器积分流水 → 机器彩种配置 → 机器行 → 登录账号。顺序错会撞外键。</p>
 *
 * <p><b>唯一保留的两类数据：</b>①{@code sys_operation_log}／{@code sys_login_log} 等审计行本身保留，
 * 只把「操作者／创建人」外键置空（审计是项目基线要求，删了就没有追责依据，且摘要文本里本来就有用户名）；
 * ②公共聊天室的 {@code chat_message} 保留（{@code sender_name} 是发送时快照，删账号不影响已发出的聊天内容）。</p>
 */
@Service
public class AccountPurgeService {

    private final JdbcTemplate jdbc;
    private final CenterUpstreamAccountService centerAccountService;

    public AccountPurgeService(JdbcTemplate jdbc, CenterUpstreamAccountService centerAccountService) {
        this.jdbc = jdbc;
        this.centerAccountService = centerAccountService;
    }

    /** 一条玩家/托账号（{@code demo_user_account.id} 与其登录账号 {@code sys_user.id}，后者可能为空）。 */
    private record PlayerRef(long accountId, Long sysUserId) {}
    private record GroupRef(long id, Long sysUserId) {}

    public record SubAccountPurgeResult(long machines, long players,
                                        CenterUpstreamAccountService.PurgeResult centerAccount) {}

    /**
     * 物理删除一个机器账号：名下的玩家/托、上报订单、机器积分流水、彩种配置、机器行和登录账号。
     *
     * @return 被删除的玩家/托数量
     */
    @Transactional
    public long purgeMachine(long agentId) {
        Long accountUserId = jdbc.query("SELECT account_user_id FROM agent WHERE id = ?",
                rs -> rs.next() ? rs.getObject("account_user_id", Long.class) : null, agentId);
        List<PlayerRef> players = jdbc.query("""
                SELECT id, sys_user_id FROM demo_user_account WHERE agent_id = ?
                """, (rs, n) -> new PlayerRef(rs.getLong("id"), rs.getObject("sys_user_id", Long.class)), agentId);
        jdbc.update("DELETE FROM center_machine_session WHERE machine_agent_id = ?", agentId);
        for (PlayerRef player : players) {
            purgePlayer(player.accountId(), player.sysUserId());
        }
        // 上报订单引用注单与机器，必须先删
        jdbc.update("DELETE FROM report_upstream_order WHERE agent_id = ?", agentId);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id = ?", agentId);
        jdbc.update("DELETE FROM agent_game WHERE agent_id = ?", agentId);
        jdbc.update("DELETE FROM agent WHERE id = ?", agentId);
        if (accountUserId != null && accountUserId > 0) {
            purgeLoginAccount(accountUserId);
        }
        return players.size();
    }

    /**
     * 物理删除一个子账号：连带其名下所有机器（BY220 {@code Users.php} 删除子账号会删掉其机器人）。
     *
     * @return 被删除的机器/玩家数量，以及联动清理的中心账号摘要
     */
    @Transactional
    public SubAccountPurgeResult purgeSubAccount(long groupId) {
        GroupRef group = jdbc.query("SELECT id, sys_user_id FROM agent_group WHERE id = ? FOR UPDATE",
                (rs, n) -> new GroupRef(rs.getLong("id"), rs.getObject("sys_user_id", Long.class)), groupId)
                .stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在"));
        if (hasForeignCenterData(groupId)) {
            throw BusinessException.conflict("CENTER_ACCOUNT_CROSS_GROUP_REFERENCE",
                    "子账号机器或注单存在其他子账号的中心数据引用，请先核对归属");
        }
        CenterUpstreamAccountService.PurgeResult centerAccount =
                centerAccountService.purgeForSubAccountDeletion(groupId).orElse(null);
        List<Long> machineIds = jdbc.queryForList(
                "SELECT id FROM agent WHERE group_id = ?", Long.class, groupId);
        long purgedPlayers = 0;
        for (Long machineId : machineIds) {
            purgedPlayers += purgeMachine(machineId);
        }
        jdbc.update("DELETE FROM agent_ledger WHERE group_id = ?", groupId);
        jdbc.update("DELETE FROM agent_group WHERE id = ?", groupId);
        if (group.sysUserId() != null && group.sysUserId() > 0) {
            purgeLoginAccount(group.sysUserId());
        }
        return new SubAccountPurgeResult(machineIds.size(), purgedPlayers, centerAccount);
    }

    private boolean hasForeignCenterData(long groupId) {
        return exists("""
                SELECT EXISTS(SELECT 1 FROM agent a JOIN center_agent c ON c.id = a.center_agent_id
                 WHERE a.group_id = ? AND (c.agent_group_id IS NULL OR c.agent_group_id <> ?))
                """, groupId, groupId)
                || exists("""
                SELECT EXISTS(SELECT 1 FROM center_machine_session s
                  JOIN agent a ON a.id = s.machine_agent_id
                  JOIN center_agent c ON c.id = s.center_agent_id
                 WHERE a.group_id = ? AND (c.agent_group_id IS NULL OR c.agent_group_id <> ?))
                """, groupId, groupId)
                || exists("""
                SELECT EXISTS(SELECT 1 FROM report_upstream_order r
                  JOIN agent a ON a.id = r.agent_id
                  JOIN center_agent c ON c.id = r.center_agent_id
                 WHERE a.group_id = ? AND (c.agent_group_id IS NULL OR c.agent_group_id <> ?))
                """, groupId, groupId)
                || exists("""
                SELECT EXISTS(SELECT 1 FROM center_order o
                  JOIN center_agent c ON c.id = o.center_agent_id
                  LEFT JOIN agent source ON source.id = o.local_agent_id
                  LEFT JOIN game_bet b ON b.id = o.local_bet_id
                  LEFT JOIN demo_user_account u ON u.id = b.user_id
                  LEFT JOIN agent bet_agent ON bet_agent.id = u.agent_id
                 WHERE (source.group_id = ? OR bet_agent.group_id = ?)
                   AND (c.agent_group_id IS NULL OR c.agent_group_id <> ?))
                """, groupId, groupId, groupId)
                || exists("""
                SELECT EXISTS(SELECT 1 FROM center_reconciliation_disposition d
                  JOIN game_bet b ON b.id = d.bet_id
                  JOIN demo_user_account u ON u.id = b.user_id
                  JOIN agent a ON a.id = u.agent_id
                  JOIN center_agent c ON c.id = d.center_agent_id
                 WHERE a.group_id = ? AND (c.agent_group_id IS NULL OR c.agent_group_id <> ?))
                """, groupId, groupId);
    }

    private boolean exists(String sql, long... parameters) {
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) args[i] = parameters[i];
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    /**
     * 物理删除一个玩家/托：注单、余额流水、行为与动作、链接与偏好、登录账号等全部清掉。
     *
     * <p>使用者确认「有用流水后续再跑就有了」，所以这里连同虚拟积分流水一起删除，
     * 不再保留「不可变流水」——这是明确的范围收缩，已记录在验收记录里。</p>
     */
    @Transactional
    public void purgePlayer(long accountId, Long sysUserId) {
        jdbc.update("""
                DELETE FROM center_reconciliation_disposition
                 WHERE bet_id IN (SELECT id FROM game_bet WHERE user_id = ?)
                """, accountId);
        jdbc.update("DELETE FROM game_bet_edit_record WHERE account_id = ?", accountId);
        jdbc.update("""
                DELETE FROM game_bet_edit_record
                 WHERE bet_id IN (SELECT id FROM game_bet WHERE user_id = ?)
                """, accountId);
        jdbc.update("DELETE FROM test_player_action WHERE account_id = ?", accountId);
        jdbc.update("""
                DELETE FROM test_player_action
                 WHERE bet_id IN (SELECT id FROM game_bet WHERE user_id = ?)
                """, accountId);
        jdbc.update("""
                DELETE FROM demo_balance_ledger
                 WHERE user_id = ?
                    OR related_bet_id IN (SELECT id FROM game_bet WHERE user_id = ?)
                """, accountId, accountId);
        jdbc.update("DELETE FROM game_bet WHERE user_id = ?", accountId);
        jdbc.update("DELETE FROM test_player_behavior WHERE account_id = ?", accountId);
        if (sysUserId != null && sysUserId > 0) {
            jdbc.update("DELETE FROM player_access_link WHERE user_id = ?", sysUserId);
            jdbc.update("DELETE FROM player_link_expiration_config WHERE user_id = ?", sysUserId);
            jdbc.update("DELETE FROM player_name_change_record WHERE user_id = ?", sysUserId);
            jdbc.update("DELETE FROM player_point_request WHERE user_id = ?", sysUserId);
            jdbc.update("DELETE FROM player_quick_bet_preference WHERE user_id = ?", sysUserId);
            jdbc.update("""
                    DELETE FROM player_admin_notice
                     WHERE recipient_user_id = ? OR sender_user_id = ?
                    """, sysUserId, sysUserId);
        }
        jdbc.update("DELETE FROM demo_user_account WHERE id = ?", accountId);
        if (sysUserId != null && sysUserId > 0) {
            purgeLoginAccount(sysUserId);
        }
    }

    /**
     * 物理删除登录账号。
     *
     * <p>先清账号自身的运行态（会话、WS 票据、聊天游标/禁言记录、角色绑定），
     * 再把历史行上的「操作者/创建人」引用置空后删除 {@code sys_user}；
     * 玩家自身的数据如果还引用着这个账号，说明调用顺序错了，直接抛错而不是留下孤儿数据。</p>
     */
    @Transactional
    public void purgeLoginAccount(long userId) {
        // WS 票据的 session_id 外键指向 auth_session，必须先删票据再删会话。
        // 本机 MySQL 实测踩过：先删 auth_session 会被 fk_auth_ws_ticket_session 挡住（H2 用例里该用户没有票据，测不出来）。
        jdbc.update("""
                DELETE FROM auth_ws_ticket
                 WHERE user_id = ?
                    OR session_id IN (SELECT session_id FROM auth_session WHERE user_id = ?)
                """, userId, userId);
        jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_read_cursor WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_user_mute WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        jdbc.update("UPDATE avatar_preset SET assigned_user_id = NULL, assigned_at = NULL, "
                + "updated_at = CURRENT_TIMESTAMP WHERE assigned_user_id = ?", userId);
        for (String[] reference : HISTORY_OPERATOR_REFERENCES) {
            jdbc.update("UPDATE " + reference[0] + " SET " + reference[1] + " = NULL WHERE "
                    + reference[1] + " = ?", userId);
        }
        jdbc.update("""
                UPDATE chat_user_mute SET operator_user_id = NULL WHERE operator_user_id = ?
                """, userId);
        jdbc.update("""
                UPDATE chat_user_mute SET revoked_by = NULL WHERE revoked_by = ?
                """, userId);
        jdbc.update("""
                UPDATE player_admin_notice SET sender_user_id = NULL WHERE sender_user_id = ?
                """, userId);
        // 收件人可以是后台账号（玩家回复管理员），账号被物理删除时这些通知行一起删掉
        jdbc.update("DELETE FROM player_admin_notice WHERE recipient_user_id = ?", userId);
        for (String[] reference : PLAYER_OWNED_REFERENCES) {
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + reference[0] + " WHERE "
                    + reference[1] + " = ?", Long.class, userId);
            if (rows != null && rows > 0) {
                // 抛业务异常而不是 IllegalStateException：把具体表列回给运维，否则只能看到一个笼统的 400
                throw BusinessException.conflict("PURGE_ORDER_INVALID",
                        "删除顺序不正确：登录账号仍被 " + reference[0] + "." + reference[1]
                                + " 引用（userId=" + userId + "，剩余 " + rows + " 行）");
            }
        }
        jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    /**
     * 「删除全量账号」：物理删除除执行者与平台直属主机器之外的所有账号（BY220 {@code Gd.php::userDels}）。
     *
     * <p>顺序：先删子账号及其中心账号、机器、玩家，再删未分组玩家与机器（保留 {@code system_owned}）→
     * 其余登录账号。直属主机器的玩家同样会被删除，与 BY220
     * {@code db('rbuser')->where('id','>',0)->delete()} 一致。</p>
     *
     * @return 被删除的账号与业务实体总数
     */
    @Transactional
    public long purgeEverythingExcept(long operatorUserId) {
        long deleted = 0;
        List<Long> groupIds = jdbc.queryForList("SELECT id FROM agent_group ORDER BY id", Long.class);
        for (Long groupId : groupIds) {
            SubAccountPurgeResult group = purgeSubAccount(groupId);
            deleted += 1 + group.machines() + group.players();
        }
        // 1. 上报订单引用注单，先清空
        deleted += jdbc.update("DELETE FROM report_upstream_order");
        // 2. 全部玩家/托（含历史数据里 sys_user_id 为空的账号行），但绝不动执行者本人：
        //    本机实测踩过——管理员账号同时挂着一行 demo_user_account（早期测试留下），
        //    若不排除就会把执行者自己也删掉，后面的审计写入随即外键失败。
        List<PlayerRef> players = jdbc.query("""
                SELECT id, sys_user_id FROM demo_user_account
                 WHERE sys_user_id IS NULL OR sys_user_id <> ?
                """, (rs, n) -> new PlayerRef(rs.getLong("id"), rs.getObject("sys_user_id", Long.class)),
                operatorUserId);
        for (PlayerRef player : players) {
            purgePlayer(player.accountId(), player.sysUserId());
        }
        deleted += players.size();
        // 3. 全部机器（保留平台直属主机器行）
        List<Long> machineIds = jdbc.queryForList(
                "SELECT id FROM agent WHERE system_owned = FALSE", Long.class);
        for (Long machineId : machineIds) {
            deleted += purgeMachine(machineId);
        }
        deleted += machineIds.size();
        // 5. 其余登录账号（其它管理员、运营等），保留执行者，避免把自己删掉
        List<Long> userIds = jdbc.queryForList(
                "SELECT id FROM sys_user WHERE id <> ?", Long.class, operatorUserId);
        for (Long userId : userIds) {
            purgeLoginAccount(userId);
        }
        deleted += userIds.size();
        return deleted;
    }

    /**
     * 历史行上的「操作者/创建人」引用：账号被物理删除后置空，行本身保留。
     *
     * <p>这些列在 {@code V69} 之前部分为 {@code NOT NULL}，迁移已放开。</p>
     */
    private static final List<String[]> HISTORY_OPERATOR_REFERENCES = List.of(
            new String[]{"agent", "created_by"},
            new String[]{"agent", "deleted_by"},
            new String[]{"agent_group", "created_by"},
            new String[]{"agent_group", "deleted_by"},
            new String[]{"agent_ledger", "operator_user_id"},
            new String[]{"center_agent", "created_by"},
            new String[]{"center_agent_ledger", "operator_user_id"},
            new String[]{"center_order_disposition", "operator_user_id"},
            new String[]{"center_reconciliation_disposition", "operator_user_id"},
            new String[]{"center_reconciliation_disposition", "revoked_by"},
            new String[]{"demo_balance_ledger", "operator_user_id"},
            new String[]{"game_bet_edit_record", "operator_user_id"},
            new String[]{"game_definition", "created_by"},
            new String[]{"game_definition", "updated_by"},
            new String[]{"platform_setting", "updated_by"},
            new String[]{"player_access_link", "created_by"},
            new String[]{"report_network", "created_by"},
            new String[]{"report_network", "updated_by"},
            new String[]{"sys_login_log", "user_id"},
            new String[]{"sys_operation_log", "operator_user_id"});

    /** 玩家自身的数据引用：正常删除顺序下应当已经清空，命中说明顺序错了，直接报错。 */
    private static final List<String[]> PLAYER_OWNED_REFERENCES = List.of(
            new String[]{"demo_user_account", "sys_user_id"},
            new String[]{"player_access_link", "user_id"},
            new String[]{"player_admin_notice", "recipient_user_id"},
            new String[]{"player_link_expiration_config", "user_id"},
            new String[]{"player_name_change_record", "user_id"},
            new String[]{"player_point_request", "user_id"},
            new String[]{"player_quick_bet_preference", "user_id"});
}
