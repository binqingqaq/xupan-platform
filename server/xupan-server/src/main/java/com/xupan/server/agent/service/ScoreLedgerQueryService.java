package com.xupan.server.agent.service;

import com.xupan.server.agent.repository.AgentLedgerRepository;
import com.xupan.server.system.service.PlayerDeskScopeService;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 代理积分流水的读取入口（账本原则 2、3）。
 *
 * <p>写入统一走 {@link AgentLedgerService}；本类只负责按当前操作者的机器范围把流水读出来，
 * 供核对使用（下一阶段中心上游的第二层结算与对账都依赖同一份流水）。</p>
 *
 * <p>范围规则与报网一致：机器身份只能读自己的流水；非机器身份必须显式指定机器。</p>
 */
@Service
public class ScoreLedgerQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PlayerDeskScopeService scopeService;
    private final AgentLedgerRepository ledger;

    public ScoreLedgerQueryService(PlayerDeskScopeService scopeService, AgentLedgerRepository ledger) {
        this.scopeService = scopeService;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public Page page(long operator, Long machineId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("LEDGER_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        long agentId = targetAgentId(scopeService.requireAccess(operator), machineId);
        List<AgentLedgerRepository.LedgerRow> items = ledger.findAgentEntries(agentId, page, pageSize);
        return new Page(agentId, items, page, pageSize, ledger.countAgentEntries(agentId),
                ledger.agentLedgerMatchesBalance(agentId));
    }

    private long targetAgentId(PlayerDeskScopeService.Scope scope, Long machineId) {
        if (scope instanceof PlayerDeskScopeService.Scope.Machine machine) {
            if (machineId != null && machineId != machine.agentId()) {
                throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或不在当前范围内");
            }
            return machine.agentId();
        }
        if (machineId == null) {
            throw BusinessException.badRequest(PlayerDeskScopeService.MACHINE_REQUIRED_CODE, "必须指定机器");
        }
        return scopeService.requireTargetAgentId(scope, machineId);
    }

    /**
     * 一页积分流水。
     *
     * @param balanced 最新一条流水的变动后余额是否等于当前机器积分（对账标志）
     */
    public record Page(long machineId, List<AgentLedgerRepository.LedgerRow> items, int page, int pageSize,
                       long total, boolean balanced) {
    }
}
