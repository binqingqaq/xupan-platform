package com.xupan.server.game.repository;

import com.xupan.server.game.domain.WalletOperationType;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class VirtualWalletRepositoryTest {

    @Autowired
    private VirtualWalletRepository repository;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureLegacyDemoAccount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE user_code = 'DEMO-USER'", Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO demo_user_account (user_code, display_name, balance) VALUES (?, ?, ?)",
                    "DEMO-USER", "演示用户", new BigDecimal("1000.00"));
        }
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE user_code LIKE 'WALLET-REPO-%')");
        jdbcTemplate.update("DELETE FROM game_bet WHERE bet_code LIKE 'WALLET-REPO-%'");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE user_code LIKE 'WALLET-REPO-%'");
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'wallet-repo-test-%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'wallet-repo-test-%'");
    }

    @Test
    void v8KeepsLegacyDemoAccountAndAddsWalletAndBetIdempotencyConstraints() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE user_code = 'DEMO-USER'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'GAME_BET' AND column_name = 'REQUEST_IDEMPOTENCY_KEY'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'GAME_BET' "
          + "AND constraint_name = 'UK_GAME_BET_USER_REQUEST_KEY'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_permission WHERE permission_code IN "
                        + "('WALLET_READ', 'WALLET_GRANT', 'WALLET_ADJUST', 'WALLET_LEDGER_READ')",
                Integer.class)).isEqualTo(4);
    }

    @Test
    void accountIdResolvesToFormalSysUserId() {
        long userId = insertUser("wallet-repo-test-mapping", "映射测试用户");
        long accountId = repository.createForUser(userId, "WALLET-REPO-MAPPING", "映射测试用户");

        assertThat(repository.findByUserId(userId)).get()
                .extracting(wallet -> wallet.accountId(), wallet -> wallet.userId(),
                        wallet -> wallet.balance(), wallet -> wallet.status())
                .containsExactly(accountId, userId, new BigDecimal("0.00"), "ACTIVE");
        assertThat(repository.findByAccountId(accountId)).get()
                .extracting(wallet -> wallet.accountId(), wallet -> wallet.userId())
                .containsExactly(accountId, userId);
        assertThat(repository.findByAccountId(1L)).isEmpty();
    }

    @Test
    @Transactional
    void appendsMoneyWithLockAndReplaysEquivalentAdminRequest() {
        long operatorId = insertUser("wallet-repo-test-admin", "测试管理员");
        long targetId = insertUser("wallet-repo-test-member", "测试成员");
        repository.createForUser(targetId, "WALLET-REPO-MEMBER", "测试成员");

        var first = repository.appendAdminGrant(operatorId, targetId,
                new BigDecimal("1000.00"), "钱包仓储测试分配", "wallet-grant-1");
        var replay = repository.appendAdminGrant(operatorId, targetId,
                new BigDecimal("1000.00"), "钱包仓储测试分配", "wallet-grant-1");

        assertThat(replay).isEqualTo(first);
        assertThat(repository.findByUserIdForUpdate(targetId)).get()
                .extracting(wallet -> wallet.balance())
                .isEqualTo(new BigDecimal("1000.00"));
        assertThat(first).extracting(entry -> entry.operationType(), entry -> entry.operatorUserId(),
                        entry -> entry.operatorName(), entry -> entry.amount(), entry -> entry.balanceAfter())
                .containsExactly(WalletOperationType.ADMIN_GRANT, operatorId, "测试管理员",
                        new BigDecimal("1000.00"), new BigDecimal("1000.00"));

        assertThatThrownBy(() -> repository.appendAdminGrant(operatorId, targetId,
                new BigDecimal("10.00"), "钱包仓储测试分配", "wallet-grant-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WALLET_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> repository.appendAdminAdjustment(operatorId, targetId,
                new BigDecimal("-1001.00"), "钱包仓储测试扣减", "wallet-adjust-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WALLET_INSUFFICIENT_BALANCE");
        assertThat(repository.findLedgerByUserId(targetId, 100)).hasSize(1);
    }

    @Test
    @Transactional
    void readsBetByAccountRequestKeyAndWritesIdempotentDebit() {
        long targetId = insertUser("wallet-repo-test-bet", "下注测试用户");
        long accountId = repository.createForUser(targetId, "WALLET-REPO-BET", "下注测试用户");
        repository.appendAdminGrant(targetId, targetId, new BigDecimal("100.00"),
                "钱包仓储测试初始额度", "wallet-grant-bet");
        insertBet(accountId, "WALLET-REPO-BET-1", "WALLET-ISSUE-1", "request-1", "10.00");
        long betId = jdbcTemplate.queryForObject(
                "SELECT id FROM game_bet WHERE bet_code = ?", Long.class, "WALLET-REPO-BET-1");
        assertThatThrownBy(() -> insertBet(accountId, "WALLET-REPO-BET-2", "WALLET-ISSUE-1",
                "request-1", "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Optional<VirtualWalletRepository.BetRequestRecord> found =
                repository.findBetByAccountIdAndIdempotencyKey(accountId, "request-1");
        assertThat(found).get().extracting(record -> record.betId(), record -> record.accountId(),
                record -> record.requestIdempotencyKey()).containsExactly(betId, accountId, "request-1");
        assertThat(repository.requireBetRequestMatch(accountId, "request-1", "WALLET-REPO-BET-1",
                "WALLET-ISSUE-1", new BigDecimal("10.00"))).isEqualTo(found.orElseThrow());
        assertThatThrownBy(() -> repository.requireBetRequestMatch(accountId, "request-1",
                "WALLET-REPO-BET-OTHER", "WALLET-ISSUE-1", new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GAME_BET_IDEMPOTENCY_CONFLICT");

        var debit = repository.appendBetDebit(targetId, betId, "WALLET-REPO-BET-1",
                "WALLET-ISSUE-1", new BigDecimal("10.00"));
        var replay = repository.appendBetDebit(targetId, betId, "WALLET-REPO-BET-1",
                "WALLET-ISSUE-1", new BigDecimal("10.00"));
        assertThat(replay).isEqualTo(debit);
        assertThat(repository.findByAccountId(accountId)).get()
                .extracting(wallet -> wallet.userId(), wallet -> wallet.balance())
                .containsExactly(targetId, new BigDecimal("90.00"));
    }

    @Test
    @Transactional
    void settlesHistoricalBetAfterUserIsDisabled() {
        long userId = insertUser("wallet-repo-test-disabled-settlement", "停用结算用户");
        long accountId = repository.createForUser(userId, "WALLET-REPO-DISABLED", "停用结算用户");
        repository.appendAdminGrant(userId, userId, new BigDecimal("100.00"),
                "停用结算测试初始化", "wallet-disabled-grant");
        insertBet(accountId, "WALLET-REPO-DISABLED-BET", "WALLET-DISABLED-ISSUE", null, "10.00");
        long betId = jdbcTemplate.queryForObject(
                "SELECT id FROM game_bet WHERE bet_code = ?", Long.class, "WALLET-REPO-DISABLED-BET");
        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE id = ?", userId);

        var credit = repository.appendSettlementCredit(userId, betId, "WALLET-DISABLED-ISSUE",
                new BigDecimal("38.50"), "停用用户历史注单结算");

        assertThat(credit.operationType()).isEqualTo(WalletOperationType.SETTLEMENT_CREDIT);
        assertThat(repository.findByUserId(userId)).get()
                .extracting(wallet -> wallet.balance())
                .isEqualTo(new BigDecimal("138.50"));
    }

    @Test
    void concurrentAdjustmentsCannotOverdrawWallet() throws Exception {
        long operatorId = insertUser("wallet-repo-test-concurrent-operator", "并发操作员");
        long targetId = insertUser("wallet-repo-test-concurrent-target", "并发目标用户");
        repository.createForUser(targetId, "WALLET-REPO-CONCURRENT", "并发目标用户");
        walletService.grant(operatorId, targetId, new BigDecimal("100.00"),
                "并发扣款测试初始化", "wallet-concurrent-grant");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> concurrentAdjustment(
                    operatorId, targetId, "wallet-concurrent-adjust-1"));
            Future<String> second = executor.submit(() -> concurrentAdjustment(
                    operatorId, targetId, "wallet-concurrent-adjust-2"));

            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "WALLET_INSUFFICIENT_BALANCE");
            assertThat(repository.findByUserId(targetId)).get()
                    .extracting(wallet -> wallet.balance())
                    .isEqualTo(new BigDecimal("20.00"));
        } finally {
            executor.shutdownNow();
        }
    }

    private String concurrentAdjustment(long operatorId, long targetId, String idempotencyKey) {
        try {
            walletService.adjust(operatorId, targetId, new BigDecimal("-80.00"),
                    "并发扣款测试", idempotencyKey);
            return "SUCCESS";
        } catch (com.xupan.server.web.BusinessException exception) {
            return exception.code();
        }
    }

    @Test
    void lockingWriteRequiresAnOuterTransaction() {
        long userId = insertUser("wallet-repo-test-lock", "锁测试用户");
        repository.createForUser(userId, "WALLET-REPO-LOCK", "锁测试用户");

        assertThatThrownBy(() -> repository.findByUserIdForUpdate(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("外层事务");
        assertThatThrownBy(() -> repository.appendAdminGrant(userId, userId,
                new BigDecimal("1.00"), "钱包仓储测试锁", "wallet-lock-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("外层事务");
    }

    private long insertUser(String username, String displayName) {
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, display_name, password_hash) VALUES (?, ?, ?)",
                username, displayName, "test-password-hash");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private void insertBet(long accountId, String betCode, String issueNumber,
                           String requestIdempotencyKey, String stake) {
        jdbcTemplate.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, issue_number, ball_number, play_type, parameters_text,
                     stake, odds_snapshot, request_idempotency_key)
                VALUES (?, ?, ?, 1, 'FAN', '1', ?, 3.850, ?)
                """, accountId, betCode, issueNumber, new BigDecimal(stake), requestIdempotencyKey);
    }
}
