package com.xupan.server.system.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.system.repository.PlayerPointOperationRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class PlayerPointOperationService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final PlayerPointOperationRepository repository;
    private final PermissionService permissionService;

    public PlayerPointOperationService(PlayerPointOperationRepository repository,
                                       PermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public Recent recent(String kind, Long beforeId, int limit, long operatorUserId) {
        requireAdmin(operatorUserId);
        String playerKind = normalizeKind(kind);
        if (beforeId != null && beforeId <= 0) {
            throw BusinessException.badRequest("POINT_OPERATION_CURSOR_INVALID", "记录游标无效");
        }
        int safeLimit = normalizeLimit(limit);
        PlayerPointsReportService.BusinessDay businessDay = PlayerPointsReportService.resolveBusinessDay(null);
        List<PlayerPointOperationRepository.Operation> rows = repository.findRecent(
                playerKind, businessDay.fromInclusive(), businessDay.toExclusive(), beforeId, safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<PlayerPointOperationRepository.Operation> visible = hasMore
                ? List.copyOf(rows.subList(0, safeLimit))
                : List.copyOf(rows);
        Long nextBeforeId = hasMore && !visible.isEmpty() ? visible.get(visible.size() - 1).ledgerId() : null;
        return new Recent(playerKind, businessDay.businessDate(), businessDay.fromInclusive(),
                businessDay.toExclusive(), visible.stream().map(Item::from).toList(), nextBeforeId, hasMore);
    }

    private void requireAdmin(long operatorUserId) {
        if (operatorUserId <= 0 || !permissionService.hasPermission(operatorUserId, "USER_MANAGE")) {
            throw BusinessException.forbidden("USER_OPERATION_FORBIDDEN", "没有用户管理权限");
        }
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!"NORMAL".equals(value) && !"BOT".equals(value)) {
            throw BusinessException.badRequest("POINT_OPERATION_KIND_INVALID", "玩家类型必须是 NORMAL 或 BOT");
        }
        return value;
    }

    private static int normalizeLimit(int limit) {
        int value = limit == 0 ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > MAX_LIMIT) {
            throw BusinessException.badRequest("POINT_OPERATION_LIMIT_INVALID", "记录数量必须在 1 到 200 之间");
        }
        return value;
    }

    public record Recent(String kind, LocalDate businessDate, Instant fromInclusive, Instant toExclusive,
                         List<Item> items, Long nextBeforeId, boolean hasMore) {
    }

    public record Item(long ledgerId, long userId, String memberCode, String displayName,
                       String playerKind, String operationType, String direction,
                       BigDecimal amount, BigDecimal balanceAfter, String reason, Instant createdAt) {
        static Item from(PlayerPointOperationRepository.Operation operation) {
            String direction = operation.amount().signum() > 0 ? "TOP_UP" : "DOWN";
            return new Item(operation.ledgerId(), operation.userId(), operation.memberCode(),
                    operation.displayName(), operation.playerKind(), operation.operationType(),
                    direction, operation.amount(), operation.balanceAfter(), operation.reason(),
                    operation.createdAt());
        }
    }
}
