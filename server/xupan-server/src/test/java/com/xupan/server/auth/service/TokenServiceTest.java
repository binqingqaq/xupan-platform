package com.xupan.server.auth.service;

import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.domain.WsTicket;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

    @Test
    void issuesIndependentOpaqueTokensWithRequiredLengthsAndOnlyHashes() {
        SessionRepository sessions = mock(SessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        UserAccount user = activeUser(7L, 3L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        TokenService service = service(sessions, users);

        TokenService.IssuedTokens first = service.issue(7L, "browser", new TokenService.RequestMetadata(
                "192.0.2.10", "test-agent"));
        TokenService.IssuedTokens second = service.issue(7L, "browser", TokenService.RequestMetadata.empty());

        assertThat(java.util.Base64.getUrlDecoder().decode(first.accessToken())).hasSize(TokenService.ACCESS_TOKEN_BYTES);
        assertThat(java.util.Base64.getUrlDecoder().decode(first.refreshToken())).hasSize(TokenService.REFRESH_TOKEN_BYTES);
        assertThat(first.accessToken()).isNotEqualTo(first.refreshToken());
        assertThat(first.accessToken()).isNotEqualTo(second.accessToken());
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessions, org.mockito.Mockito.times(2)).insert(captor.capture());
        SessionRecord persisted = captor.getAllValues().get(0);
        assertThat(persisted.accessTokenHash()).isEqualTo(TokenService.sha256(first.accessToken()));
        assertThat(persisted.refreshTokenHash()).isEqualTo(TokenService.sha256(first.refreshToken()));
        assertThat(persisted.accessTokenHash()).doesNotContain(first.accessToken());
        assertThat(persisted.refreshTokenHash()).doesNotContain(first.refreshToken());
        assertThat(persisted.ipDigest()).isEqualTo(TokenService.sha256("192.0.2.10"));
        assertThat(persisted.userAgentDigest()).isEqualTo(TokenService.sha256("test-agent"));
    }

    @Test
    void accessAndRefreshTokensAreNotInterchangeable() {
        SessionRepository sessions = mock(SessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(activeUser(7L, 0L)));
        TokenService service = service(sessions, users);
        TokenService.IssuedTokens issued = service.issue(7L, null, null);

        when(sessions.findByAccessTokenHash(TokenService.sha256(issued.accessToken())))
                .thenReturn(Optional.empty());
        assertThat(service.validateAccessToken(issued.refreshToken())).isEmpty();
    }

    @Test
    void rejectsExpiredRevokedVersionMismatchedAndInactiveAccessSessions() {
        SessionRepository sessions = mock(SessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        UserAccount active = activeUser(7L, 4L);
        when(users.findById(7L)).thenReturn(Optional.of(active));
        TokenService service = service(sessions, users);

        SessionRecord expired = session("expired", 7L, NOW.minusSeconds(1), null, 4L);
        when(sessions.findByAccessTokenHash(TokenService.sha256("access"))).thenReturn(Optional.of(expired));
        assertThat(service.validateAccessToken("access")).isEmpty();

        SessionRecord revoked = session("revoked", 7L, NOW.plusSeconds(60), NOW.minusSeconds(1), 4L);
        when(sessions.findByAccessTokenHash(TokenService.sha256("access"))).thenReturn(Optional.of(revoked));
        assertThat(service.validateAccessToken("access")).isEmpty();

        SessionRecord wrongVersion = session("version", 7L, NOW.plusSeconds(60), null, 3L);
        when(sessions.findByAccessTokenHash(TokenService.sha256("access"))).thenReturn(Optional.of(wrongVersion));
        assertThat(service.validateAccessToken("access")).isEmpty();

        when(users.findById(7L)).thenReturn(Optional.of(activeUser(7L, 4L, "DISABLED")));
        SessionRecord valid = session("disabled", 7L, NOW.plusSeconds(60), null, 4L);
        when(sessions.findByAccessTokenHash(TokenService.sha256("access"))).thenReturn(Optional.of(valid));
        assertThat(service.validateAccessToken("access")).isEmpty();

        when(users.findById(7L)).thenReturn(Optional.of(activeUser(7L, 4L, "DELETED")));
        assertThat(service.validateAccessToken("access")).isEmpty();
    }

    @Test
    void refreshUsesConditionalRotationAndRejectsReplay() {
        SessionRepository sessions = mock(SessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(activeUser(7L, 0L)));
        TokenService service = service(sessions, users);
        SessionRecord current = new SessionRecord(1L, "session", 7L, "access-hash", NOW.plusSeconds(60),
                TokenService.sha256("refresh-one"), NOW.plusSeconds(3600), null, null, null,
                NOW, null, NOW, 0L);
        when(sessions.findByRefreshTokenHash(TokenService.sha256("refresh-one")))
                .thenReturn(Optional.of(current));
        when(sessions.rotateTokensIfCurrent(eq("session"), eq(TokenService.sha256("refresh-one")),
                any(), any(), any(), any())).thenReturn(true, false);

        TokenService.IssuedTokens rotated = service.refresh("refresh-one", TokenService.RequestMetadata.empty());
        assertThat(rotated.sessionId()).isEqualTo("session");
        assertThat(java.util.Base64.getUrlDecoder().decode(rotated.accessToken())).hasSize(TokenService.ACCESS_TOKEN_BYTES);
        assertThat(java.util.Base64.getUrlDecoder().decode(rotated.refreshToken())).hasSize(TokenService.REFRESH_TOKEN_BYTES);
        assertThatThrownBy(() -> service.refresh("refresh-one", TokenService.RequestMetadata.empty()))
                .isInstanceOf(TokenService.InvalidTokenException.class);
    }

    @Test
    void createsAndConsumesBoundSingleUseWsTicketThroughRepository() {
        SessionRepository sessions = mock(SessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(activeUser(7L, 0L)));
        SessionRecord session = session("session", 7L, NOW.plusSeconds(60), null, 0L);
        when(sessions.findBySessionId("session")).thenReturn(Optional.of(session));
        TokenService service = service(sessions, users);

        TokenService.IssuedWsTicket issued = service.issueWsTicket(7L, "session", "room-a");
        assertThat(java.util.Base64.getUrlDecoder().decode(issued.rawTicket())).hasSize(TokenService.WS_TICKET_BYTES);
        assertThat(issued.persisted().ticketHash()).isEqualTo(TokenService.sha256(issued.rawTicket()));
        assertThat(issued.persisted().ticketHash()).doesNotContain(issued.rawTicket());
        assertThat(issued.persisted().expiresAt()).isEqualTo(NOW.plusSeconds(60));

        WsTicket consumed = issued.persisted().withUsedAt(NOW);
        when(sessions.consumeWsTicket(TokenService.sha256(issued.rawTicket()), 7L, "session", "room-a", NOW))
                .thenReturn(Optional.of(consumed));
        assertThat(service.consumeWsTicket(issued.rawTicket(), 7L, "session", "room-a")).isEqualTo(consumed);

        when(sessions.consumeWsTicket(TokenService.sha256(issued.rawTicket()), 7L, "session", "room-a", NOW))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consumeWsTicket(issued.rawTicket(), 7L, "session", "room-a"))
                .isInstanceOf(TokenService.InvalidTokenException.class);
    }

    private static TokenService service(SessionRepository sessions, UserRepository users) {
        return new TokenService(sessions, users, new CounterRandom(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UserAccount activeUser(long id, long securityVersion) {
        return activeUser(id, securityVersion, "ACTIVE");
    }

    private static UserAccount activeUser(long id, long securityVersion, String status) {
        return new UserAccount(id, "user-" + id, "User", null, "password-hash", status,
                0, null, securityVersion, null, null);
    }

    private static SessionRecord session(String id, long userId, Instant accessExpiresAt,
                                         Instant revokedAt, long securityVersion) {
        return new SessionRecord(1L, id, userId, "access-hash", accessExpiresAt,
                "refresh-hash", NOW.plusSeconds(3600), null, null, null, NOW, revokedAt, NOW,
                securityVersion);
    }

    private static final class CounterRandom extends SecureRandom {
        private int counter;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (++counter);
            }
        }
    }
}
