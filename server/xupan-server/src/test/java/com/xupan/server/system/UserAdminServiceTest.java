package com.xupan.server.system;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.system.service.UserAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UserAdminServiceTest {

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBeforeEach() {
        cleanData();
    }

    @AfterEach
    void cleanAfterEach() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE resource_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'user-admin-test-%')");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE user_code LIKE 'USER-%')");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'user-admin-test-%')");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'user-admin-test-%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'user-admin-test-%'");
    }

    @Test
    void createsUserWithZeroBalanceWalletAndIsIdempotentForWalletInitialization() {
        long operatorId = userRepository.insert("user-admin-test-operator", "操作管理员", "hash", "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");

        long userId = userAdminService.createUser("user-admin-test-member", "成员用户",
                "MemberPassword123", "USER", operatorId);

        assertThat(userRepository.findByUsername("user-admin-test-member")).get()
                .extracting(user -> user.id(), user -> user.displayName(), user -> user.status())
                .containsExactly(userId, "成员用户", "ACTIVE");
        assertThat(walletService.getForAdmin(userId))
                .extracting(wallet -> wallet.userId(), wallet -> wallet.userCode(), wallet -> wallet.balance())
                .containsExactly(userId, "USER-" + userId, new java.math.BigDecimal("0.00"));
        assertThat(walletService.ensureWalletForUser(userId, "其他显示名")).isEqualTo(
                walletService.getForAdmin(userId).accountId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE operator_user_id = ? AND resource_id = ?",
                Integer.class, operatorId, Long.toString(userId))).isEqualTo(1);
    }
}
