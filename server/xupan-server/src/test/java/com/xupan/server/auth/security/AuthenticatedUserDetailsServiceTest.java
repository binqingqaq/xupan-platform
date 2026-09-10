package com.xupan.server.auth.security;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedUserDetailsServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void reloadsCurrentActivePermissionsInsteadOfTrustingAClientSnapshot() {
        UserRepository users = mock(UserRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        UserAccount account = new UserAccount(7L, "alice", "Alice", null, "password-hash", "ACTIVE",
                0, null, 2L, null, null);
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        when(permissions.findPermissionCodes(7L)).thenReturn(Set.of("CHAT_ROOM_READ"),
                Set.of("GAME_CURRENT_READ"));
        when(permissions.toAuthorities(any())).thenAnswer(invocation -> {
            Set<String> codes = invocation.getArgument(0);
            return codes.stream().map(code -> (GrantedAuthority) () -> "PERM_" + code)
                    .collect(Collectors.toSet());
        });
        AuthenticatedUserDetailsService service = new AuthenticatedUserDetailsService(users, permissions);

        AuthenticatedUser first = service.loadUserByUsername("alice");
        AuthenticatedUser second = service.loadUserByUsername("alice");

        assertThat(first.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("PERM_CHAT_ROOM_READ");
        assertThat(second.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("PERM_GAME_CURRENT_READ");
        assertThat(first.getPassword()).isEqualTo("password-hash");
        assertThat(first.getSecurityVersion()).isEqualTo(2L);
        verify(permissions, org.mockito.Mockito.times(2)).findPermissionCodes(7L);
    }

    @Test
    void preservesDisabledAndDeletedStatusForSecurityToReject() {
        UserRepository users = mock(UserRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.findPermissionCodes(7L)).thenReturn(Set.of());
        when(permissions.toAuthorities(any())).thenReturn(Set.of());
        when(users.findById(7L)).thenReturn(Optional.of(new UserAccount(7L, "disabled", "Disabled", null,
                "password-hash", "DISABLED", 0, null, 0L, null, null)),
                Optional.of(new UserAccount(7L, "deleted", "Deleted", null,
                        "password-hash", "DELETED", 0, null, 0L, null, null)));
        AuthenticatedUserDetailsService service = new AuthenticatedUserDetailsService(users, permissions);

        AuthenticatedUser result = service.loadUserById(7L);
        AuthenticatedUser deleted = service.loadUserById(7L);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getAuthorities()).isEmpty();
        assertThat(result.getPassword()).isEqualTo("password-hash");
        assertThat(deleted.isEnabled()).isFalse();
    }
}
