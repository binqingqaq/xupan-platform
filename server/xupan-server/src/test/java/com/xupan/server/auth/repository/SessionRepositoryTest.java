package com.xupan.server.auth.repository;

import com.xupan.server.auth.domain.SessionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SessionRepositoryTest {

    @Autowired
    private SessionRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM auth_session WHERE session_id LIKE 'repo-session-%'");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'repo-session-user-%'");
    }

    @Test
    void findsRotatesAndRevokesByHashedToken() {
        userId = userRepository.insert("repo-session-user-one", "Session User", "hash", "ACTIVE");
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        SessionRecord original = session("repo-session-one", userId, "access-one", "refresh-one", now);
        repository.insert(original);

        assertThat(repository.findByAccessTokenHash("access-one")).get().extracting(SessionRecord::sessionId)
                .isEqualTo("repo-session-one");
        assertThat(repository.findByRefreshTokenHash("refresh-one")).isPresent();

        repository.rotateTokens("repo-session-one", "access-two", now.plusSeconds(120),
                "refresh-two", now.plusSeconds(7200));
        assertThat(repository.findByAccessTokenHash("access-one")).isEmpty();
        assertThat(repository.findByRefreshTokenHash("refresh-two")).get()
                .extracting(SessionRecord::accessTokenHash).isEqualTo("access-two");
        assertThat(repository.rotateTokensIfCurrent("repo-session-one", "refresh-one", "access-three",
                now.plusSeconds(180), "refresh-three", now.plusSeconds(7300))).isFalse();
        assertThat(repository.rotateTokensIfCurrent("repo-session-one", "refresh-two", "access-three",
                now.plusSeconds(180), "refresh-three", now.plusSeconds(7300))).isTrue();

        assertThat(repository.revoke("repo-session-one", now.plusSeconds(1))).isTrue();
        assertThat(repository.revoke("repo-session-one", now.plusSeconds(2))).isFalse();
        assertThat(repository.findByAccessTokenHash("access-three")).get().satisfies(value ->
                assertThat(value.revokedAt()).isEqualTo(now.plusSeconds(1)));
    }

    @Test
    void revokesAllUserSessionsIdempotentlyAndTouchesLastSeen() {
        userId = userRepository.insert("repo-session-user-two", "Session User", "hash", "ACTIVE");
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        repository.insert(session("repo-session-two", userId, "access-three", "refresh-three", now));
        repository.insert(session("repo-session-three", userId, "access-four", "refresh-four", now));

        Instant seenAt = now.plusSeconds(10);
        assertThat(repository.touch("repo-session-two", seenAt)).isTrue();
        assertThat(repository.revokeAllByUserId(userId, now.plusSeconds(20))).isEqualTo(2);
        assertThat(repository.revokeAllByUserId(userId, now.plusSeconds(21))).isEqualTo(0);
        assertThat(repository.findByAccessTokenHash("access-three")).get()
                .extracting(SessionRecord::lastSeenAt).isEqualTo(seenAt);
    }

    private static SessionRecord session(String id, long userId, String accessHash, String refreshHash,
                                         Instant now) {
        return new SessionRecord(0L, id, userId, accessHash, now.plusSeconds(1800), refreshHash,
                now.plusSeconds(30L * 24 * 60 * 60), "test-device", "ip-digest", "ua-digest",
                null, null, now, 0L);
    }
}
