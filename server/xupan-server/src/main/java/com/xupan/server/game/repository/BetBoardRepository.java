package com.xupan.server.game.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class BetBoardRepository {

    private final JdbcTemplate jdbcTemplate;

    public BetBoardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AggregateSummary summarize(String issueNumber) {
        Map<String, KindAggregate> kinds = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT a.player_kind, COUNT(*) AS bet_count, COALESCE(SUM(b.stake), 0) AS total_stake
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.issue_number = ?
                   AND b.settlement_status <> 'CANCELED'
                 GROUP BY a.player_kind
                """, rs -> {
            kinds.put(rs.getString("player_kind"), new KindAggregate(
                    rs.getLong("bet_count"), money(rs.getBigDecimal("total_stake"))));
        }, issueNumber);
        KindAggregate normal = kinds.getOrDefault("NORMAL", new KindAggregate(0, BigDecimal.ZERO.setScale(2)));
        KindAggregate bot = kinds.getOrDefault("BOT", new KindAggregate(0, BigDecimal.ZERO.setScale(2)));
        return new AggregateSummary(normal, bot);
    }

    public List<BetItem> findBets(String issueNumber, String playerKind, int limit) {
        return jdbcTemplate.query("""
                SELECT b.id, a.display_name, a.player_kind, b.play_type, b.parameters_text,
                       b.stake, b.settlement_status, b.created_at
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.issue_number = ?
                   AND a.player_kind = ?
                   AND b.settlement_status <> 'CANCELED'
                 ORDER BY b.created_at DESC, b.id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new BetItem(
                rs.getLong("id"),
                rs.getString("display_name"),
                rs.getString("player_kind"),
                rs.getString("play_type"),
                rs.getString("parameters_text"),
                money(rs.getBigDecimal("stake")),
                rs.getString("settlement_status"),
                rs.getTimestamp("created_at").toInstant()
        ), issueNumber, playerKind, limit);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    public record AggregateSummary(KindAggregate normal, KindAggregate bot) {
    }

    public record KindAggregate(long count, BigDecimal totalStake) {
        public KindAggregate {
            totalStake = totalStake == null ? BigDecimal.ZERO.setScale(2) : totalStake.setScale(2);
        }
    }

    public record BetItem(long id, String displayName, String playerKind, String playType,
                          String parametersText, BigDecimal stake, String settlementStatus,
                          java.time.Instant createdAt) {
    }
}
