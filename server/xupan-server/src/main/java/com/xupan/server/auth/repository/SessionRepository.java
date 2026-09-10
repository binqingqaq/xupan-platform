package com.xupan.server.auth.repository;

import com.xupan.server.auth.domain.SessionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class SessionRepository {

    private static final String SESSION_COLUMNS = """
            s.id, s.session_id, s.user_id, s.access_token_hash, s.access_expires_at,
            s.refresh_token_hash, s.refresh_expires_at, s.device_label, s.ip_digest,
            s.user_agent_digest, s.last_seen_at, s.revoked_at, s.created_at,
            u.security_version AS user_security_version
            """;

    private final JdbcTemplate jdbcTemplate;

    public SessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void insert(SessionRecord session) {
        jdbcTemplate.update("""
                INSERT INTO auth_session
                    (session_id, user_id, access_token_hash, access_expires_at,
                     refresh_token_hash, refresh_expires_at, device_label, ip_digest,
                     user_agent_digest, last_seen_at, revoked_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session.sessionId(), session.userId(), session.accessTokenHash(),
                timestamp(session.accessExpiresAt()), session.refreshTokenHash(), timestamp(session.refreshExpiresAt()),
                session.deviceLabel(), session.ipDigest(), session.userAgentDigest(), timestamp(session.lastSeenAt()),
                timestamp(session.revokedAt()), timestamp(session.createdAt()));
    }

    public Optional<SessionRecord> findByAccessTokenHash(String tokenHash) {
        return find("WHERE s.access_token_hash = ?", tokenHash);
    }

    public Optional<SessionRecord> findByRefreshTokenHash(String tokenHash) {
        return find("WHERE s.refresh_token_hash = ?", tokenHash);
    }

    public Optional<SessionRecord> findBySessionId(String sessionId) {
        return find("WHERE s.session_id = ?", sessionId);
    }

    @Transactional
    public int rotateTokens(String sessionId, String accessHash, Instant accessExpires,
                            String refreshHash, Instant refreshExpires) {
        return jdbcTemplate.update("""
                UPDATE auth_session
                   SET access_token_hash = ?,
                       access_expires_at = ?,
                       refresh_token_hash = ?,
                       refresh_expires_at = ?,
                       last_seen_at = CURRENT_TIMESTAMP
                 WHERE session_id = ?
                   AND revoked_at IS NULL
                """, accessHash, timestamp(accessExpires), refreshHash, timestamp(refreshExpires), sessionId);
    }

    /** Atomically rotates only when the caller still owns the current refresh-token digest. */
    @Transactional
    public boolean rotateTokensIfCurrent(String sessionId, String expectedRefreshHash,
                                         String accessHash, Instant accessExpires,
                                         String refreshHash, Instant refreshExpires) {
        return jdbcTemplate.update("""
                UPDATE auth_session
                   SET access_token_hash = ?,
                       access_expires_at = ?,
                       refresh_token_hash = ?,
                       refresh_expires_at = ?,
                       last_seen_at = CURRENT_TIMESTAMP
                 WHERE session_id = ?
                   AND refresh_token_hash = ?
                   AND revoked_at IS NULL
                """, accessHash, timestamp(accessExpires), refreshHash, timestamp(refreshExpires),
                sessionId, expectedRefreshHash) == 1;
    }

    @Transactional
    public boolean revoke(String sessionId, Instant revokedAt) {
        return jdbcTemplate.update("""
                UPDATE auth_session
                   SET revoked_at = ?, last_seen_at = COALESCE(last_seen_at, ?)
                 WHERE session_id = ? AND revoked_at IS NULL
                """, timestamp(revokedAt), timestamp(revokedAt), sessionId) == 1;
    }

    @Transactional
    public int revokeAllByUserId(long userId, Instant revokedAt) {
        return jdbcTemplate.update("""
                UPDATE auth_session
                   SET revoked_at = ?, last_seen_at = COALESCE(last_seen_at, ?)
                 WHERE user_id = ? AND revoked_at IS NULL
                """, timestamp(revokedAt), timestamp(revokedAt), userId);
    }

    @Transactional
    public boolean touch(String sessionId, Instant lastSeenAt) {
        return jdbcTemplate.update("""
                UPDATE auth_session SET last_seen_at = ?
                 WHERE session_id = ? AND revoked_at IS NULL
                """, timestamp(lastSeenAt), sessionId) == 1;
    }

    private Optional<SessionRecord> find(String predicate, Object... args) {
        return jdbcTemplate.query("SELECT " + SESSION_COLUMNS + " FROM auth_session s "
                        + "JOIN sys_user u ON u.id = s.user_id " + predicate,
                (rs, rowNum) -> new SessionRecord(
                        rs.getLong("id"), rs.getString("session_id"), rs.getLong("user_id"),
                        rs.getString("access_token_hash"), instant(rs.getTimestamp("access_expires_at")),
                        rs.getString("refresh_token_hash"), instant(rs.getTimestamp("refresh_expires_at")),
                        rs.getString("device_label"), rs.getString("ip_digest"),
                        rs.getString("user_agent_digest"), instant(rs.getTimestamp("last_seen_at")),
                        instant(rs.getTimestamp("revoked_at")), instant(rs.getTimestamp("created_at")),
                        rs.getLong("user_security_version")), args).stream().findFirst();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
