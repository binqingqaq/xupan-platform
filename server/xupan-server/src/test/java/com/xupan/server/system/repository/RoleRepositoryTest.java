package com.xupan.server.system.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RoleRepositoryTest {

    @Autowired
    private RoleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM sys_role WHERE role_code LIKE 'ROLE_REPO_TEST_%'");
    }

    @Test
    void returnsOnlyActiveRolesAndLocksAllAdministratorsForLastAdminChecks() {
        jdbcTemplate.update("INSERT INTO sys_role (role_code, display_name, status) VALUES (?, ?, ?)",
                "ROLE_REPO_TEST_DISABLED", "停用角色", "DISABLED");
        jdbcTemplate.update("INSERT INTO sys_role (role_code, display_name, status) VALUES (?, ?, ?)",
                "ROLE_REPO_TEST_ACTIVE", "启用角色", "ACTIVE");

        assertThat(repository.findActiveByCodes(List.of("role_repo_test_disabled", "role_repo_test_active")))
                .extracting(value -> value.roleCode())
                .containsExactly("ROLE_REPO_TEST_ACTIVE");
        assertThat(repository.findActiveRoles()).extracting(value -> value.roleCode())
                .contains("ADMIN", "USER", "ROLE_REPO_TEST_ACTIVE")
                .doesNotContain("ROLE_REPO_TEST_DISABLED");
    }
}
