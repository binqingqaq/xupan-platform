package com.xupan.server.system.service;

import com.xupan.server.agent.service.AgentLedgerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 玩家删除口径严格对齐 BY220：**物理删除玩家行 + 余额退回机器**，注单与流水历史保留。
 *
 * <p><b>BY220 依据</b>（{@code application/rbt/controller/Index.php:235-243}）：</p>
 * <pre>
 * db('robot')->where('UserName', $user['uid'])->setInc('score', $user['score']);  // ① 余额退回机器
 * db('rbuser')->where('wxid', ...)->where('uid', USER_ID)->delete();              // ② 物理删除玩家行
 * setCount(USER_ID);                                                              // ③ 重算统计
 * </pre>
 *
 * <p>BY220 的注单（{@code x_record}）与资金流水（{@code x_money_log}）里冗余存了玩家与机器
 * （{@code name}/{@code NickName}/{@code uid}），且没有外键，所以删玩家行之后历史仍然完整可读。
 * xupan 有外键，靠 {@code V77} 把「指向玩家行的外键」改为可空 + {@code ON DELETE SET NULL}，
 * 并在删除前把**玩家与机器的快照写进历史行**，从而在保留历史的前提下物理删除
 * （计划 §3.1、§6）。</p>
 *
 * <p><b>快照口径（工头 2026-10-08 裁决②）</b>：快照**只在删除时写入**，读侧一律
 * {@code COALESCE(JOIN 到的实时值, 快照列)} —— 存活玩家走 JOIN、已删玩家走快照。
 * 本说明写在代码里而**不是改 V77**：V77 已在真实 MySQL 应用，改它会变更 Flyway checksum
 * 并破坏已应用的迁移。不做「任何时刻都用纯快照展示」：BY220 也不是这样——它靠
 * {@code x_record} 的冗余列，但那是**写注单时**就写好的；我方按计划 §3.1 只在删除前写入，
 * 未删除玩家的历史行 {@code player_code_snapshot}/{@code player_name_snapshot} 保持 NULL、
 * 由 JOIN 兜底。</p>
 *
 * <p><b>机器/普托快照</b>：{@code agent_id_snapshot}（BY220 {@code x_record.uid}）与
 * {@code player_kind_snapshot}（BY220 {@code isTuo}）在 V77 里对**存量行回填**，并在删除时写入，
 * 使机器范围报表在玩家行已删后仍能把这些历史统计进去（否则报表合计会静默变小）。</p>
 *
 * <p><b>幂等与并发</b>：整个删除在**一个事务**里完成，入口先对玩家账号行
 * {@code SELECT ... FOR UPDATE}；上下分（{@code AgentConsoleService.changeScoreForAgent} →
 * {@code findCurrentAgentIdForUpdate}）与钱包写入都要拿这把锁，因此「删除」与「上下分」并发时
 * 会串行化：先删的那次把余额退进机器池，后到的那次已经查不到账号行，直接按幂等成功返回，
 * **不会二次退款**。另外退回流水使用确定性幂等键
 * {@code PLAYER-DELETE-REFUND-<accountId>}（{@code agent_ledger.idempotency_key} 唯一）做第二道保险。</p>
 *
 * <p><b>与 BY220 的两处明确差异</b>（已在实施报告与缺口表登记）：</p>
 * <ol>
 *   <li>BY220 不记退回流水，我们为审计**多记一条** {@code agent_ledger}
 *       （类型 {@code PLAYER_DELETE_REFUND}，{@code reason} 写明"玩家删除退回余额"）——这是超集；</li>
 *   <li>BY220 删玩家时连带删掉 {@code x_record}/{@code x_money_log} 之外的运行态行，
 *       我们按计划把 {@code test_player_*} 与身份域子行物理删除，而
 *       {@code player_point_request}/{@code player_name_change_record} 与注单/流水同口径保留（引用置空）。</li>
 * </ol>
 *
 * <p>合规：只涉及虚拟积分，不涉及任何真实资金。</p>
 */
