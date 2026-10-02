package com.xupan.server.center.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 中心上游代理 Token 的生成与校验。
 *
 * <p>账本原则 9：<b>Token 只保存哈希，原文只在创建时返回一次</b>，不进入日志、不进入前端持久化、
 * 不进入审计摘要。</p>
 *
 * <p>Token 是中心识别"是哪个代理"的唯一依据（《代理报网盘口与结算业务规则说明》§14：
 * 盘口 A/B/C/D 不是代理身份，代理身份靠代理 ID / UserName / Token / 上游账号）。
 * 因此这里用高熵随机串，并且用 SHA-256 摘要入库——即使库被读走也无法反推出可用的 Token。</p>
 */
@Service
public class CenterTokenService {

    /** Token 原文前缀，便于在界面上区分"这是中心 Token"与其它凭据。 */
    public static final String PREFIX = "ctr_";
    private static final int RANDOM_BYTES = 32;
    /** 库里保存的前缀长度（仅用于展示，不足以重建 Token）。 */
    private static final int VISIBLE_PREFIX = PREFIX.length() + 4;

    private final SecureRandom random = new SecureRandom();

    /** 生成一个新 Token 原文。 */
    public String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Token 摘要（入库值）。 */
    public String hash(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    /** 可展示的前缀（例如 {@code ctr_AbCd}），用于在界面上辨认是哪条 Token。 */
    public String visiblePrefix(String token) {
        if (token == null || token.length() < VISIBLE_PREFIX) {
            return PREFIX;
        }
        return token.substring(0, VISIBLE_PREFIX);
    }

    /**
     * 常量时间比较，避免按字节比较泄露信息。
     *
     * <p>调用方需先按 {@link #hash(String)} 查出候选账户，再用本方法确认，做到"查得到 + 比得安全"。</p>
     */
    public boolean matches(String rawToken, String storedHash) {
        if (rawToken == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(rawToken).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
