package com.xupan.server.system;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.system.service.UserAdminService;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "MemberPassword123", operatorId);

        assertThat(userRepository.findByUsername("user-admin-test-member")).get()
                .extracting(user -> user.id(), user -> user.displayName(), user -> user.status())
                .containsExactly(userId, "成员用户", "ACTIVE");
        assertThat(walletService.getForAdmin(userId))
                .extracting(wallet -> wallet.userId(), wallet -> wallet.userCode(), wallet -> wallet.balance())
                .containsExactly(userId, "USER-" + userId, new java.math.BigDecimal("0.00"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT internal_code FROM sys_user WHERE id = ?", String.class, userId))
                .matches("^wxid_[A-Za-z0-9]{16}$");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT member_code FROM demo_user_account WHERE sys_user_id = ?", String.class, userId))
                .matches("^v[0-9]+$");
        assertThat(walletService.ensureWalletForUser(userId, "其他显示名")).isEqualTo(
                walletService.getForAdmin(userId).accountId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE operator_user_id = ? AND resource_id = ?",
                Integer.class, operatorId, Long.toString(userId))).isEqualTo(1);
    }

    @Test
    void managesStatusPasswordAndRolesWithSecurityVersionAndAudit() {
        long operatorId = userRepository.insert("user-admin-test-operator-2", "操作管理员", "hash", "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");
        long targetId = userRepository.insert("user-admin-test-target", "目标用户", "old-hash", "ACTIVE");
        userRepository.assignRole(targetId, "USER");
        long initialVersion = userRepository.findById(targetId).orElseThrow().securityVersion();

        userAdminService.changeStatus(targetId, "LOCKED", operatorId);
        UserAccount locked = userRepository.findById(targetId).orElseThrow();
        assertThat(locked.status()).isEqualTo("LOCKED");
        assertThat(locked.securityVersion()).isEqualTo(initialVersion + 1);

        userAdminService.resetPassword(targetId, "ChangedPassword123", operatorId);
        UserAccount passwordChanged = userRepository.findById(targetId).orElseThrow();
        assertThat(passwordChanged.passwordHash()).isNotEqualTo("old-hash");
        assertThat(passwordChanged.securityVersion()).isEqualTo(initialVersion + 2);

        userAdminService.replaceRoles(targetId, java.util.List.of("OPERATOR"), operatorId);
        UserAccount roleChanged = userRepository.findById(targetId).orElseThrow();
        assertThat(userRepository.findRoleCodes(targetId)).containsExactly("OPERATOR");
        assertThat(roleChanged.securityVersion()).isEqualTo(initialVersion + 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE operator_user_id = ? AND resource_id = ?",
                Integer.class, operatorId, Long.toString(targetId))).isEqualTo(3);
    }

    @Test
    void rejectsInvalidStatusAndProtectsLastAdmin() {
        long operatorId = userRepository.insert("user-admin-test-operator-3", "操作管理员", "hash", "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");
        long targetId = userRepository.insert("user-admin-test-target-2", "目标用户", "hash", "ACTIVE");
        userRepository.assignRole(targetId, "USER");

        assertThatThrownBy(() -> userAdminService.changeStatus(targetId, "UNKNOWN", operatorId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("USER_STATUS_INVALID");
        assertThatThrownBy(() -> userAdminService.replaceRoles(operatorId, java.util.List.of("USER"), operatorId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("USER_SELF_OPERATION_FORBIDDEN");
    }
}