@Service
public class PlayerPhysicalDeleteService {

    /** 退回机器池流水的 reason 前缀：写明用途，便于对账与审计检索。 */
    private static final String REFUND_REASON_PREFIX = "玩家删除退回余额";

    private final JdbcTemplate jdbc;
    private final AgentLedgerService agentLedgerService;

    public PlayerPhysicalDeleteService(JdbcTemplate jdbc, AgentLedgerService agentLedgerService) {
        this.jdbc = jdbc;
        this.agentLedgerService = agentLedgerService;
    }

    /** 删除时读到的玩家账号快照（同一事务内已 {@code FOR UPDATE} 锁住）。 */
    public record AccountSnapshot(long accountId, long sysUserId, String memberCode, String displayName,
                                  BigDecimal balance, Long agentId, String playerKind) {
    }

    /** 删除结果：BY220 只返回 {@code 0}（成功标志），这里额外回传审计需要的金额信息。 */
    public record Outcome(long userId, String memberCode, String playerName, BigDecimal refundedAmount,
                          Long machineId, boolean alreadyDeleted) {

        static Outcome alreadyDeleted(long userId) {
            return new Outcome(userId, null, null, BigDecimal.ZERO.setScale(2), null, true);
        }
    }

    /**
     * 玩家是否**已被物理删除**（账号行与登录账号都不存在）。
     *
     * <p>用于把「重复删除」（幂等成功）与「不是玩家 / 不在自己范围内」（404）区分开，
     * 避免第二次删除返回 404 破坏幂等。</p>
     */
    @Transactional(readOnly = true)
    public boolean isPurged(long userId) {
        Long accounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id = ?", Long.class, userId);
        if (accounts != null && accounts > 0) {
            return false;
        }
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id = ?", Long.class, userId);
        return users == null || users == 0;
    }

    /**
     * 物理删除一个玩家/托：退余额 → 写快照 → 清运行态 → 置空历史引用 → 删账号行 → 删登录账号。
     *
     * @return 删除结果；{@code alreadyDeleted=true} 表示这次调用没有删除任何东西（幂等重放）
     */
    @Transactional
    public Outcome delete(long userId, long operatorUserId) {
        AccountSnapshot account = lockAccount(userId);
        if (account == null) {
            // 已经被删掉（重复删除）：幂等成功，且**绝不二次退款**
            return Outcome.alreadyDeleted(userId);
        }

        // ① 先把「删除时的玩家与机器」落进历史行。必须在任何 DELETE 之前：
        //    外键一旦 SET NULL，就再也查不到这位玩家是谁（计划 §6 点名的坑）。
        writeHistorySnapshots(account);

        // ② 随身份消失的运行态行（会话、票据、角色绑定、链接与偏好、聊天游标/禁言、托行为）
        purgeIdentityRows(userId, account.accountId());

        // ③ 业务历史行上的引用置空保留（审计与登录日志是项目审计基线，只置空不删除）
        nullHistoryReferences(userId);

        // ④ 余额退回玩家归属机器（BY220 Index.php:239），并写机器账本流水
        BigDecimal refunded = refundBalanceToMachine(account, operatorUserId);

        // ⑤ 物理删除账号行与登录账号：
        //    demo_user_account 删除会让 game_bet / demo_balance_ledger / game_bet_edit_record
        //    的玩家引用按外键 SET NULL；sys_user 删除会让 player_point_request /
        //    player_name_change_record 的引用按外键 SET NULL。
        jdbc.update("DELETE FROM demo_user_account WHERE id = ?", account.accountId());
        jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);

