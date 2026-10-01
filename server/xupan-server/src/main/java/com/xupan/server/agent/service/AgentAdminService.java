package com.xupan.server.agent.service;

import com.xupan.server.agent.domain.Agent;
import com.xupan.server.agent.domain.AgentGroup;
import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AgentAdminService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicy;
    private final PermissionService permissionService;
    private final SessionRepository sessionRepository;
    private final OperationAuditRepository auditRepository;

    public AgentAdminService(AgentRepository agentRepository, UserRepository userRepository,
                             PasswordPolicyService passwordPolicy, PermissionService permissionService,
                             SessionRepository sessionRepository, OperationAuditRepository auditRepository) {
        this.agentRepository = agentRepository;
        this.userRepository = userRepository;
        this.passwordPolicy = passwordPolicy;
        this.permissionService = permissionService;
        this.sessionRepository = sessionRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public List<AgentGroup> listGroups(long operatorUserId) {
        requirePermission(operatorUserId);
        return agentRepository.findGroups();
    }

    @Transactional
    public AgentGroup createGroup(String code, String displayName, long operatorUserId) {
        requirePermission(operatorUserId);
        String normalizedCode = normalizeCode(code, "渠道组编码");
        String normalizedName = required(displayName, "渠道组名称不能为空");
        try {
            long id = agentRepository.createGroup(normalizedCode, normalizedName, operatorUserId);
            audit(operatorUserId, "POST", "/api/admin/agent-groups", Long.toString(id),
                    "code=" + normalizedCode + ",name=" + normalizedName);
            return agentRepository.findGroup(id).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("AGENT_GROUP_CODE_EXISTS", "渠道组编码已存在");
        }
    }

    @Transactional
    public AgentGroup changeGroupStatus(long groupId, String status, long operatorUserId) {
        requirePermission(operatorUserId);
        String normalizedStatus = normalizeStatus(status);
        AgentGroup group = agentRepository.findGroup(groupId)
                .orElseThrow(() -> BusinessException.notFound("AGENT_GROUP_NOT_FOUND", "渠道组不存在"));
        if ("DISABLED".equals(normalizedStatus) && agentRepository.countActiveAgentsInGroup(groupId) > 0) {
            throw BusinessException.conflict("AGENT_GROUP_HAS_ACTIVE_AGENTS", "渠道组下仍有启用代理，不能停用");
        }
        agentRepository.updateGroupStatus(groupId, normalizedStatus);
        audit(operatorUserId, "PATCH", "/api/admin/agent-groups/" + groupId + "/status",
                Long.toString(groupId), "status=" + normalizedStatus);
        return agentRepository.findGroup(groupId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public AgentPage listAgents(String status, String keyword, Long groupId, int page, int pageSize,
                                long operatorUserId) {
        requirePermission(operatorUserId);
        validatePage(page, pageSize);
        String normalizedStatus = status == null || status.isBlank() ? null : normalizeStatus(status);
        String normalizedKeyword = normalizeOptional(keyword);
        long total = agentRepository.countAgents(normalizedStatus, normalizedKeyword, groupId);
        return new AgentPage(agentRepository.findAgents(normalizedStatus, normalizedKeyword, groupId, page, pageSize),
                page, pageSize, total);
    }

    @Transactional
    public AgentRepository.AgentRow createAgent(String agentCode, String username, String displayName,
                                                String rawPassword, Long groupId, long operatorUserId) {
        requirePermission(operatorUserId);
        String normalizedCode = normalizeCode(agentCode, "代理编码").toUpperCase(Locale.ROOT);
        String normalizedUsername = required(username, "代理登录名不能为空");
        String normalizedName = required(displayName, "代理名称不能为空");
        if (groupId != null) {
            AgentGroup group = agentRepository.findGroup(groupId)
                    .orElseThrow(() -> BusinessException.notFound("AGENT_GROUP_NOT_FOUND", "渠道组不存在"));
            if (!"ACTIVE".equals(group.status())) {
                throw BusinessException.conflict("AGENT_GROUP_DISABLED", "渠道组已停用");
            }
        }
        if (agentRepository.agentCodeExists(normalizedCode)) {
            throw BusinessException.conflict("AGENT_CODE_EXISTS", "代理编码已存在");
        }
        if (userRepository.findByUsername(normalizedUsername).isPresent()) {
            throw BusinessException.conflict("USER_USERNAME_EXISTS", "代理登录名已存在");
        }
        String passwordHash;
        try {
            passwordHash = passwordPolicy.encode(rawPassword);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("USER_PASSWORD_INVALID", "密码不符合安全策略");
        }
        try {
            long userId = userRepository.insert(normalizedUsername, normalizedName, passwordHash, "ACTIVE");
            if (userRepository.assignRole(userId, "AGENT") != 1) {
                throw BusinessException.badRequest("AGENT_ROLE_INVALID", "代理角色不存在或已停用");
            }
            long agentId = agentRepository.createAgent(normalizedCode, normalizedName, groupId,
                    userId, operatorUserId);
            audit(operatorUserId, "POST", "/api/admin/agents", Long.toString(agentId),
                    "code=" + normalizedCode + ",username=" + normalizedUsername);
            return agentRepository.findAgent(agentId).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("AGENT_CREATE_CONFLICT", "代理编码或登录名已存在");
        }
    }

    @Transactional
    public AgentRepository.AgentRow changeAgentStatus(long agentId, String status, long operatorUserId) {
        requirePermission(operatorUserId);
        String normalizedStatus = normalizeStatus(status);
        Agent agent = agentRepository.findAgentEntity(agentId)
                .orElseThrow(() -> BusinessException.notFound("AGENT_NOT_FOUND", "代理不存在"));
        if (agent.systemOwned()) {
            throw BusinessException.conflict("AGENT_SYSTEM_PROTECTED", "平台直属代理不能停用");
        }
        agentRepository.updateAgentStatus(agentId, normalizedStatus);
        if (agent.accountUserId() != null) {
            userRepository.updateManagedStatus(agent.accountUserId(), normalizedStatus);
            sessionRepository.revokeAllActiveByUserId(agent.accountUserId(), Instant.now());
        }
        audit(operatorUserId, "PATCH", "/api/admin/agents/" + agentId + "/status",
                Long.toString(agentId), "status=" + normalizedStatus);
        return agentRepository.findAgent(agentId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public AgentPlayerPage listPlayerAssignments(String keyword, Long agentId, int page, int pageSize,
                                                 long operatorUserId) {
        requirePermission(operatorUserId);
        validatePage(page, pageSize);
        if (agentId != null && agentRepository.findAgentEntity(agentId).isEmpty()) {
            throw BusinessException.notFound("AGENT_NOT_FOUND", "代理不存在");
        }
        return new AgentPlayerPage(
                agentRepository.findPlayerAssignments(normalizeOptional(keyword), agentId, page, pageSize),
                page, pageSize, agentRepository.countPlayerAssignments(normalizeOptional(keyword), agentId));
    }

    @Transactional
    public void assignPlatformDirectPlayer(long userId, long targetAgentId, long operatorUserId) {
        requirePermission(operatorUserId);
        long defaultAgentId = agentRepository.defaultAgentId();
        if (targetAgentId == defaultAgentId) {
            throw BusinessException.badRequest("AGENT_ASSIGN_TARGET_INVALID", "请选择普通代理");
        }
        Agent target = agentRepository.findAgentEntity(targetAgentId)
                .orElseThrow(() -> BusinessException.notFound("AGENT_NOT_FOUND", "代理不存在"));
        if (target.systemOwned() || !target.active()) {
            throw BusinessException.conflict("AGENT_NOT_ACTIVE", "目标代理不可用");
        }
        Long currentAgentId = agentRepository.findCurrentAgentId(userId).orElse(null);
        if (currentAgentId == null) {
            throw BusinessException.notFound("PLAYER_ACCOUNT_NOT_FOUND", "玩家账户不存在");
        }
        if (currentAgentId != defaultAgentId) {
            throw BusinessException.conflict("AGENT_PLAYER_TRANSFER_FORBIDDEN", "第一版只允许分配平台直属玩家");
        }
        if (agentRepository.assignPlatformDirectPlayer(userId, targetAgentId) != 1) {
            throw BusinessException.conflict("AGENT_PLAYER_ASSIGN_CONFLICT", "玩家归属已变化，请刷新后重试");
        }
        audit(operatorUserId, "PUT", "/api/admin/agent-player-assignments/" + userId,
                Long.toString(userId), "agentId=" + targetAgentId);
    }

    private void requirePermission(long operatorUserId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, "AGENT_MANAGE")) {
            throw BusinessException.forbidden("AGENT_MANAGE_FORBIDDEN", "没有代理管理权限");
        }
    }

    private void audit(long operatorUserId, String method, String path, String resourceId, String summary) {
        auditRepository.record(operatorUserId, "AGENT_MANAGE", method, path, resourceId,
                "SUCCESS", null, summary, null, Instant.now());
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("AGENT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
    }

    private static String normalizeCode(String value, String label) {
        String normalized = required(value, label + "不能为空");
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw BusinessException.badRequest("AGENT_CODE_INVALID", label + "只能包含字母、数字、下划线和短横线");
        }
        return normalized;
    }

    private static String normalizeStatus(String value) {
        String normalized = required(value, "状态不能为空").toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw BusinessException.badRequest("AGENT_STATUS_INVALID", "代理状态无效");
        }
        return normalized;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest("AGENT_REQUEST_INVALID", message);
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw BusinessException.badRequest("AGENT_REQUEST_INVALID", "字段长度不能超过 128");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    public record AgentPage(List<AgentRepository.AgentRow> items, int page, int pageSize, long total) {
    }

    public record AgentPlayerPage(List<AgentRepository.PlayerAssignmentRow> items, int page, int pageSize,
                                  long total) {
    }
}
