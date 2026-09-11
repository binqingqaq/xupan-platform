package com.xupan.server.auth.repository;

import com.xupan.server.auth.domain.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThatThrownBy(() -> repository.findByIdForUpdate(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("外层事务");
        UserAccount lockedUser = repository.executeInLockedUserTransaction(id, user -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return user;
        });
        assertThat(lockedUser.id()).isEqualTo(id);
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
    void loginUpdatesDoNotReactivateDisabledOrDeletedUsers() {
        long disabledId = repository.insert("repo-test-disabled", "Disabled", "hash", "DISABLED");
        long deletedId = repository.insert("repo-test-deleted", "Deleted", "hash", "DELETED");
        Instant loginAt = Instant.parse("2026-09-10T10:01:00Z");
        Instant lockedUntil = Instant.parse("2026-09-10T10:15:00Z");

        assertThat(repository.recordLoginFailure(disabledId, lockedUntil)).isZero();
        assertThat(repository.clearLoginFailures(disabledId, "192.0.2.1", loginAt)).isZero();
        assertThat(repository.recordLoginFailure(deletedId, lockedUntil)).isZero();
        assertThat(repository.clearLoginFailures(deletedId, "192.0.2.1", loginAt)).isZero();

        assertThat(repository.findById(disabledId)).get()
                .extracting(UserAccount::status, UserAccount::failedLoginCount, UserAccount::lastLoginAt)
                .containsExactly("DISABLED", 0, null);
        assertThat(repository.findById(deletedId)).get()
                .extracting(UserAccount::status, UserAccount::failedLoginCount, UserAccount::lastLoginAt)
                .containsExactly("DELETED", 0, null);
    }

    @Test
    void incrementsSecurityVersion() {
        long id = repository.insert("repo-test-version", "Version", "hash", "ACTIVE");

        repository.incrementSecurityVersion(id);
        repository.incrementSecurityVersion(id);

        assertThat(repository.findById(id)).get().extracting(UserAccount::securityVersion).isEqualTo(2L);
    }

    @Test
    void managementPageFiltersByStatusAndKeywordWithStablePaging() {
        long first = repository.insert("repo-test-page-a", "Alpha Member", "hash", "ACTIVE");
        long second = repository.insert("repo-test-page-b", "Beta Member", "hash", "DISABLED");

        assertThat(repository.findManagementPage("ACTIVE", "Alpha", 1, 20))
                .extracting(UserRepository.UserManagementRow::id)
                .containsExactly(first);
        assertThat(repository.findManagementPage(null, "Member", 1, 1))
                .extracting(UserRepository.UserManagementRow::id)
                .containsExactly(first);
        assertThat(repository.countManagementUsers(null, "Member")).isEqualTo(2L);
        assertThat(repository.findManagementUser(second)).get()
                .extracting(UserRepository.UserManagementRow::status)
                .isEqualTo("DISABLED");
    }
}
