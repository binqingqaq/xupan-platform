package com.xupan.server.playerauth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class PlayerLinkTokenCipher {
    private static final String VERSION = "v1";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public PlayerLinkTokenCipher(@Value("${xupan.auth.player-link-display-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException("未配置 XUPAN_AUTH_PLAYER_LINK_DISPLAY_KEY");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("玩家链接加密密钥必须是 Base64", exception);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("玩家链接加密密钥必须是 32 字节 Base64 值");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String rawToken) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
            return VERSION + "." + encode(nonce) + "." + encode(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("玩家链接加密失败", exception);
        }
    }

    public String decrypt(String encodedCiphertext) {
        if (encodedCiphertext == null || encodedCiphertext.isBlank()) {
            throw new IllegalStateException("玩家链接缺少密文");
        }
        String[] parts = encodedCiphertext.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("玩家链接密文版本无效");
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("玩家链接解密失败", exception);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