        return new Outcome(userId, account.memberCode(), account.displayName(), refunded,
                account.agentId(), false);
    }

    /**
     * 锁定并读取玩家账号行。
     *
     * <p>这把锁与上下分/钱包写入的锁是同一把（都是 {@code demo_user_account} 的同一行），
     * 保证「删除」与「上下分」并发时只退一次。</p>
     */
    AccountSnapshot lockAccount(long userId) {
        return jdbc.query("""
                SELECT a.id AS account_id, a.sys_user_id, a.member_code, a.display_name, a.balance,
                       a.agent_id, a.player_kind
                  FROM demo_user_account a
                 WHERE a.sys_user_id = ?
                   FOR UPDATE
                """, (rs, rowNum) -> new AccountSnapshot(
                rs.getLong("account_id"), rs.getLong("sys_user_id"), rs.getString("member_code"),
                rs.getString("display_name"), rs.getBigDecimal("balance"),
                rs.getObject("agent_id", Long.class), rs.getString("player_kind")), userId)
                .stream().findFirst().orElse(null);
    }

    /** 把玩家编号/昵称/机器归属写进该玩家的全部历史行（空值不覆盖既有快照）。 */
    void writeHistorySnapshots(AccountSnapshot account) {
        String code = account.memberCode();
        String name = account.displayName();
        long accountId = account.accountId();

        jdbc.update("""
                UPDATE game_bet
                   SET player_code_snapshot = COALESCE(player_code_snapshot, ?),
                       player_name_snapshot = COALESCE(player_name_snapshot, ?)
                 WHERE user_id = ?
                """, code, name, accountId);
        jdbc.update("""
                UPDATE demo_balance_ledger
                   SET player_code_snapshot = COALESCE(player_code_snapshot, ?),
                       player_name_snapshot = COALESCE(player_name_snapshot, ?)
                 WHERE user_id = ?
                """, code, name, accountId);
        jdbc.update("""
                UPDATE game_bet_edit_record
                   SET player_code_snapshot = COALESCE(player_code_snapshot, ?),
                       player_name_snapshot = COALESCE(player_name_snapshot, ?)
                 WHERE account_id = ?
                """, code, name, accountId);
        jdbc.update("""
                UPDATE player_point_request
                   SET player_code_snapshot = COALESCE(player_code_snapshot, ?),
                       player_name_snapshot = COALESCE(player_name_snapshot, ?)
                 WHERE user_id = ?
                """, code, name, account.sysUserId());
        jdbc.update("""
                UPDATE player_name_change_record
                   SET player_code_snapshot = COALESCE(player_code_snapshot, ?)
                 WHERE user_id = ?
                """, code, account.sysUserId());

        // 机器归属与玩家分类快照单独处理：null 不进参数（避免驱动对 null 的 BIGINT 推断差异），
        // 且玩家未归属机器时本来就应保持 NULL。
        if (account.agentId() != null) {
            long agentId = account.agentId();
            jdbc.update("UPDATE game_bet SET agent_id_snapshot = COALESCE(agent_id_snapshot, ?) WHERE user_id = ?",
                    agentId, accountId);
            jdbc.update("UPDATE demo_balance_ledger SET agent_id_snapshot = COALESCE(agent_id_snapshot, ?) WHERE user_id = ?",
                    agentId, accountId);
            jdbc.update("UPDATE game_bet_edit_record SET agent_id_snapshot = COALESCE(agent_id_snapshot, ?) WHERE account_id = ?",
                    agentId, accountId);
        }
        if (account.playerKind() != null) {
            String playerKind = account.playerKind();
            jdbc.update("UPDATE game_bet SET player_kind_snapshot = COALESCE(player_kind_snapshot, ?) WHERE user_id = ?",
                    playerKind, accountId);
            jdbc.update("UPDATE demo_balance_ledger SET player_kind_snapshot = COALESCE(player_kind_snapshot, ?) WHERE user_id = ?",
                    playerKind, accountId);
        }
    }

    /**
     * 物理删除「应当随身份消失」的行。
     *
     * <p>顺序照 {@code AccountPurgeService#purgeLoginAccount} 已验证过的纪律：
     * WS 票据的 {@code session_id} 外键指向 {@code auth_session}，必须先删票据再删会话；
     * 托动作引用托行为，必须先删动作再删行为。</p>
     */
    void purgeIdentityRows(long userId, long accountId) {
        jdbc.update("""
                DELETE FROM auth_ws_ticket
                 WHERE user_id = ?
                    OR session_id IN (SELECT session_id FROM auth_session WHERE user_id = ?)
                """, userId, userId);
        jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM player_access_link WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM player_link_expiration_config WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM player_quick_bet_preference WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_read_cursor WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_user_mute WHERE user_id = ?", userId);
        // 玩家若曾是他人的禁言操作者，行本身属于对方的历史：只置空操作者，不删行。
        jdbc.update("UPDATE chat_user_mute SET operator_user_id = NULL WHERE operator_user_id = ?", userId);
        jdbc.update("UPDATE chat_user_mute SET revoked_by = NULL WHERE revoked_by = ?", userId);

        // 管理员私信：玩家是收件人的行属于玩家自身数据，随身份删除；
        // 玩家作为发件人的行属于对方（管理员/玩家）的收件历史，只置空发件人。
        jdbc.update("DELETE FROM player_admin_notice WHERE recipient_user_id = ?", userId);
        jdbc.update("UPDATE player_admin_notice SET sender_user_id = NULL WHERE sender_user_id = ?", userId);

        // 托行为与动作：属于玩家自身的运行态配置（不是业务历史）
        jdbc.update("DELETE FROM test_player_action WHERE user_id = ? OR account_id = ?", userId, accountId);
        jdbc.update("""
                DELETE FROM test_player_behavior_play_type
                 WHERE behavior_id IN (SELECT id FROM test_player_behavior WHERE account_id = ?)
                """, accountId);
        jdbc.update("DELETE FROM test_player_behavior WHERE account_id = ?", accountId);
    }

    /**
     * 历史行上的账号引用置空保留。
     *
     * <p>项目审计基线（沿用 {@code V69}／{@code AccountPurgeService} 的既定口径）：
     * 审计与登录日志**本身保留**，只把操作者/主体置空——删了就没有追责依据，
     * 且摘要文本里本来就有用户名。注单/流水/审批/换名记录则由 {@code V77} 的外键
     * {@code ON DELETE SET NULL} 自动收敛。</p>
     */
    void nullHistoryReferences(long userId) {
        jdbc.update("UPDATE sys_login_log SET user_id = NULL WHERE user_id = ?", userId);
        jdbc.update("UPDATE sys_operation_log SET operator_user_id = NULL WHERE operator_user_id = ?", userId);
        jdbc.update("UPDATE demo_balance_ledger SET operator_user_id = NULL WHERE operator_user_id = ?", userId);
        jdbc.update("UPDATE game_bet_edit_record SET operator_user_id = NULL WHERE operator_user_id = ?", userId);
        // 玩家一般不会是机器账本的操作者；真出现时按同一审计口径只置空操作者、保留流水行，
        // 而不是让外键把整个删除卡死（V69 已确立"审计行保留、操作者置空"的项目基线）。
        jdbc.update("UPDATE agent_ledger SET operator_user_id = NULL WHERE operator_user_id = ?", userId);
    }

    /**
     * 把删除时的玩家余额退回**玩家归属机器**（BY220 {@code Index.php:239}）。
     *
     * <p>金额为 0 或玩家没有归属机器时不写流水、不报错（没有变动就没有账目；
     * {@code agent_ledger} 本身有 {@code amount > 0} 约束）。BY220 不记这条流水，
     * 我们为审计记一条，{@code reason} 写明「玩家删除退回余额」。</p>
     */
    BigDecimal refundBalanceToMachine(AccountSnapshot account, long operatorUserId) {
        BigDecimal balance = account.balance() == null ? BigDecimal.ZERO : account.balance();
        if (balance.signum() <= 0 || account.agentId() == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        String reason = REFUND_REASON_PREFIX + "：" + account.displayName();
        agentLedgerService.changeMachineScore(account.agentId(), balance,
                AgentLedgerService.TYPE_PLAYER_DELETE_REFUND, reason, "PLAYER",
                Long.toString(account.sysUserId()), operatorUserId,
                "PLAYER-DELETE-REFUND-" + account.accountId());
        return balance;
    }
}
