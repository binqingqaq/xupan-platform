package com.xupan.server.system.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.system.repository.PlayerPointOperationRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerPointOperationServiceTest {

    @Mock
    private PlayerPointOperationRepository repository;

    @Mock
    private PermissionService permissionService;

    @Test
    void mapsDirectionsAndReturnsCursorWhenMoreRowsExist() {
        when(permissionService.hasPermission(7L, "USER_MANAGE")).thenReturn(true);
        Instant createdAt = Instant.parse("2026-09-23T08:00:00Z");
        when(repository.findRecent(eq("NORMAL"), any(Instant.class), any(Instant.class), isNull(), eq(3)))
                .thenReturn(List.of(
                        operation(31L, 100, "100.00", createdAt),
                        operation(30L, -25, "75.00", createdAt.minusSeconds(60)),
                        operation(29L, 5, "50.00", createdAt.minusSeconds(120))
                ));

        PlayerPointOperationService.Recent recent = new PlayerPointOperationService(
                repository, permissionService).recent("normal", null, 2, 7L);

        assertThat(recent.items()).hasSize(2);
        assertThat(recent.items().get(0).direction()).isEqualTo("TOP_UP");
        assertThat(recent.items().get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(recent.items().get(1).direction()).isEqualTo("DOWN");
        assertThat(recent.items().get(1).amount()).isEqualByComparingTo("-25.00");
        assertThat(recent.nextBeforeId()).isEqualTo(30L);
        assertThat(recent.hasMore()).isTrue();
    }

    @Test
    void rejectsInvalidKindCursorLimitAndMissingPermission() {
        when(permissionService.hasPermission(7L, "USER_MANAGE")).thenReturn(true);
        PlayerPointOperationService service = new PlayerPointOperationService(repository, permissionService);

        assertThatThrownBy(() -> service.recent("ALL", null, 50, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("POINT_OPERATION_KIND_INVALID");
        assertThatThrownBy(() -> service.recent("NORMAL", 0L, 50, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("POINT_OPERATION_CURSOR_INVALID");
        assertThatThrownBy(() -> service.recent("NORMAL", null, 201, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("POINT_OPERATION_LIMIT_INVALID");
        assertThatThrownBy(() -> service.recent("NORMAL", null, 50, 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("USER_OPERATION_FORBIDDEN");
    }

    private static PlayerPointOperationRepository.Operation operation(long id, long amount, String balanceAfter,
                                                                      Instant createdAt) {
        return new PlayerPointOperationRepository.Operation(id, 90L, "v1001", "测试玩家",
                "NORMAL", amount > 0 ? "ADMIN_GRANT" : "ADMIN_ADJUST",
                new BigDecimal(amount).setScale(2), new BigDecimal(balanceAfter).setScale(2),
                "测试上下分", createdAt);
    }
}
