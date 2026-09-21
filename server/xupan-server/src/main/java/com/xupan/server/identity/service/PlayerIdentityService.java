package com.xupan.server.identity.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
public class PlayerIdentityService {

    private static final char[] CODE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public PlayerIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PlayerIdentity ensureForUser(long userId, long accountId) {
        if (userId <= 0 || accountId <= 0) {
            throw new IllegalArgumentException("玩家身份关联必须为正数");
        }
        jdbc.update("""
                UPDATE sys_user
                   SET internal_code = COALESCE(internal_code, ?),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, newInternalCode(), userId);
        jdbc.update("""
                UPDATE demo_user_account
                   SET member_code = COALESCE(member_code, ?),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, nextMemberCode(accountId), accountId);
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

    public String nextMemberCode(long accountId) {
        if (accountId <= 0) {
            throw new IllegalArgumentException("账户编号必须为正数");
        }
        return "v" + accountId;
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
