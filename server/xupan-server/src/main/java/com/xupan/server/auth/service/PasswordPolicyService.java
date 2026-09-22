package com.xupan.server.auth.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PasswordPolicyService {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final long LOCK_MINUTES = 15;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());

    public void validateForCreation(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("密码长度必须为 8 至 72 个字符");
        }
        if (rawPassword.chars().noneMatch(Character::isLetter)
                || rawPassword.chars().noneMatch(Character::isDigit)) {
            throw new IllegalArgumentException("密码至少包含一个字母和一个数字");
        }
    }

    public String encode(String rawPassword) {
        validateForCreation(rawPassword);
        return passwordEncoder.encode(rawPassword);
    }

    /** Creates a credential hash that is intentionally never disclosed or used for player login. */
    public String encodeUnusableCredential() {
        return passwordEncoder.encode(UUID.randomUUID().toString() + UUID.randomUUID());
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null
                && passwordEncoder.matches(rawPassword, encodedPassword);
    }

    /** Performs the same BCrypt work for an unknown username without retaining a credential. */
    public boolean matchesUnknownUser(String rawPassword) {
        return matches(rawPassword, dummyPasswordHash);
    }

    public boolean shouldLock(int failedCount) {
        return failedCount >= MAX_FAILED_ATTEMPTS;
    }

    public Instant lockUntil(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("锁定起始时间不能为空");
        }
        return now.plusSeconds(LOCK_MINUTES * 60);
    }
}
