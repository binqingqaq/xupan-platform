package com.xupan.server.auth.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 操作审计查询（账本原则 3：日志不只可写，还要可查）。
 *
 * <p>{@code AUDIT_READ} 此前只声明、无任何校验点，审计只能写不能查；本服务把它落地：
 * 只有持 {@code AUDIT_READ} 的操作者能查，且只返回审计字段本身——摘要里的敏感内容在
 * 写入时已被 {@code LoginAuditRepository.rejectSensitive} 拦掉，查询侧不再回显摘要以外的推测信息。</p>
 */
@Service
public class AuditQueryService {

    private static final String PERMISSION = "AUDIT_READ";
    private static final int MAX_PAGE_SIZE = 100;

    private final OperationAuditRepository repository;
    private final PermissionService permissionService;

    public AuditQueryService(OperationAuditRepository repository, PermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public Page search(long operator, Long operatorUserId, String permissionCode, String result,
                       String keyword, int page, int pageSize) {
        if (!permissionService.hasPermission(operator, PERMISSION)) {
            throw BusinessException.forbidden("AUDIT_READ_FORBIDDEN", "没有查看审计日志的权限");
        }
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("AUDIT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        List<OperationAuditRepository.AuditRow> items = repository.findEntries(operatorUserId, permissionCode,
                result, keyword, page, pageSize);
        return new Page(items, page, pageSize,
                repository.countEntries(operatorUserId, permissionCode, result, keyword));
    }

    /** 一页操作审计。 */
    public record Page(List<OperationAuditRepository.AuditRow> items, int page, int pageSize, long total) {
    }
}
