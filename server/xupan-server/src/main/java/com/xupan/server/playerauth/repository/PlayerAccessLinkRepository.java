package com.xupan.server.playerauth.repository;

import com.xupan.server.playerauth.domain.PlayerAccessLink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class PlayerAccessLinkRepository {

    private static final String COLUMNS = """
            l.id, l.user_id, l.token_hash, l.token_ciphertext, l.scope, l.expires_at, l.revoked_at,
            l.last_used_at, l.created_by, l.created_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public PlayerAccessLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void insert(PlayerAccessLink link) {
        jdbcTemplate.update("""
                INSERT INTO player_access_link
                    (user_id, token_hash, token_ciphertext, scope, expires_at, revoked_at, last_used_at, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, link.userId(), link.tokenHash(), link.tokenCiphertext(), link.scope(), timestamp(link.expiresAt()),
                timestamp(link.revokedAt()), timestamp(link.lastUsedAt()), link.createdBy(), timestamp(link.createdAt()));
    }

    @Transactional
    public Optional<PlayerAccessLink> findUsableByHashForUpdate(String tokenHash, Instant now) {
        return jdbcTemplate.query("""
                SELECT %s
                  FROM player_access_link l
                  JOIN sys_user u ON u.id = l.user_id
                  JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE l.token_hash = ?
                   AND l.scope = 'PLAYER_FULL'
                   AND l.revoked_at IS NULL
                   AND l.expires_at > ?
                   AND u.status = 'ACTIVE'
                   AND a.status = 'ACTIVE'
                   AND a.player_kind = 'NORMAL'
                FOR UPDATE
                """.formatted(COLUMNS), this::map, tokenHash, timestamp(now)).stream().findFirst();
    }

    @Transactional
    public int touchUsed(long linkId, Instant usedAt) {
        return jdbcTemplate.update("""
                UPDATE player_access_link
                   SET last_used_at = ?
                   WHERE id = ? AND revoked_at IS NULL
                """, timestamp(usedAt), linkId);
    }

    @Transactional
    public int revoke(long linkId, long userId, Instant revokedAt) {
        return jdbcTemplate.update("""
                UPDATE player_access_link
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE id = ? AND user_id = ?
                """, timestamp(revokedAt), linkId, userId);
    }

    @Transactional
    public int restore(long linkId, long userId) {
        return jdbcTemplate.update("""
                UPDATE player_access_link
                   SET revoked_at = NULL
                 WHERE id = ? AND user_id = ?
                """, linkId, userId);
    }

    @Transactional
    public int revokeAllByUserId(long userId, Instant revokedAt) {
        return jdbcTemplate.update("""
                UPDATE player_access_link
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE user_id = ? AND revoked_at IS NULL
                """, timestamp(revokedAt), userId);
    }

    @Transactional
    public void saveConfiguredDays(long userId, int days) {
        if (jdbcTemplate.update("UPDATE player_link_expiration_config SET days=?, updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                days, userId) == 0) {
            try {
                jdbcTemplate.update("INSERT INTO player_link_expiration_config(user_id, days) VALUES (?, ?)", userId, days);
            } catch (DuplicateKeyException ignored) {
                jdbcTemplate.update("UPDATE player_link_expiration_config SET days=?, updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                        days, userId);
            }
        }
    }

    public int findConfiguredDays(long userId, int fallbackDays) {
        Integer days = jdbcTemplate.query("SELECT days FROM player_link_expiration_config WHERE user_id=?",
                (rs, n) -> rs.getInt("days"), userId).stream().findFirst().orElse(null);
        return days == null ? fallbackDays : days;
    }

    public Optional<PlayerAccessLink> findByIdAndUserId(long linkId, long userId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM player_access_link l WHERE l.id = ? AND l.user_id = ?",
                this::map, linkId, userId).stream().findFirst();
    }

    public Optional<PlayerAccessLink> findByHash(String tokenHash) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM player_access_link l WHERE l.token_hash = ?",
                this::map, tokenHash).stream().findFirst();
    }

    public Optional<PlayerAccessLink> findLatestByUserId(long userId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM player_access_link l "
                        + "WHERE l.user_id=? ORDER BY l.created_at DESC, l.id DESC LIMIT 1",
                this::map, userId).stream().findFirst();
    }

    private PlayerAccessLink map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PlayerAccessLink(rs.getLong("id"), rs.getLong("user_id"), rs.getString("token_hash"),
                rs.getString("token_ciphertext"), rs.getString("scope"), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("revoked_at")), instant(rs.getTimestamp("last_used_at")),
                rs.getLong("created_by"), instant(rs.getTimestamp("created_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
