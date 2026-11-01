package com.xupan.server.displayobserver;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisplayObserverBindingServiceTest {

    private static final long ADMIN_ID = 3L;
    private static final long MACHINE_ID = 9L;
    private static final long VIEWER_ID = 41L;
    private static final long GROUP_ID = 6L;
    private static final long ACCOUNT_USER_ID = 70L;
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_-]{16}");

    private final DisplayObserverBindingRepository repository = mock(DisplayObserverBindingRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final DisplayObserverBindingService service =
            new DisplayObserverBindingService(repository, permissionService);

    @BeforeEach
    void allowPlatformAdmin() {
        when(permissionService.findRoleCodes(ADMIN_ID)).thenReturn(List.of("ADMIN"));
        when(permissionService.hasPermission(eq(ADMIN_ID), anyString())).thenReturn(true);
    }

    @Test
    void firstBindCreatesAnEnabledSingletonAndRandomPublicPathId() {
        DisplayObserverBindingRepository.TargetSnapshot target = target(MACHINE_ID, VIEWER_ID);
        when(repository.lockEligibleTarget(MACHINE_ID, VIEWER_ID)).thenReturn(Optional.of(target));
        when(repository.findDetails()).thenReturn(Optional.of(details(
                MACHINE_ID, VIEWER_ID, true, 1, "Abcdef0123456789", GROUP_ID)));
        when(repository.isValidBinding(MACHINE_ID, VIEWER_ID, GROUP_ID)).thenReturn(true);

        DisplayObserverBindingService.BindingView result = service.bind(MACHINE_ID, VIEWER_ID, 0, ADMIN_ID);

        assertThat(result.configured()).isTrue();
        assertThat(result.enabled()).isTrue();
        assertThat(result.valid()).isTrue();
        assertThat(result.publicId()).matches(PUBLIC_ID);
        verify(repository).insert(eq(target), org.mockito.ArgumentMatchers.matches(PUBLIC_ID.pattern()), eq(ADMIN_ID));
        verify(repository).audit(eq("BIND"), eq(MACHINE_ID), eq(VIEWER_ID), eq(ADMIN_ID), eq(1L), anyString());
    }

    @Test
    void firstBindRejectsBotUnlessTargetIsEligibleAndManual() {
        when(repository.lockEligibleTarget(MACHINE_ID, VIEWER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bind(MACHINE_ID, VIEWER_ID, 0, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("DISPLAY_OBSERVER_TARGET_INVALID");
        verify(repository, never()).insert(any(), anyString(), anyLong());
    }

    @Test
    void deletedTargetsInvalidateTheirPublicIdButKeepSingletonAvailableForReset() {
        String oldPublicId = "Abcdef0123456789";
        DisplayObserverBindingRepository.BindingRow orphan = binding(
                null, null, MACHINE_ID, VIEWER_ID, oldPublicId, false, 7);
        when(repository.findByPublicId(oldPublicId)).thenReturn(Optional.of(orphan));
        when(repository.isValidBinding(null, null, GROUP_ID)).thenReturn(false);

        assertThat(service.resolvePublicId(oldPublicId)).isEmpty();

        DisplayObserverBindingRepository.TargetSnapshot replacement = target(12L, 52L);
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(orphan));
        when(repository.lockEligibleTarget(12L, 52L)).thenReturn(Optional.of(replacement));
        when(repository.rebind(eq(7L), eq(replacement), org.mockito.ArgumentMatchers.matches(PUBLIC_ID.pattern()),
                eq(ADMIN_ID))).thenReturn(1);
        when(repository.findDetails()).thenReturn(Optional.of(details(
                12L, 52L, false, 8, "Zyxwvu9876543210", GROUP_ID)));
        when(repository.isValidBinding(12L, 52L, GROUP_ID)).thenReturn(true);

        DisplayObserverBindingService.BindingView result = service.resetBinding(
                12L, 52L, 7, "异常删除后的受控重绑", ADMIN_ID);

        assertThat(result.configured()).isTrue();
        assertThat(result.enabled()).isFalse();
        assertThat(result.agentId()).isEqualTo(12L);
        assertThat(result.viewerUserId()).isEqualTo(52L);
        assertThat(result.publicId()).isNotEqualTo(oldPublicId);
        verify(repository).audit(eq("REBIND"), eq(12L), eq(52L), eq(ADMIN_ID), eq(8L), eq("异常删除后的受控重绑"));
    }

    @Test
    void aDeletedBindingCannotBeSilentlyReplacedWithInitialBind() {
        DisplayObserverBindingRepository.TargetSnapshot replacement = target(12L, 52L);
        when(repository.lockEligibleTarget(12L, 52L)).thenReturn(Optional.of(replacement));
        doThrow(new DuplicateKeyException("singleton binding exists")).when(repository)
                .insert(eq(replacement), org.mockito.ArgumentMatchers.matches(PUBLIC_ID.pattern()), eq(ADMIN_ID));

        assertThatThrownBy(() -> service.bind(12L, 52L, 0, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("DISPLAY_OBSERVER_ALREADY_BOUND");
    }

    @Test
    void boundBotCannotBeSwitchedBackToAutomatic() {
        when(repository.lockViewerBehavior(VIEWER_ID)).thenReturn(true);
        when(repository.isBoundViewer(VIEWER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.requireManualBehaviorIfBound(VIEWER_ID, "AUTOMATIC"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("DISPLAY_OBSERVER_BOT_MUST_REMAIN_MANUAL");
    }

    @Test
    void expiredOwningGroupMakesThePublicBindingUnavailable() {
        when(repository.findDetails()).thenReturn(Optional.of(details(
                MACHINE_ID, VIEWER_ID, true, 5, "Abcdef0123456789", GROUP_ID,
                Instant.now().minusSeconds(1))));
        when(repository.isValidBinding(MACHINE_ID, VIEWER_ID, GROUP_ID)).thenReturn(false);

        DisplayObserverBindingService.BindingView result = service.getBinding(ADMIN_ID);

        assertThat(result.valid()).isFalse();
        assertThat(result.invalidReason()).isEqualTo("展示机器所属子账号已过期");
    }

    @Test
    void expiredOwningGroupInvalidatesThePublicLinkResolver() {
        String publicId = "Abcdef0123456789";
        when(repository.findByPublicId(publicId)).thenReturn(Optional.of(binding(
                MACHINE_ID, VIEWER_ID, MACHINE_ID, VIEWER_ID, publicId, true, 5)));
        when(repository.isValidBinding(MACHINE_ID, VIEWER_ID, GROUP_ID)).thenReturn(false);

        assertThat(service.resolvePublicId(publicId)).isEmpty();
    }

    @Test
    void rotatingChangesThePublicIdAndAdvancesBindingVersion() {
        String oldPublicId = "Abcdef0123456789";
        DisplayObserverBindingRepository.BindingRow current = binding(
                MACHINE_ID, VIEWER_ID, MACHINE_ID, VIEWER_ID, oldPublicId, true, 4);
        when(repository.findSingletonForUpdate()).thenReturn(Optional.of(current));
        when(repository.isValidBinding(MACHINE_ID, VIEWER_ID, GROUP_ID)).thenReturn(true);
        when(repository.update(eq(4L), eq(true), org.mockito.ArgumentMatchers.matches(PUBLIC_ID.pattern()),
                eq(ADMIN_ID))).thenReturn(1);
        when(repository.findDetails()).thenReturn(Optional.of(details(
                MACHINE_ID, VIEWER_ID, true, 5, "Zyxwvu9876543210", GROUP_ID)));

        DisplayObserverBindingService.BindingView result = service.rotate("外发地址轮换", 4, ADMIN_ID);

        assertThat(result.version()).isEqualTo(5);
        assertThat(result.publicId()).isNotEqualTo(oldPublicId);
        verify(repository).audit(eq("ROTATE"), eq(MACHINE_ID), eq(VIEWER_ID), eq(ADMIN_ID), eq(5L), eq("外发地址轮换"));
    }

    private static DisplayObserverBindingRepository.BindingRow binding(
            Long agentId, Long viewerUserId, long agentIdSnapshot, long viewerUserIdSnapshot,
            String publicId, boolean enabled, long version
    ) {
        return new DisplayObserverBindingRepository.BindingRow(
                1, agentId, viewerUserId, agentIdSnapshot, viewerUserIdSnapshot, GROUP_ID,
                "DISPLAY", "display-machine", "展示机器", "展示子账号", "DISPLAY-BOT", "展示托",
                publicId, enabled, version, ADMIN_ID, Instant.now(), ADMIN_ID, Instant.now());
    }

    private static DisplayObserverBindingRepository.TargetSnapshot target(long agentId, long viewerUserId) {
        return new DisplayObserverBindingRepository.TargetSnapshot(agentId, viewerUserId, GROUP_ID,
                "DISPLAY", "display-machine", "展示机器", "展示子账号", "DISPLAY-BOT", "展示托");
    }

    private static DisplayObserverBindingRepository.BindingDetails details(
            Long agentId, Long viewerUserId, boolean enabled, long version, String publicId, long groupId
    ) {
        return details(agentId, viewerUserId, enabled, version, publicId, groupId, null);
    }

    private static DisplayObserverBindingRepository.BindingDetails details(
            Long agentId, Long viewerUserId, boolean enabled, long version, String publicId, long groupId,
            Instant groupExpiresAt
    ) {
        return new DisplayObserverBindingRepository.BindingDetails(
                agentId, viewerUserId, MACHINE_ID, VIEWER_ID, groupId,
                agentId == null ? null : groupId,
                "DISPLAY", "display-machine", "展示机器", "展示子账号", "DISPLAY-BOT", "展示托",
                publicId, enabled, version,
                agentId == null ? null : "ACTIVE", false, null,
                false, agentId == null ? null : ACCOUNT_USER_ID,
                agentId == null ? null : "DISPLAY", agentId == null ? null : "display-machine",
                agentId == null ? null : "展示机器", agentId == null ? null : "展示子账号",
                agentId == null ? null : "ACTIVE", groupExpiresAt, false, agentId == null ? null : "ACTIVE",
                viewerUserId == null ? null : "展示托", viewerUserId == null ? null : "ACTIVE",
                viewerUserId == null ? null : "BOT", viewerUserId == null ? null : "ACTIVE",
                viewerUserId == null ? null : agentId, viewerUserId == null ? null : "MANUAL",
                viewerUserId == null ? null : false, agentId != null && viewerUserId != null);
    }
}
