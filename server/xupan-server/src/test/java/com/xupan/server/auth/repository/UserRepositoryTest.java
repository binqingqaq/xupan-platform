package com.xupan.server.auth.repository;

import com.xupan.server.auth.domain.UserAccount;
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
class UserRepositoryTest {

    @Autowired
    private UserRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUsers() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'repo-test-%'");
    }

    @Test
    void insertsAndFindsUserWithExplicitDomainFields() {
        long id = repository.insert("repo-test-alice", "Alice", "{bcrypt}hash", "ACTIVE");

        assertThat(repository.findByUsername("repo-test-alice")).get()
                .extracting(UserAccount::id, UserAccount::username, UserAccount::displayName,
                        UserAccount::passwordHash, UserAccount::status)
                .containsExactly(id, "repo-test-alice", "Alice", "{bcrypt}hash", "ACTIVE");
        assertThat(repository.findById(id)).isPresent();
        assertThat(repository.findByIdForUpdate(id)).isPresent();
        assertThat(repository.existsAnyUser()).isTrue();
    }

    @Test
    void recordsFailuresAndClearsThemOnSuccessfulLogin() {
        long id = repository.insert("repo-test-lock", "Lock", "hash", "ACTIVE");

        for (int count = 1; count <= 4; count++) {
            repository.recordLoginFailure(id, null);
        }
        assertThat(repository.findById(id)).get().extracting(UserAccount::failedLoginCount).isEqualTo(4);

        Instant lockedUntil = Instant.parse("2026-09-10T10:15:00Z");
        repository.recordLoginFailure(id, lockedUntil);
        assertThat(repository.findById(id)).get()
                .extracting(UserAccount::failedLoginCount, UserAccount::status, UserAccount::lockedUntil)
                .containsExactly(5, "LOCKED", lockedUntil);

        Instant loginAt = Instant.parse("2026-09-10T10:01:00Z");
        repository.clearLoginFailures(id, "192.0.2.1", loginAt);
        assertThat(repository.findById(id)).get()
                .extracting(UserAccount::failedLoginCount, UserAccount::status, UserAccount::lockedUntil,
                        UserAccount::lastLoginIp, UserAccount::lastLoginAt)
                .containsExactly(0, "ACTIVE", null, "192.0.2.1", loginAt);
    }

    @Test
    void incrementsSecurityVersion() {
        long id = repository.insert("repo-test-version", "Version", "hash", "ACTIVE");

        repository.incrementSecurityVersion(id);
        repository.incrementSecurityVersion(id);

        assertThat(repository.findById(id)).get().extracting(UserAccount::securityVersion).isEqualTo(2L);
    }
}
