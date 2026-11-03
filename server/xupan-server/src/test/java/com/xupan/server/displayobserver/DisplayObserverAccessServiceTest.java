package com.xupan.server.displayobserver;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DisplayObserverAccessServiceTest {

    private final DisplayObserverBindingService bindingService = mock(DisplayObserverBindingService.class);
    private final DisplayObserverAccessService service = new DisplayObserverAccessService(bindingService);

    @Test
    void resolvesOnlyTheBoundViewerAndReplacesItsNormalAuthorities() {
        String publicId = "AbCdEf0123456789";
        when(bindingService.resolvePublicId(publicId)).thenReturn(Optional.of(
                new DisplayObserverBindingService.PublicBinding(publicId, 17, 42, 3)));
        AuthenticatedUser bot = botUser(42);

        Optional<AuthenticatedUser> result = service.principal(bot, "DRO:" + publicId);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().isDisplayObserver()).isTrue();
        assertThat(result.orElseThrow().getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder(
                        "SCOPE_DISPLAY_OBSERVER", "PERM_GAME_CURRENT_READ", "PERM_WALLET_READ")
                .doesNotContain("PERM_GAME_BET_PLACE", "PERM_CHAT_MESSAGE_SEND", "PERM_WALLET_ADJUST",
                        "PERM_USER_MANAGE");
        assertThat(service.isCurrent(42, "DRO:" + publicId)).isTrue();
        assertThat(service.isCurrent(43, "DRO:" + publicId)).isFalse();
    }

    @Test
    void rejectsMalformedScopeWithoutLookingUpBinding() {
        assertThat(service.principal(botUser(42), "DRO:short")).isEmpty();
        assertThat(service.resolve("short")).isEmpty();
        verifyNoInteractions(bindingService);
    }

    @Test
    void rejectsAViewerThatDoesNotMatchTheBinding() {
        String publicId = "AbCdEf0123456789";
        when(bindingService.resolvePublicId(publicId)).thenReturn(Optional.of(
                new DisplayObserverBindingService.PublicBinding(publicId, 17, 42, 3)));

        assertThat(service.principal(botUser(43), "DRO:" + publicId)).isEmpty();
        assertThat(service.isCurrent(43, "DRO:" + publicId)).isFalse();
    }

    private static AuthenticatedUser botUser(long userId) {
        UserAccount account = new UserAccount(userId, "showcase-bot", "展示托", null,
                "password-hash", "ACTIVE", 0, null, 0L, null, null, "TEST");
        return new AuthenticatedUser(account, List.of(
                new SimpleGrantedAuthority("PERM_GAME_BET_PLACE"),
                new SimpleGrantedAuthority("PERM_CHAT_MESSAGE_SEND"),
                new SimpleGrantedAuthority("PERM_WALLET_ADJUST"),
                new SimpleGrantedAuthority("PERM_USER_MANAGE")));
    }
}
