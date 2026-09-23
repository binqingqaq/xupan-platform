package com.xupan.server.identity.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Set;

@Service
public class PlayerIdentityService {

    private static final char[] CODE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PLAYER_KINDS = Set.of("NORMAL", "BOT");

    private final JdbcTemplate jdbc;

    public PlayerIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PlayerIdentity ensureForUser(long userId, long accountId) {
        if (userId <= 0 || accountId <= 0) {
            throw new IllegalArgumentException("玩家身份关联必须为正数");
        }
        String currentInternalCode = jdbc.queryForObject(
                "SELECT internal_code FROM sys_user WHERE id = ?", String.class, userId);
        String currentMemberCode = jdbc.queryForObject(
                "SELECT member_code FROM demo_user_account WHERE id = ?", String.class, accountId);
        String playerKind = jdbc.queryForObject(
                "SELECT player_kind FROM demo_user_account WHERE id = ?", String.class, accountId);
        jdbc.update("""
                UPDATE sys_user
                   SET internal_code = COALESCE(internal_code, ?),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, currentInternalCode == null ? newInternalCode() : currentInternalCode, userId);
        jdbc.update("""
                UPDATE demo_user_account
                   SET member_code = COALESCE(member_code, ?),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, currentMemberCode == null ? nextMemberCode(playerKind) : currentMemberCode, accountId);
        return requireByUserId(userId);
    }

    @Transactional(readOnly = true)
    public PlayerIdentity requireByUserId(long userId) {
        return jdbc.query("""
                SELECT u.id, u.internal_code, a.id AS account_id, a.member_code,
                       u.display_name, a.player_kind
                  FROM sys_user u
                  JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE u.id = ?
                """, (rs, rowNum) -> new PlayerIdentity(
                rs.getLong("id"), rs.getString("internal_code"),
                rs.getLong("account_id"), rs.getString("member_code"),
                rs.getString("display_name"), rs.getString("player_kind")), userId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("玩家身份不存在: " + userId));
    }

    @Transactional
    public String nextMemberCode(String playerKind) {
        String normalizedKind = playerKind == null || playerKind.isBlank() ? "NORMAL" : playerKind.trim().toUpperCase();
        if (!PLAYER_KINDS.contains(normalizedKind)) {
            throw new IllegalArgumentException("玩家分类无效: " + playerKind);
        }
        int updated = jdbc.update("""
                UPDATE player_member_code_sequence
                   SET next_value = next_value + 1
                 WHERE player_kind = ?
                """, normalizedKind);
        if (updated != 1) {
            throw new IllegalStateException("玩家会员编号序列不存在: " + normalizedKind);
        }
        Long allocated = jdbc.queryForObject("""
                SELECT next_value - 1
                  FROM player_member_code_sequence
                 WHERE player_kind = ?
                 FOR UPDATE
                """, Long.class, normalizedKind);
        if (allocated == null || allocated < 100) {
            throw new IllegalStateException("玩家会员编号序列无效: " + normalizedKind);
        }
        String candidate = "v" + allocated;
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE member_code = ?", Integer.class, candidate);
        return existing != null && existing > 0 ? nextMemberCode(normalizedKind) : candidate;
    }

    public String newInternalCode() {
        StringBuilder suffix = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            suffix.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return "wxid_" + suffix;
    }

    public record PlayerIdentity(long userId, String internalCode, long accountId,
                                 String memberCode, String displayName, String playerKind) {
    }
}
