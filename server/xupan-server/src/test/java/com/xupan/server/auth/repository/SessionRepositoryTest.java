package com.xupan.server.auth.repository;

import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.domain.WsTicket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE session_id LIKE 'repo-session-%'");
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

    @Test
    void createsFindsAndConsumesTicketOnlyForMatchingIdentityAndRoom() {
        userId = userRepository.insert("repo-session-user-ticket", "Ticket User", "hash", "ACTIVE");
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        repository.insert(session("repo-session-ticket", userId, "access-ticket", "refresh-ticket", now));
        WsTicket ticket = new WsTicket("ticket-hash", userId, "repo-session-ticket", "room-a",
                now.plusSeconds(60), null, now);
        repository.insertWsTicket(ticket);

        assertThat(repository.findWsTicketByHash("ticket-hash", userId, "repo-session-ticket", "room-a", now)).get()
                .extracting(WsTicket::userId, WsTicket::sessionId, WsTicket::roomCode)
                .containsExactly(userId, "repo-session-ticket", "room-a");
        assertThat(repository.findUsableWsTicket("ticket-hash", userId, "repo-session-ticket", "room-a", now))
                .isPresent();
        assertThat(repository.findUsableWsTicket("ticket-hash", userId + 1, "repo-session-ticket", "room-a", now))
                .isEmpty();
        assertThat(repository.findUsableWsTicket("ticket-hash", userId, "other-session", "room-a", now))
                .isEmpty();
        assertThat(repository.findUsableWsTicket("ticket-hash", userId, "repo-session-ticket", "room-b", now))
                .isEmpty();

        assertThat(repository.consumeWsTicket("ticket-hash", userId, "repo-session-ticket", "room-a", now))
                .get().extracting(WsTicket::usedAt).isEqualTo(now);
        assertThat(repository.consumeWsTicket("ticket-hash", userId, "repo-session-ticket", "room-a",
                now.plusSeconds(1))).isEmpty();
        assertThat(repository.findUsableWsTicket("ticket-hash", userId, "repo-session-ticket", "room-a",
                now.plusSeconds(1))).isEmpty();
    }

    @Test
    void rejectsExpiredTicketAndDoesNotMarkItUsed() {
        userId = userRepository.insert("repo-session-user-expired-ticket", "Ticket User", "hash", "ACTIVE");
        Instant expiresAt = Instant.parse("2026-09-10T10:00:00Z");
        repository.insert(session("repo-session-expired-ticket", userId, "access-expired", "refresh-expired", expiresAt));
        repository.saveWsTicket(new WsTicket("expired-ticket-hash", userId, "repo-session-expired-ticket", "room-a",
                expiresAt, null, expiresAt.minusSeconds(60)));

        assertThat(repository.consumeWsTicket("expired-ticket-hash", userId, "repo-session-expired-ticket", "room-a",
                expiresAt)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_at FROM auth_ws_ticket WHERE ticket_hash = ?", java.sql.Timestamp.class,
                "expired-ticket-hash")).isNull();
    }

    @Test
    void conditionalTicketConsumptionAllowsOnlyOneConcurrentWinner() throws Exception {
        userId = userRepository.insert("repo-session-user-concurrent-ticket", "Ticket User", "hash", "ACTIVE");
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        repository.insert(session("repo-session-concurrent-ticket", userId, "access-concurrent", "refresh-concurrent", now));
        repository.insertWsTicket(new WsTicket("concurrent-ticket-hash", userId, "repo-session-concurrent-ticket",
                "room-a", now.plusSeconds(60), null, now));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = List.of(
                    submitConsumer(executor, ready, start, now),
                    submitConsumer(executor, ready, start, now));
            ready.await();
            start.countDown();

            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void treatsNullAndBlankRoomAsTheSameUnboundTicket() {
        userId = userRepository.insert("repo-session-user-empty-room", "Ticket User", "hash", "ACTIVE");
        Instant now = Instant.now();
        repository.insert(session("repo-session-empty-room", userId, "access-empty-room", "refresh-empty-room", now));
        repository.insertWsTicket(new WsTicket("empty-room-ticket-hash", userId, "repo-session-empty-room",
                null, now.plusSeconds(60), null, now));

        assertThat(repository.findWsTicketByHash("empty-room-ticket-hash", userId, "repo-session-empty-room", "", now))
                .isPresent();
        assertThat(repository.consumeWsTicket("empty-room-ticket-hash", userId, "repo-session-empty-room", "   ", now))
                .isPresent();
    }

    @Test
    void rejectsSessionUserMismatchDuplicateHashAndMissingSession() {
        userId = userRepository.insert("repo-session-user-binding", "Ticket User", "hash", "ACTIVE");
        long otherUserId = userRepository.insert("repo-session-user-other", "Other User", "hash", "ACTIVE");
        Instant now = Instant.now();
        repository.insert(session("repo-session-binding", userId, "access-binding", "refresh-binding", now));
        repository.insert(session("repo-session-other", otherUserId, "access-other", "refresh-other", now));

        jdbcTemplate.update("""
                INSERT INTO auth_ws_ticket
                    (ticket_hash, user_id, session_id, room_code, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "binding-mismatch-hash", otherUserId, "repo-session-binding", "room-binding",
                java.sql.Timestamp.from(now.plusSeconds(60)), java.sql.Timestamp.from(now));
        assertThat(repository.findWsTicketByHash("binding-mismatch-hash", otherUserId, "repo-session-binding",
                "room-binding", now)).isEmpty();
        assertThat(repository.consumeWsTicket("binding-mismatch-hash", otherUserId, "repo-session-binding",
                "room-binding", now)).isEmpty();

        assertThatThrownBy(() -> repository.insertWsTicket(new WsTicket("binding-mismatch-hash", userId,
                "repo-session-binding", "room-binding", now.plusSeconds(60), null, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.insertWsTicket(new WsTicket("missing-session-hash", userId,
                "repo-session-missing", "room-binding", now.plusSeconds(60), null, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Future<Boolean> submitConsumer(ExecutorService executor, CountDownLatch ready,
                                            CountDownLatch start, Instant now) {
        return executor.submit(() -> {
            ready.countDown();
            start.await();
            return repository.consumeWsTicket("concurrent-ticket-hash", userId,
                    "repo-session-concurrent-ticket", "room-a", now).isPresent();
        });
    }

    private static SessionRecord session(String id, long userId, String accessHash, String refreshHash,
                                         Instant now) {
        return new SessionRecord(0L, id, userId, accessHash, now.plusSeconds(1800), refreshHash,
                now.plusSeconds(30L * 24 * 60 * 60), "test-device", "ip-digest", "ua-digest",
                null, null, now, 0L);
    }
}
