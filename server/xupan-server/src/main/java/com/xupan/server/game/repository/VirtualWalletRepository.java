package com.xupan.server.game.repository;

import com.xupan.server.game.domain.VirtualWallet;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletOperationType;
import com.xupan.server.game.domain.WalletStatistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class VirtualWalletRepository {

    private static final String WALLET_COLUMNS = """
            SELECT a.id AS account_id, a.sys_user_id AS user_id, a.user_code,
                   a.display_name, a.balance, a.status
              FROM demo_user_account a
            """;

    private static final String LEDGER_COLUMNS = """
            SELECT l.id, l.user_id AS account_id, a.sys_user_id AS user_id,
                   l.operation_type, l.amount, l.balance_before, l.balance_after,
                   l.operator_user_id, l.operator_name, l.idempotency_key,
                   l.related_bet_id, l.issue_number, l.reason, l.created_at
              FROM demo_balance_ledger l
              JOIN demo_user_account a ON a.id = l.user_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public VirtualWalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<VirtualWallet> findByUserId(long userId) {
        return jdbcTemplate.query(WALLET_COLUMNS + " WHERE a.sys_user_id = ?",
                walletMapper(), userId).stream().findFirst();
    }

    public Optional<VirtualWallet> findByUserIdForUpdate(long userId) {
        requireTransaction("findByUserIdForUpdate");
        return jdbcTemplate.query(WALLET_COLUMNS + " WHERE a.sys_user_id = ? FOR UPDATE",
                walletMapper(), userId).stream().findFirst();
    }

    /** Resolves a legacy account primary key to its formal sys_user identity. */
    public Optional<VirtualWallet> findByAccountId(long accountId) {
        return jdbcTemplate.query(WALLET_COLUMNS + " WHERE a.id = ? AND a.sys_user_id IS NOT NULL",
                walletMapper(), accountId).stream().findFirst();
    }

    @Transactional
    public long createForUser(long userId, String userCode, String displayName) {
        return createForIdentity(userId, userCode, displayName, "REAL");
    }

    @Transactional
    public long createForTestPlayer(long userId, String userCode, String displayName) {
        return createForIdentity(userId, userCode, displayName, "TEST");
    }

    private long createForIdentity(long userId, String userCode, String displayName,
                                   String identityType) {
        if (userId <= 0) {
            throw new IllegalArgumentException("WALLET_USER_INVALID: userId 必须为正数");
        }
        String code = requiredText(userCode, "userCode", 64);
        String name = requiredText(displayName, "displayName", 128);
        jdbcTemplate.update("""
                INSERT INTO demo_user_account
                    (sys_user_id, user_code, display_name, balance, status, identity_type)
                VALUES (?, ?, ?, 0.00, 'ACTIVE', ?)
                """, userId, code, name, identityType);
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM demo_user_account WHERE sys_user_id = ?", Long.class, userId);
        if (accountId == null) {
            throw new IllegalStateException("创建钱包后未取得账户 ID");
        }
        return accountId;
    }

    public Optional<WalletLedgerEntry> findLedgerByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(LEDGER_COLUMNS + " WHERE l.idempotency_key = ?",
                ledgerMapper(), idempotencyKey).stream().findFirst();
    }

    /** Reads the persisted request key without trusting a client-supplied bet code. */
    public Optional<BetRequestRecord> findBetByAccountIdAndIdempotencyKey(
            long accountId, String requestIdempotencyKey) {
        if (accountId <= 0 || requestIdempotencyKey == null || requestIdempotencyKey.isBlank()
                || requestIdempotencyKey.length() > 128) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                SELECT id, user_id, bet_code, issue_number, stake, request_idempotency_key
                  FROM game_bet
                 WHERE user_id = ? AND request_idempotency_key = ?
                """, (rs, rowNum) -> new BetRequestRecord(rs.getLong("id"),
                rs.getLong("user_id"), rs.getString("bet_code"), rs.getString("issue_number"),
                rs.getBigDecimal("stake"), rs.getString("request_idempotency_key")),
                accountId, requestIdempotencyKey).stream().findFirst();
    }

    /**
     * Compares a replayed request with its stored bet identity and business parameters.
     * A mismatch is a stable conflict rather than a second bet attempt.
     */
    public BetRequestRecord requireBetRequestMatch(long accountId, String requestIdempotencyKey,
                                                    String betCode, String issueNumber,
                                                    BigDecimal stake) {
        BetRequestRecord existing = findBetByAccountIdAndIdempotencyKey(accountId, requestIdempotencyKey)
                .orElseThrow(() -> new IllegalStateException("GAME_BET_IDEMPOTENCY_NOT_FOUND: 注单不存在"));
        BigDecimal normalizedStake = positiveMoney(stake);
        if (!existing.betCode().equals(betCode) || !existing.issueNumber().equals(issueNumber)
                || existing.stake().compareTo(normalizedStake) != 0) {
            throw new IllegalStateException("GAME_BET_IDEMPOTENCY_CONFLICT: 注单请求参数不一致");
        }
        return existing;
    }

    public WalletLedgerEntry appendAdminGrant(long operatorUserId, long targetUserId,
                                               BigDecimal amount, String reason,
                                               String idempotencyKey) {
        BigDecimal value = positiveMoney(amount, "WALLET_AMOUNT_INVALID");
        String safeReason = reason(reason);
        String key = idempotencyKey(idempotencyKey);
        VirtualWallet wallet = lockWallet(targetUserId);
        Optional<WalletLedgerEntry> replay = findLedgerByIdempotencyKey(key);
        if (replay.isPresent()) {
            return replayOrConflict(replay.get(), WalletOperationType.ADMIN_GRANT, value,
                    targetUserId, safeReason, null);
        }
        String operatorName = operatorName(operatorUserId);
        return append(wallet, WalletOperationType.ADMIN_GRANT, value, operatorUserId, operatorName,
                key, null, null, safeReason);
    }

    public WalletLedgerEntry appendAdminAdjustment(long operatorUserId, long targetUserId,
                                                    BigDecimal amount, String reason,
                                                    String idempotencyKey) {
        BigDecimal value = nonZeroMoney(amount, "WALLET_AMOUNT_INVALID");
        String safeReason = reason(reason);
        String key = idempotencyKey(idempotencyKey);
        VirtualWallet wallet = lockWallet(targetUserId);
        Optional<WalletLedgerEntry> replay = findLedgerByIdempotencyKey(key);
        if (replay.isPresent()) {
            return replayOrConflict(replay.get(), WalletOperationType.ADMIN_ADJUST, value,
                    targetUserId, safeReason, null);
        }
        String operatorName = operatorName(operatorUserId);
        return append(wallet, WalletOperationType.ADMIN_ADJUST, value, operatorUserId, operatorName,
                key, null, null, safeReason);
    }

    public WalletLedgerEntry appendAdminReset(long operatorUserId, long targetUserId,
                                               String reason, String idempotencyKey) {
        String safeReason = reason(reason);
        String key = idempotencyKey(idempotencyKey);
        VirtualWallet wallet = lockWallet(targetUserId);
        Optional<WalletLedgerEntry> replay = findLedgerByIdempotencyKey(key);
        if (replay.isPresent()) {
            WalletLedgerEntry existing = replay.get();
            if (existing.operationType() != WalletOperationType.ADMIN_RESET
                    || existing.userId() != targetUserId
                    || !existing.reason().equals(safeReason)) {
                throw new IllegalStateException("WALLET_IDEMPOTENCY_CONFLICT: 幂等键参数不一致");
            }
            return existing;
        }
        BigDecimal amount = money(wallet.balance().negate());
        if (amount.signum() == 0) {
            throw new IllegalStateException("WALLET_ALREADY_RESET: 钱包余额已经为零");
        }
        String operatorName = operatorName(operatorUserId);
        return append(wallet, WalletOperationType.ADMIN_RESET, amount, operatorUserId, operatorName,
                key, null, null, safeReason);
    }

    public WalletLedgerEntry appendBetDebit(long targetUserId, long betId,
                                             String betCode, String issueNumber,
                                             BigDecimal stake) {
        BigDecimal value = positiveMoney(stake, "WALLET_AMOUNT_INVALID");
        String code = requiredText(betCode, "betCode", 64);
        String issue = requiredText(issueNumber, "issueNumber", 64);
        String key = "BET_DEBIT:" + code;
        VirtualWallet wallet = lockWallet(targetUserId);
        BetReference bet = requireBet(betId, wallet.accountId(), code, issue, value);
        Optional<WalletLedgerEntry> replay = findLedgerByIdempotencyKey(key);
        if (replay.isPresent()) {
            return replayOrConflict(replay.get(), WalletOperationType.BET_DEBIT, value.negate(),
                    targetUserId, "下注扣款:" + code, betId);
        }
        return append(wallet, WalletOperationType.BET_DEBIT, value.negate(), null, "SYSTEM", key,
                betId, issue, "下注扣款:" + code);
    }

    public WalletLedgerEntry appendSettlementCredit(long targetUserId, long betId,
                                                      String issueNumber, BigDecimal amount,
                                                      String reason) {
        BigDecimal value = positiveMoney(amount, "WALLET_AMOUNT_INVALID");
        String issue = requiredText(issueNumber, "issueNumber", 64);
        String safeReason = reason(reason);
        String key = "SETTLEMENT:" + betId;
        VirtualWallet wallet = lockWalletForSettlement(targetUserId);
        requireBetForSettlement(betId, wallet.accountId(), issue);
        Optional<WalletLedgerEntry> replay = findLedgerByIdempotencyKey(key);
        if (replay.isPresent()) {
            return replayOrConflict(replay.get(), WalletOperationType.SETTLEMENT_CREDIT, value,
                    targetUserId, safeReason, betId);
        }
        return append(wallet, WalletOperationType.SETTLEMENT_CREDIT, value, null, "SYSTEM", key,
                betId, issue, safeReason);
    }

    public List<WalletLedgerEntry> findLedgerByUserId(long userId, int limit) {
        if (userId <= 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("钱包流水查询参数无效");
        }
        return jdbcTemplate.query(LEDGER_COLUMNS + """
                 WHERE a.sys_user_id = ?
                 ORDER BY l.id DESC
                 LIMIT ?
                """, ledgerMapper(), userId, limit);
    }

    public WalletStatistics findStatisticsByAccountId(long accountId) {
        if (accountId <= 0) {
            throw new IllegalArgumentException("钱包统计账户无效");
        }
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total_bet_count,
                       COALESCE(SUM(CASE WHEN settlement_status <> 'PENDING' THEN 1 ELSE 0 END), 0)
                           AS settled_bet_count,
                       COALESCE(SUM(CASE WHEN settlement_status = 'PENDING' THEN 1 ELSE 0 END), 0)
                           AS pending_bet_count,
                       COALESCE(SUM(stake), 0) AS total_stake,
                       COALESCE(SUM(CASE WHEN settlement_status <> 'PENDING' THEN stake ELSE 0 END), 0)
                           AS settled_stake,
                       COALESCE(SUM(CASE WHEN settlement_status = 'PENDING' THEN stake ELSE 0 END), 0)
                           AS pending_stake,
                       COALESCE(SUM(CASE WHEN settlement_status <> 'PENDING' THEN net_profit ELSE 0 END), 0)
                           AS net_profit
                  FROM game_bet
                 WHERE user_id = ?
                """, (rs, rowNum) -> new WalletStatistics(
                rs.getLong("total_bet_count"), rs.getLong("settled_bet_count"),
                rs.getLong("pending_bet_count"), rs.getBigDecimal("total_stake"),
                rs.getBigDecimal("settled_stake"), rs.getBigDecimal("pending_stake"),
                rs.getBigDecimal("net_profit")), accountId);
    }

    private WalletLedgerEntry append(VirtualWallet wallet, WalletOperationType operationType,
                                     BigDecimal amount, Long operatorUserId, String operatorName,
                                     String idempotencyKey, Long relatedBetId, String issueNumber,
                                     String reason) {
        BigDecimal before = money(wallet.balance());
        BigDecimal after = money(before.add(amount));
        if (after.signum() < 0) {
            throw new IllegalStateException("WALLET_INSUFFICIENT_BALANCE: 余额不足");
        }
        int updated = jdbcTemplate.update("""
                UPDATE demo_user_account
                   SET balance = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND balance = ?
                """, after, wallet.accountId(), before);
        if (updated != 1) {
            throw new IllegalStateException("WALLET_CONCURRENT_UPDATE: 钱包余额已变化");
        }
        jdbcTemplate.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after,
                     operator_user_id, operator_name, idempotency_key, related_bet_id,
                     issue_number, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, wallet.accountId(), operationType.name(), amount, before, after,
                operatorUserId, operatorName, idempotencyKey, relatedBetId, issueNumber, reason);
        return findLedgerByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("写入钱包流水后未找到流水"));
    }

    private VirtualWallet lockWallet(long userId) {
        requireTransaction("钱包写入");
        VirtualWallet wallet = findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("WALLET_NOT_FOUND: 正式用户没有钱包"));
        if (!"ACTIVE".equals(wallet.status())) {
            throw new IllegalStateException("WALLET_INACTIVE: 钱包未启用");
        }
        return wallet;
    }

    /**
     * Settlement must finish for valid historical bets even when the user was
     * disabled after placing the bet.
     */
    private VirtualWallet lockWalletForSettlement(long userId) {
        requireTransaction("钱包结算");
        return findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("WALLET_NOT_FOUND: 正式用户没有钱包"));
    }

    private String operatorName(long operatorUserId) {
        if (operatorUserId <= 0) {
            throw new IllegalArgumentException("WALLET_OPERATOR_INVALID: operatorUserId 必须为正数");
        }
        return jdbcTemplate.query("""
                SELECT display_name
                  FROM sys_user
                 WHERE id = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> rs.getString(1), operatorUserId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("WALLET_OPERATOR_NOT_FOUND: 操作人无效"));
    }

    private BetReference requireBet(long betId, long accountId, String betCode,
                                    String issueNumber, BigDecimal stake) {
        BetReference bet = jdbcTemplate.query("""
                SELECT user_id, bet_code, issue_number, stake
                  FROM game_bet
                 WHERE id = ?
                """, (rs, rowNum) -> new BetReference(rs.getLong("user_id"),
                rs.getString("bet_code"), rs.getString("issue_number"), rs.getBigDecimal("stake")), betId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("WALLET_BET_NOT_FOUND: 注单不存在"));
        if (bet.accountId() != accountId || !betCode.equals(bet.betCode())
                || !issueNumber.equals(bet.issueNumber()) || money(bet.stake()).compareTo(stake) != 0) {
            throw new IllegalStateException("WALLET_BET_MISMATCH: 注单与钱包操作不匹配");
        }
        return bet;
    }

    private void requireBetForSettlement(long betId, long accountId, String issueNumber) {
        BetReference bet = jdbcTemplate.query("""
                SELECT user_id, bet_code, issue_number, stake
                  FROM game_bet
                 WHERE id = ?
                """, (rs, rowNum) -> new BetReference(rs.getLong("user_id"),
                rs.getString("bet_code"), rs.getString("issue_number"), rs.getBigDecimal("stake")), betId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("WALLET_BET_NOT_FOUND: 注单不存在"));
        if (bet.accountId() != accountId || !issueNumber.equals(bet.issueNumber())) {
            throw new IllegalStateException("WALLET_BET_MISMATCH: 注单与钱包操作不匹配");
        }
    }

    private WalletLedgerEntry replayOrConflict(WalletLedgerEntry existing, WalletOperationType type,
                                                BigDecimal amount, long targetUserId,
                                                String reason, Long relatedBetId) {
        boolean same = existing.operationType() == type
                && existing.userId() == targetUserId
                && existing.amount().compareTo(amount) == 0
                && Objects.equals(existing.relatedBetId(), relatedBetId)
                && existing.reason().equals(reason);
        if (!same) {
            throw new IllegalStateException("WALLET_IDEMPOTENCY_CONFLICT: 幂等键参数不一致");
        }
        return existing;
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " 参数无效");
        }
        return value;
    }

    private static String reason(String value) {
        return requiredText(value, "reason", 255);
    }

    private static String idempotencyKey(String value) {
        return requiredText(value, "idempotencyKey", 128);
    }

    private static BigDecimal positiveMoney(BigDecimal value, String code) {
        BigDecimal normalized = nonZeroMoney(value, code);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(code + ": 金额必须为正数");
        }
        return normalized;
    }

    private static BigDecimal nonZeroMoney(BigDecimal value, String code) {
        if (value == null || value.scale() > 2 || value.signum() == 0) {
            throw new IllegalArgumentException(code + ": 金额必须为非零且最多两位小数");
        }
        return money(value);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal positiveMoney(BigDecimal value) {
        if (value == null || value.scale() > 2 || value.signum() <= 0) {
            throw new IllegalArgumentException("WALLET_AMOUNT_INVALID: 金额必须为正数且最多两位小数");
        }
        return money(value);
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }

    private static org.springframework.jdbc.core.RowMapper<VirtualWallet> walletMapper() {
        return (rs, rowNum) -> new VirtualWallet(rs.getLong("account_id"), rs.getLong("user_id"),
                rs.getString("user_code"), rs.getString("display_name"), rs.getBigDecimal("balance"),
                rs.getString("status"));
    }

    private static org.springframework.jdbc.core.RowMapper<WalletLedgerEntry> ledgerMapper() {
        return (rs, rowNum) -> new WalletLedgerEntry(rs.getLong("id"), rs.getLong("account_id"),
                rs.getLong("user_id"), WalletOperationType.valueOf(rs.getString("operation_type")),
                rs.getBigDecimal("amount"), rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"), nullableLong(rs, "operator_user_id"),
                rs.getString("operator_name"), rs.getString("idempotency_key"),
                nullableLong(rs, "related_bet_id"), rs.getString("issue_number"),
                rs.getString("reason"), rs.getTimestamp("created_at").toInstant());
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record BetReference(long accountId, String betCode, String issueNumber, BigDecimal stake) {
    }

    public record BetRequestRecord(long betId, long accountId, String betCode,
                                   String issueNumber, BigDecimal stake,
                                   String requestIdempotencyKey) {
    }
}
