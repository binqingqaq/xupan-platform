package com.xupan.server.system.service;

import com.xupan.server.agent.domain.Agent;
import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 解析运营后台（{@code /console}，BY220 {@code robot.php}）的登录身份与可见范围。
 *
 * <p>范围按优先级解析：平台管理员（{@code USER_MANAGE}）为全局；子账号（{@code agent_group.sys_user_id}）
 * 为自己分组下的机器与玩家；机器账号（{@code agent.account_user_id}）只含自己名下的玩家与配置。</p>
 *
 * <p>范围条件由 {@link Scope#agentIdCondition()} 提供，假定 SQL 中 {@code a} 为
 * {@code demo_user_account} 别名，所有使用该条件的仓储都必须遵守这一约定。</p>
 */
@Service
public class PlayerDeskScopeService {

    /** 无任何运营后台身份时使用的稳定错误码。 */
    public static final String FORBIDDEN_CODE = "PLAYER_DESK_FORBIDDEN";
    public static final String FORBIDDEN_MESSAGE = "没有运营后台访问权限";
    /** 子账号创建玩家/托未指定机器时的稳定错误码。 */
    public static final String MACHINE_REQUIRED_CODE = "PLAYER_DESK_MACHINE_REQUIRED";
    /** 指定机器不存在、已停用或不属于当前分组时的稳定错误码。 */
    public static final String MACHINE_INVALID_CODE = "PLAYER_DESK_MACHINE_INVALID";

    private final PermissionService permissionService;
    private final AgentRepository agentRepository;
    private final JdbcTemplate jdbc;

    public PlayerDeskScopeService(PermissionService permissionService, AgentRepository agentRepository,
                                  JdbcTemplate jdbc) {
        this.permissionService = permissionService;
        this.agentRepository = agentRepository;
        this.jdbc = jdbc;
    }

    /**
     * 运营后台登录身份与数据范围。
     *
     * <p>sealed 层级保证服务层用 switch/instanceof 穷举三种身份，不会出现"未处理的范围"。</p>
     */
    public sealed interface Scope permits Scope.Global, Scope.Group, Scope.Machine {

        String mode();

        /** 追加在 {@code demo_user_account a} 之后的代理范围条件；GLOBAL 不追加任何条件。 */
        default String agentIdCondition() {
            return agentIdCondition("a");
        }

        /** 同 {@link #agentIdCondition()}，但显式指定 {@code demo_user_account} 的 SQL 别名。 */
        default String agentIdCondition(String alias) {
            return "";
        }

        /** {@link #agentIdCondition()} 中占位符对应的参数，顺序一致。 */
        default List<Object> agentIdParameters() {
            return List.of();
        }

        default boolean global() {
            return this instanceof Global;
        }

        /** 平台管理员：全局范围。 */
        record Global() implements Scope {
            public static final Global INSTANCE = new Global();

            @Override
            public String mode() {
                return "GLOBAL";
            }
        }

        /** 子账号：自己 {@code agent_group} 下的机器与其玩家。 */
        record Group(long groupId, String groupName) implements Scope {
            @Override
            public String mode() {
                return "GROUP";
            }

            @Override
            public String agentIdCondition(String alias) {
                return " AND " + alias + ".agent_id IN (SELECT id FROM agent WHERE group_id = ? AND deleted_at IS NULL)";
            }

            @Override
            public List<Object> agentIdParameters() {
                return List.of(groupId);
            }
        }

        /** 机器账号：只含 {@code agent_id = 自己} 的玩家与配置。 */
        record Machine(long agentId, long accountId, String agentCode, String agentName, BigDecimal score,
                       long botLimit, long playerCount, long botCount, Instant expiresAt, Long groupId, String groupName,
                       String status) implements Scope {
            @Override
            public String mode() {
                return "MACHINE";
            }

            @Override
            public String agentIdCondition(String alias) {
                return " AND " + alias + ".agent_id = ?";
            }

            @Override
            public List<Object> agentIdParameters() {
                return List.of(agentId);
            }
        }
    }

    /**
     * 供服务层使用：解析范围，无权限时抛出稳定的 403 业务错误。
     *
     * <p>当前事实：子账号（{@code agent_group.sys_user_id}）的 {@code AGENT_CONSOLE_READ}
     * 已在 V76 被撤销（/console 严格对齐 BY220 robot.php，只接受机器账号），
     * 子账号走平台后台的玩家工作台，靠 {@code AGENT_PLAYER_MANAGE} 进入
     * {@code /api/admin/player-desk/**}；这里的范围解析只负责 GLOBAL / GROUP / MACHINE 的归属判定。</p>
     */
    @Transactional(readOnly = true)
    public Scope requireAccess(long operatorUserId) {
        return resolve(operatorUserId);
    }

    /** 仅平台管理员可用的全局范围；机器账号/子账号一律 403。 */
    @Transactional(readOnly = true)
    public Scope requireGlobal(long operatorUserId) {
        Scope scope = resolve(operatorUserId);
        if (!scope.global()) {
            throw BusinessException.forbidden(FORBIDDEN_CODE, FORBIDDEN_MESSAGE);
        }
        return scope;
    }

    @Transactional(readOnly = true)
    public Scope resolve(long operatorUserId) {
        if (operatorUserId <= 0) {
            throw BusinessException.forbidden(FORBIDDEN_CODE, FORBIDDEN_MESSAGE);
        }
        if (permissionService.hasPermission(operatorUserId, "USER_MANAGE")) {
            return Scope.Global.INSTANCE;
        }
        List<Scope.Group> groups = jdbc.query("""
                SELECT id, display_name
                  FROM agent_group
                 WHERE sys_user_id = ? AND deleted_at IS NULL
                 ORDER BY id
                """, (rs, rowNum) -> new Scope.Group(rs.getLong("id"), rs.getString("display_name")),
                operatorUserId);
        if (!groups.isEmpty()) {
            return groups.get(0);
        }
        Agent agent = agentRepository.findAgentByAccountUserId(operatorUserId)
                .filter(Agent::active)
                .orElse(null);
        if (agent != null) {
            return requireMachine(agent.id(), operatorUserId);
        }
        throw BusinessException.forbidden(FORBIDDEN_CODE, FORBIDDEN_MESSAGE);
    }

    /**
     * 解析创建玩家/托时的目标机器。
     *
     * <ul>
     *   <li>MACHINE：忽略传入值，强制归属自己；</li>
     *   <li>GROUP：必须传 {@code machineId} 且机器属于本分组，否则 400；</li>
     *   <li>GLOBAL：可选，未传时保持现状（平台直属代理 {@code PLATFORM_DIRECT}）。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public long requireTargetAgentId(Scope scope, Long machineId) {
        if (scope instanceof Scope.Machine machine) {
            return machine.agentId();
        }
        if (scope instanceof Scope.Group group) {
            if (machineId == null) {
                throw BusinessException.badRequest(MACHINE_REQUIRED_CODE, "子账号创建玩家或托必须指定机器");
            }
            return requireActiveAgent(machineId, group.groupId()).id();
        }
        if (machineId == null) {
            return agentRepository.defaultAgentId();
        }
        return requireActiveAgent(machineId, null).id();
    }

    private Agent requireActiveAgent(long machineId, Long groupId) {
        Agent agent = agentRepository.findAgentEntity(machineId)
                .filter(Agent::active)
                .orElseThrow(() -> BusinessException.badRequest(MACHINE_INVALID_CODE, "机器不存在或已停用"));
        if (groupId != null && !groupId.equals(agent.groupId())) {
            throw BusinessException.badRequest(MACHINE_INVALID_CODE, "机器不属于当前子账号分组");
        }
        return agent;
    }

    private Scope.Machine requireMachine(long agentId, long accountUserId) {
        List<Scope.Machine> rows = jdbc.query("""
                SELECT ag.id, ag.account_user_id, ag.agent_code, ag.display_name, ag.score, ag.bot_count, ag.expires_at,
                       ag.group_id, g.display_name AS group_display_name, ag.status,
                       (SELECT COUNT(*) FROM demo_user_account a
                         WHERE a.agent_id = ag.id AND a.player_kind = 'NORMAL' AND a.status <> 'DELETED'
                           AND a.identity_type IN ('REAL', 'TEST')) AS player_count,
                       (SELECT COUNT(*) FROM demo_user_account a
                         WHERE a.agent_id = ag.id AND a.player_kind = 'BOT' AND a.status <> 'DELETED'
                           AND a.identity_type IN ('REAL', 'TEST')) AS bot_count_current
                  FROM agent ag
                  LEFT JOIN agent_group g ON g.id = ag.group_id AND g.deleted_at IS NULL
                 WHERE ag.id = ? AND ag.deleted_at IS NULL AND ag.status = 'ACTIVE'
                """, (rs, rowNum) -> new Scope.Machine(rs.getLong("id"), accountUserId,
                rs.getString("agent_code"), rs.getString("display_name"), money(rs.getBigDecimal("score")),
                rs.getLong("bot_count"), rs.getLong("player_count"), rs.getLong("bot_count_current"),
                instant(rs.getTimestamp("expires_at")),
                nullableLong(rs, "group_id"), rs.getString("group_display_name"),
                rs.getString("status")), agentId);
        return rows.stream().findFirst()
                .orElseThrow(() -> BusinessException.forbidden(FORBIDDEN_CODE, FORBIDDEN_MESSAGE));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
