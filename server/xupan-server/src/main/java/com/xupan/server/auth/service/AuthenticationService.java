package com.xupan.server.auth.service;

import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.LoginAuditRepository;
import com.xupan.server.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class AuthenticationService {

    public static final String SESSION_ID_ATTRIBUTE = AuthenticationService.class.getName() + ".sessionId";

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final PasswordPolicyService passwordPolicy;
    private final TokenService tokenService;
    private final LoginAuditRepository loginAuditRepository;
    private final Clock clock;

    @Autowired
    public AuthenticationService(UserRepository userRepository, PermissionService permissionService,
                                 PasswordPolicyService passwordPolicy, TokenService tokenService,
                                 LoginAuditRepository loginAuditRepository) {
        this(userRepository, permissionService, passwordPolicy, tokenService, loginAuditRepository, Clock.systemUTC());
    }

    AuthenticationService(UserRepository userRepository, PermissionService permissionService,
                          PasswordPolicyService passwordPolicy, TokenService tokenService,
                          LoginAuditRepository loginAuditRepository, Clock clock) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.permissionService = Objects.requireNonNull(permissionService);
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.loginAuditRepository = Objects.requireNonNull(loginAuditRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    public LoginResult login(String username, String rawPassword, TokenService.RequestMetadata metadata) {
        return login(username, rawPassword, null, metadata);
    }

    public LoginResult login(String username, String rawPassword, String deviceLabel,
                             TokenService.RequestMetadata metadata) {
        String normalizedUsername = username == null ? "" : username;
        Instant now = clock.instant();
        UserAccount existing = userRepository.findByUsername(normalizedUsername).orElse(null);
        if (existing == null) {
            passwordPolicy.matchesUnknownUser(rawPassword);
            loginAuditRepository.recordFailure(normalizedUsername, null, "AUTH_INVALID_CREDENTIALS",
                    ip(metadata), userAgent(metadata), now);
            throw new AuthenticationFailure("AUTH_INVALID_CREDENTIALS");
        }

        LoginAttempt attempt = userRepository.executeInLockedUserTransaction(existing.id(), user -> {
            if (!user.canLogin(now)) {
                loginAuditRepository.recordFailure(user.username(), user.id(), statusFailureCode(user),
                        ip(metadata), userAgent(metadata), now);
                return LoginAttempt.failure("AUTH_INVALID_CREDENTIALS");
            }
            boolean matches;
            try {
                matches = passwordPolicy.matches(rawPassword, user.passwordHash());
            } catch (RuntimeException ignored) {
                matches = false;
            }
            if (!matches) {
                int nextFailureCount = user.failedLoginCount() + 1;
                Instant lockedUntil = passwordPolicy.shouldLock(nextFailureCount)
                        ? passwordPolicy.lockUntil(now) : null;
                userRepository.recordLoginFailure(user.id(), lockedUntil);
                loginAuditRepository.recordFailure(user.username(), user.id(),
                        lockedUntil == null ? "AUTH_INVALID_CREDENTIALS" : "AUTH_ACCOUNT_LOCKED",
                        ip(metadata), userAgent(metadata), now);
                return LoginAttempt.failure("AUTH_INVALID_CREDENTIALS");
            }

            userRepository.clearLoginFailures(user.id(), ip(metadata), now);
            TokenService.IssuedTokens tokens = tokenService.issue(user.id(), deviceLabel, metadata);
            loginAuditRepository.recordSuccess(user.username(), user.id(), ip(metadata), userAgent(metadata), now);
            return LoginAttempt.success(tokens, currentUser(user));
        });
        if (!attempt.success()) {
            throw new AuthenticationFailure(attempt.failureCode());
        }
        return new LoginResult(attempt.tokens(), attempt.user());
    }

    public CurrentUser currentUser(long userId) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailure("AUTH_UNAUTHENTICATED"));
        if (!"ACTIVE".equals(user.status())) {
            throw new AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return currentUser(user);
    }

    public TokenService.IssuedTokens refresh(String rawRefreshToken, TokenService.RequestMetadata metadata) {
        try {
            return tokenService.refresh(rawRefreshToken, metadata);
        } catch (TokenService.InvalidTokenException exception) {
            loginAuditRepository.recordRevoked("anonymous-refresh", null, "AUTH_TOKEN_REVOKED",
                    ip(metadata), userAgent(metadata), clock.instant());
            throw exception;
        }
    }

    public void logout(long userId, String sessionId, TokenService.RequestMetadata metadata) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        userRepository.findById(userId).ifPresent(user -> {
            tokenService.revoke(sessionId);
            loginAuditRepository.recordLogout(user.username(), user.id(), ip(metadata), userAgent(metadata), clock.instant());
        });
    }

    private CurrentUser currentUser(UserAccount user) {
        return new CurrentUser(user.id(), user.username(), user.displayName(), user.avatarKey(),
                permissionService.findRoleCodes(user.id()),
                permissionService.findPermissionCodes(user.id()).stream().sorted().toList());
    }

    private static String statusFailureCode(UserAccount user) {
        if ("DISABLED".equals(user.status()) || "DELETED".equals(user.status())) {
            return "AUTH_ACCOUNT_DISABLED";
        }
        return "AUTH_ACCOUNT_LOCKED";
    }

    private static String ip(TokenService.RequestMetadata metadata) {
        return metadata == null ? null : metadata.ipAddress();
    }

    private static String userAgent(TokenService.RequestMetadata metadata) {
        return metadata == null ? null : metadata.userAgent();
    }

    private record LoginAttempt(boolean success, String failureCode,
                                TokenService.IssuedTokens tokens, CurrentUser user) {
        static LoginAttempt failure(String code) { return new LoginAttempt(false, code, null, null); }
        static LoginAttempt success(TokenService.IssuedTokens tokens, CurrentUser user) {
            return new LoginAttempt(true, null, tokens, user);
        }
    }

    public record LoginResult(TokenService.IssuedTokens tokens, CurrentUser user) {
        public long expiresInSeconds() {
            return Math.max(0, tokens.accessExpiresAt().getEpochSecond() - Instant.now().getEpochSecond());
        }
    }

    public record CurrentUser(long id, String username, String displayName, String avatarKey,
                              List<String> roles, List<String> permissions) {
    }

    public static class AuthenticationFailure extends RuntimeException {
        private final String code;

        public AuthenticationFailure(String code) {
            super(code);
            this.code = code;
        }

        public String code() { return code; }
    }
}
