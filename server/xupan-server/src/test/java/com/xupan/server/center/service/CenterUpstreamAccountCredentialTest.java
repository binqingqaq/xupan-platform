package com.xupan.server.center.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.center.repository.CenterUpstreamAccountRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class CenterUpstreamAccountCredentialTest {

    @Test
    void generatedCredentialsFollowTheRuleAndAreNeverLoggedInPlaintext() {
        CenterUpstreamAccountService service = spy(new CenterUpstreamAccountService(
                mock(CenterUpstreamAccountRepository.class), mock(PermissionService.class),
                mock(PasswordPolicyService.class), mock(CenterTokenService.class),
                mock(OperationAuditRepository.class)));
        doAnswer(invocation -> {
            long groupId = invocation.getArgument(0);
            String username = invocation.getArgument(1);
            return new CenterUpstreamAccountService.CreatedAccount(
                    new CenterUpstreamAccountService.AccountView(1L, groupId, "测试子账号", username,
                            "ACTIVE", null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            null, false, 0));
        }).when(service).createAccount(anyLong(), anyString(), anyString(), any(BigDecimal.class),
                nullable(Instant.class), anyLong());

        var first = service.createGeneratedAccount(31L, BigDecimal.ZERO, null, 9L);
        var second = service.createGeneratedAccount(31L, BigDecimal.ZERO, null, 9L);

        assertThat(first.account().username()).matches("loc31_[a-z0-9]{10}")
                .isNotEqualTo(second.account().username());
        assertThat(first.password()).matches("[a-z0-9]{16}")
                .containsPattern("[a-z]").containsPattern("[0-9]")
                .isNotEqualTo(second.password());
        assertThat(first.toString()).doesNotContain(first.password());
    }
}
