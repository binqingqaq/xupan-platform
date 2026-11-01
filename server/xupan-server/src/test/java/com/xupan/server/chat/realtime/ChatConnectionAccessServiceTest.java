package com.xupan.server.chat.realtime;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.security.AuthenticatedUserDetailsService;
import com.xupan.server.displayobserver.DisplayObserverAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class ChatConnectionAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final AuthenticatedUserDetailsService userDetailsService = mock(AuthenticatedUserDetailsService.class);
    private final ChatConnectionAccessService accessService =
            new ChatConnectionAccessService(sessionRepository, userDetailsService);
    private final ChatConnection connection = mock(ChatConnection.class);

    @Test
    void allowsOnlyAnActiveSessionWithCurrentRoomReadPermission() {
        AuthenticatedUser user = user(true, true, true);
        when(connection.userId()).thenReturn(7L);
        when(connection.sessionId()).thenReturn("session-7");
        when(userDetailsService.loadUserById(7L)).thenReturn(user);
        when(sessionRepository.findBySessionId("session-7")).thenReturn(Optional.of(activeSession()));

        ChatConnectionAccessService.AccessCheck result = accessService.check(connection, NOW);

        assertThat(result.status()).isEqualTo(ChatConnectionAccessService.Status.ALLOWED);
        assertThat(result.user()).isSameAs(user);
    }

    @Test
    void rejectsRevokedSession() {
        when(connection.userId()).thenReturn(7L);
        when(connection.sessionId()).thenReturn("session-7");
        AuthenticatedUser user = user(true, true, true);
        when(userDetailsService.loadUserById(7L)).thenReturn(user);
        when(sessionRepository.findBySessionId("session-7"))
                .thenReturn(Optional.of(activeSession().withRevokedAt(NOW.minusSeconds(1))));

        ChatConnectionAccessService.AccessCheck result = accessService.check(connection, NOW);

        assertThat(result.status()).isEqualTo(ChatConnectionAccessService.Status.UNAUTHENTICATED);
    }

    @Test
    void rejectsDisabledUserAndRevokedRoomReadPermission() {
        when(connection.userId()).thenReturn(7L);
        when(connection.sessionId()).thenReturn("session-7");
        when(sessionRepository.findBySessionId("session-7")).thenReturn(Optional.of(activeSession()));

        AuthenticatedUser disabled = user(false, true, true);
        when(userDetailsService.loadUserById(7L)).thenReturn(disabled);
        assertThat(accessService.check(connection, NOW).status())
                .isEqualTo(ChatConnectionAccessService.Status.FORBIDDEN);

        AuthenticatedUser withoutRead = user(true, true, false);
        when(userDetailsService.loadUserById(7L)).thenReturn(withoutRead);
        assertThat(accessService.check(connection, NOW).status())
                .isEqualTo(ChatConnectionAccessService.Status.FORBIDDEN);
    }

    @Test
    void displayObserverConnectionRequiresTheStillActiveFixedBinding() {
        when(connection.userId()).thenReturn(7L);
        when(connection.sessionId()).thenReturn("observer-session");
        String scope = "DRO:AbCdEf0123456789";
        SessionRecord observerSession = new SessionRecord(2L, "observer-session", 7L,
                "access-hash", NOW.plusSeconds(60), "refresh-hash", NOW.plusSeconds(120),
                "observer", null, null, NOW, null, NOW, 0L, "DISPLAY_OBSERVER", scope,
                SessionRecord.ENTRY_DISPLAY_OBSERVER);
        when(sessionRepository.findBySessionId("observer-session")).thenReturn(Optional.of(observerSession));
        AuthenticatedUser bot = new AuthenticatedUser(
                new UserAccount(7L, "display-bot", "展示托", null, "hash", "ACTIVE", 0,
                        null, 0L, null, null), List.of());
        when(userDetailsService.loadUserById(7L)).thenReturn(bot);

        DisplayObserverAccessService bindings = mock(DisplayObserverAccessService.class);
        AuthenticatedUser observer = bot.asDisplayObserver(scope);
        when(bindings.principal(bot, scope)).thenReturn(Optional.of(observer), Optional.empty());
        ChatConnectionAccessService observerAccess = new ChatConnectionAccessService(
                sessionRepository, userDetailsService, bindings);

        assertThat(observerAccess.check(connection, NOW).status())
                .isEqualTo(ChatConnectionAccessService.Status.ALLOWED);
        assertThat(observerAccess.check(connection, NOW).status())
                .isEqualTo(ChatConnectionAccessService.Status.FORBIDDEN);
    }

    private static AuthenticatedUser user(boolean enabled, boolean nonLocked, boolean canRead) {
        AuthenticatedUser user = mock(AuthenticatedUser.class);
        when(user.getUserId()).thenReturn(7L);
        when(user.getSecurityVersion()).thenReturn(0L);
        when(user.isEnabled()).thenReturn(enabled);
        when(user.isAccountNonLocked()).thenReturn(nonLocked);
        List<GrantedAuthority> authorities = canRead
                ? List.of(new SimpleGrantedAuthority("PERM_CHAT_ROOM_READ"))
                : List.of();
        doReturn(authorities).when(user).getAuthorities();
        return user;
    }

    private static SessionRecord activeSession() {
        return new SessionRecord(1L, "session-7", 7L, "access-hash", NOW.plusSeconds(60),
                "refresh-hash", NOW.plusSeconds(120), "test", null, null, NOW, null, NOW, 0L);
    }
}
