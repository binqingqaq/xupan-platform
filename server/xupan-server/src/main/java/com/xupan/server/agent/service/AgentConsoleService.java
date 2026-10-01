package com.xupan.server.agent.service;

import com.xupan.server.agent.domain.Agent;
import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentConsoleService {

    private final AgentRepository agentRepository;
    private final PermissionService permissionService;

    public AgentConsoleService(AgentRepository agentRepository, PermissionService permissionService) {
        this.agentRepository = agentRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public AgentRepository.AgentRow overview(long accountUserId) {
        requireConsole(accountUserId);
        Agent agent = agentRepository.findAgentByAccountUserId(accountUserId)
                .orElseThrow(() -> BusinessException.forbidden("AGENT_PROFILE_MISSING", "当前账号没有代理资料"));
        if (!agent.active()) {
            throw BusinessException.forbidden("AGENT_DISABLED", "代理已停用");
        }
        return agentRepository.findAgent(agent.id())
                .orElseThrow(() -> BusinessException.notFound("AGENT_NOT_FOUND", "代理不存在"));
    }

    @Transactional(readOnly = true)
    public AgentPlayerPage listPlayers(long accountUserId, String kind, String keyword,
                                       int page, int pageSize) {
        AgentRepository.AgentRow agent = overview(accountUserId);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("AGENT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        String normalizedKind = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase();
        if (normalizedKind != null && !"NORMAL".equals(normalizedKind) && !"BOT".equals(normalizedKind)) {
            throw BusinessException.badRequest("AGENT_PLAYER_KIND_INVALID", "玩家分类无效");
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return new AgentPlayerPage(
                agentRepository.findPlayersByAgent(agent.id(), normalizedKind, normalizedKeyword, page, pageSize),
                page, pageSize,
                agentRepository.countPlayersByAgent(agent.id(), normalizedKind, normalizedKeyword));
    }

    private void requireConsole(long accountUserId) {
        if (accountUserId <= 0 || !permissionService.hasPermission(accountUserId, "AGENT_CONSOLE_READ")) {
            throw BusinessException.forbidden("AGENT_CONSOLE_FORBIDDEN", "没有代理后台访问权限");
        }
    }

    public record AgentPlayerPage(List<AgentRepository.AgentPlayerRow> items, int page, int pageSize, long total) {
    }
}
