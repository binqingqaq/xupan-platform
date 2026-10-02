package com.xupan.server.system.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.chat.repository.PlayerPointRequestRepository;
import com.xupan.server.game.domain.WalletOperationResult;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class PlayerPointRequestService {

    private final PlayerPointRequestRepository repository;
    private final VirtualWalletService walletService;
    private final PlayerDeskScopeService scopeService;
    private final OperationAuditRepository auditRepository;

    public PlayerPointRequestService(PlayerPointRequestRepository repository,
                                     VirtualWalletService walletService,
                                     PlayerDeskScopeService scopeService,
                                     OperationAuditRepository auditRepository) {
        this.repository = repository;
        this.walletService = walletService;
        this.scopeService = scopeService;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public List<PlayerPointRequestRepository.Request> pending(long operatorUserId, int limit) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operatorUserId);
        return repository.findPending(limit, scope);
    }

    @Transactional
    public ReviewResult approve(long requestId, long operatorUserId, String reason) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operatorUserId);
        PlayerPointRequestRepository.Request request = requireRequest(requestId, scope);
        if (!"PENDING".equals(request.status())) {
            return new ReviewResult(request, null);
        }
        String reviewReason = normalizeReason(reason, "聊天上下分申请批准");
        String idempotencyKey = "POINT-REQUEST:" + request.id();
        WalletOperationResult operation;
        if ("TOP_UP".equals(request.requestType())) {
            operation = walletService.grant(operatorUserId, request.userId(), request.amount(),
                    reviewReason, idempotencyKey);
        } else if ("DOWN".equals(request.requestType())) {
            operation = walletService.adjust(operatorUserId, request.userId(), request.amount().negate(),
                    reviewReason, idempotencyKey);
        } else {
            throw BusinessException.conflict("POINT_REQUEST_TYPE_INVALID", "积分申请类型无效");
        }
        if (!repository.markReviewed(request.id(), "APPROVED", operatorUserId, reviewReason, Instant.now())) {
            throw new IllegalStateException("积分申请审批状态更新失败");
        }
        auditRepository.record(operatorUserId, "USER_MANAGE", "POST",
                "/api/admin/player-desk/point-requests/" + request.id() + "/approve",
                Long.toString(request.id()), "SUCCESS", null,
                "requestType=" + request.requestType() + ",amount=" + request.amount()
                        + ",ledgerId=" + operation.ledger().id(), null, Instant.now());
        return new ReviewResult(requireRequest(request.id(), scope), operation);
    }

    @Transactional
    public ReviewResult reject(long requestId, long operatorUserId, String reason) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operatorUserId);
        PlayerPointRequestRepository.Request request = requireRequest(requestId, scope);
        if (!"PENDING".equals(request.status())) {
            return new ReviewResult(request, null);
        }
        String reviewReason = normalizeReason(reason, "聊天上下分申请驳回");
        if (!repository.markReviewed(request.id(), "REJECTED", operatorUserId, reviewReason, Instant.now())) {
            throw new IllegalStateException("积分申请审批状态更新失败");
        }
        auditRepository.record(operatorUserId, "USER_MANAGE", "POST",
                "/api/admin/player-desk/point-requests/" + request.id() + "/reject",
                Long.toString(request.id()), "SUCCESS", null,
                "requestType=" + request.requestType() + ",amount=" + request.amount(), null, Instant.now());
        return new ReviewResult(requireRequest(request.id(), scope), null);
    }

    /** 范围外的积分申请按“不存在”处理，避免通过 403 暴露跨机器数据存在性。 */
    private PlayerPointRequestRepository.Request requireRequest(long requestId, PlayerDeskScopeService.Scope scope) {
        return repository.findByIdForUpdate(requestId, scope)
                .orElseThrow(() -> BusinessException.notFound("POINT_REQUEST_NOT_FOUND", "积分申请不存在"));
    }

    private static String normalizeReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason.trim();
        if (value.length() > 255) {
            throw BusinessException.badRequest("POINT_REQUEST_REASON_INVALID", "审批原因不能超过255个字符");
        }
        return value;
    }

    public record ReviewResult(PlayerPointRequestRepository.Request request,
                               WalletOperationResult walletOperation) {
    }
}
