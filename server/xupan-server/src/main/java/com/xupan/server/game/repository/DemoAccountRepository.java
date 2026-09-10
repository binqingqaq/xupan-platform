package com.xupan.server.game.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Repository
public class DemoAccountRepository {

    public static final String DEFAULT_USER_CODE = "DEMO-USER";

    private final JdbcTemplate jdbcTemplate;

    public DemoAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AccountRecord findByCode(String userCode) {
        return jdbcTemplate.queryForObject("""
                SELECT id, user_code, display_name, balance, status
                  FROM demo_user_account
                 WHERE user_code = ?
                """, (rs, rowNum) -> new AccountRecord(
                rs.getLong("id"), rs.getString("user_code"), rs.getString("display_name"),
                rs.getBigDecimal("balance"), rs.getString("status")), userCode);
    }

    public List<AccountRecord> findAll() {
        return jdbcTemplate.query("""
                SELECT id, user_code, display_name, balance, status
                  FROM demo_user_account
                 ORDER BY id
                """, (rs, rowNum) -> new AccountRecord(
                rs.getLong("id"), rs.getString("user_code"), rs.getString("display_name"),
                rs.getBigDecimal("balance"), rs.getString("status")));
    }

    public List<LedgerRecord> findLedger(String userCode) {
        return jdbcTemplate.query("""
                SELECT l.id, a.user_code, l.operation_type, l.amount, l.balance_before,
                       l.balance_after, l.reason, l.operator_name, l.created_at
                  FROM demo_balance_ledger l
                  JOIN demo_user_account a ON a.id = l.user_id
                 WHERE a.user_code = ?
                 ORDER BY l.id DESC
                """, (rs, rowNum) -> new LedgerRecord(
                rs.getLong("id"), rs.getString("user_code"), rs.getString("operation_type"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"), rs.getString("reason"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toInstant()), userCode);
    }

    public AccountRecord adjust(String userCode, BigDecimal delta, String operationType,
                                String reason, String operatorName) {
        AccountRecord current = findByCode(userCode);
        BigDecimal amount = money(delta);
        BigDecimal next = money(current.balance().add(amount));
        if (next.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("余额不足，不能完成本次调整");
        }
        int updated = jdbcTemplate.update("""
                UPDATE demo_user_account
                   SET balance = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND balance = ?
                """, next, current.id(), current.balance());
        if (updated != 1) {
            throw new IllegalStateException("账户余额已变化，请刷新后重试");
        }
        jdbcTemplate.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after,
                     reason, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, current.id(), operationType, amount, current.balance(), next, reason, operatorName);
        return findByCode(userCode);
    }

    public AccountRecord debit(String userCode, BigDecimal stake, String reason) {
        return adjust(userCode, money(stake).negate(), "BET_DEBIT", reason, "SYSTEM");
    }

    public AccountRecord credit(String userCode, BigDecimal amount, String reason) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return findByCode(userCode);
        }
        return adjust(userCode, money(amount), "SETTLEMENT_CREDIT", reason, "SYSTEM");
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record AccountRecord(long id, String userCode, String displayName,
                                BigDecimal balance, String status) {
    }

    public record LedgerRecord(long id, String userCode, String operationType,
                               BigDecimal amount, BigDecimal balanceBefore,
                               BigDecimal balanceAfter, String reason,
                               String operatorName, java.time.Instant createdAt) {
    }
}
