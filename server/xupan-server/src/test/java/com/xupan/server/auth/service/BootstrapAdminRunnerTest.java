package com.xupan.server.auth.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.system.service.UserAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAdminRunnerTest {

    @Test
    void disabledBootstrapDoesNothing() {
        UserRepository users = mock(UserRepository.class);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(users, new PasswordPolicyService(),
                false, null, null);

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(users, never()).existsAnyUser();
    }

    @Test
    void existingUserIsNeverOverwrittenOrGivenAnotherAdmin() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsAnyUser()).thenReturn(true);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(users, new PasswordPolicyService(),
                true, "external-admin", "BootstrapPassword123");

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(users).existsAnyUser();
        verify(users, never()).insert(anyString(), anyString(), anyString(), anyString());
        verify(users, never()).assignRole(eq(1L), anyString());
    }

    @Test
    void enabledEmptyBootstrapFailsClearlyWhenExternalCredentialsAreMissing() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsAnyUser()).thenReturn(false);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(users, new PasswordPolicyService(),
                true, "external-admin", "");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置缺失")
                .hasMessageNotContaining("BootstrapPassword123");
        verify(users, never()).insert(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void enabledEmptyBootstrapCreatesHashedAdminAndBindsAdminRole() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsAnyUser()).thenReturn(false);
        when(users.insert(eq("external-admin"), eq("external-admin"), anyString(), eq("ACTIVE"))).thenReturn(42L);
        when(users.assignRole(42L, "ADMIN")).thenReturn(1);
        String rawPassword = "BootstrapPassword123";
        BootstrapAdminRunner runner = new BootstrapAdminRunner(users, new PasswordPolicyService(),
                true, "external-admin", rawPassword);

        runner.run(new DefaultApplicationArguments(new String[0]));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(users).insert(eq("external-admin"), eq("external-admin"), captor.capture(), eq("ACTIVE"));
        assertThat(captor.getValue()).doesNotContain(rawPassword).startsWith("$2");
        verify(users).assignRole(42L, "ADMIN");
    }

    @Test
    void enabledBootstrapUsesUserAdminServiceSoAdminGetsWalletInitialization() {
        UserRepository users = mock(UserRepository.class);
        UserAdminService userAdminService = mock(UserAdminService.class);
        when(users.existsAnyUser()).thenReturn(false);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(userAdminService, users,
                true, "external-admin", "BootstrapPassword123");

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(userAdminService).createUser("external-admin", "external-admin",
                "BootstrapPassword123", "ADMIN", 0L);
        verify(users, never()).insert(anyString(), anyString(), anyString(), anyString());
    }
}
