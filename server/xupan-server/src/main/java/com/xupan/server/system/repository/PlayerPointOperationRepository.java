package com.xupan.server.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class PlayerPointOperationRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlayerPointOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Operation> findRecent(String playerKind, Instant fromInclusive, Instant toExclusive,
                                      Long beforeId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, u.id AS user_id, a.member_code, u.display_name, a.player_kind,
                       l.operation_type, l.amount, l.balance_after, l.reason, l.created_at
                  FROM demo_balance_ledger l
                  JOIN demo_user_account a ON a.id = l.user_id
                  JOIN sys_user u ON u.id = a.sys_user_id
                 WHERE a.player_kind = ?
                   AND l.operation_type IN ('ADMIN_GRANT', 'ADMIN_ADJUST')
                   AND l.created_at >= ? AND l.created_at < ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(playerKind);
        arguments.add(Timestamp.from(fromInclusive));
        arguments.add(Timestamp.from(toExclusive));
        if (beforeId != null) {
            sql.append("   AND l.id < ?\n");
            arguments.add(beforeId);
        }
        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT ?");
        arguments.add(limit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new Operation(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("member_code"),
                rs.getString("display_name"),
                rs.getString("player_kind"),
                rs.getString("operation_type"),
                money(rs.getBigDecimal("amount")),
                money(rs.getBigDecimal("balance_after")),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()
        ), arguments.toArray());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    public record Operation(long ledgerId, long userId, String memberCode, String displayName,
                            String playerKind, String operationType, BigDecimal amount,
                            BigDecimal balanceAfter, String reason, Instant createdAt) {
    }
}
