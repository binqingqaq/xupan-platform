package com.xupan.server.auth.service;

import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.LoginAuditRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class AuthenticationService {

    public static final String SESSION_ID_ATTRIBUTE = AuthenticationService.class.getName() + ".sessionId";

    /** 机器账号角色码（BY220 {@code x_robot} 对应的 {@code AGENT} 角色），与前端 {@code requiredRoles} 口径一致。 */
    public static final String AGENT_ROLE_CODE = "AGENT";
    /** 入口角色不满足的稳定错误码：不是 401（凭据有效），故走 403。 */
    public static final String ENTRY_FORBIDDEN_CODE = "AUTH_ENTRY_FORBIDDEN";
    public static final String ENTRY_FORBIDDEN_MESSAGE = "该入口只接受机器账号";

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final PasswordPolicyService passwordPolicy;
    private final TokenService tokenService;
    private final LoginAuditRepository loginAuditRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public AuthenticationService(UserRepository userRepository, PermissionService permissionService,
                                 PasswordPolicyService passwordPolicy, TokenService tokenService,
                                 LoginAuditRepository loginAuditRepository, JdbcTemplate jdbcTemplate) {
        this(userRepository, permissionService, passwordPolicy, tokenService, loginAuditRepository,
                jdbcTemplate, Clock.systemUTC());
    }

    AuthenticationService(UserRepository userRepository, PermissionService permissionService,
                          PasswordPolicyService passwordPolicy, TokenService tokenService,
                          LoginAuditRepository loginAuditRepository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.permissionService = Objects.requireNonNull(permissionService);
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.loginAuditRepository = Objects.requireNonNull(loginAuditRepository);
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    public LoginResult login(String username, String rawPassword, TokenService.RequestMetadata metadata) {
        return login(username, rawPassword, null, metadata);
    }

    public LoginResult login(String username, String rawPassword, String deviceLabel,
                             TokenService.RequestMetadata metadata) {
        return login(username, rawPassword, deviceLabel, metadata, SessionRecord.ENTRY_PLATFORM);
    }

    /**
     * 按**入口**登录（Flyway V75 的会话入口维度）：
     * 平台后台（BY220 {@code admin.php}）签 {@link SessionRecord#ENTRY_PLATFORM}，
     * 代理后台（BY220 {@code robot.php}，机器账号）签 {@link SessionRecord#ENTRY_CONSOLE}。
     *
     * <p>入口角色边界（使用者 2026-10-08 第 2 题选 B 之后的收口）：**显式**请求
     * {@link SessionRecord#ENTRY_CONSOLE} 时，账号必须含 {@code AGENT} 角色，否则拒绝登录
     * （403 {@value #ENTRY_FORBIDDEN_CODE}「{@value #ENTRY_FORBIDDEN_MESSAGE}」）且不建会话、不写 Cookie。
     * 非显式的平台入口（缺省头 / {@code ADMIN}）**不校验角色**，以保持"旧前端与老标签页仍可用"的兼容路径——
     * 前后端在同一仓库一起重建产物，不引入"后端必须先于前端上线"的发布耦合。</p>
     *
     * <p>入口只影响刷新 Cookie 与刷新校验，不影响身份与权限集合（权限集合仍由角色权限表决定）。</p>
     */
    public LoginResult login(String username, String rawPassword, String deviceLabel,
                             TokenService.RequestMetadata metadata, String entry) {
        String normalizedUsername = username == null ? "" : username;
        String sessionEntry = TokenService.normalizeEntry(entry);
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
            if ("PLAYER_LINK".equals(user.authMode())) {
                loginAuditRepository.recordFailure(user.username(), user.id(), "AUTH_INVALID_CREDENTIALS",
                        ip(metadata), userAgent(metadata), now);
                return LoginAttempt.failure("AUTH_INVALID_CREDENTIALS");
            }
            boolean matches;
            try {
                matches = passwordPolicy.matches(rawPassword, user.passwordHash());
            } catch (RuntimeException ignored) {
                matches = false;
            }
            if (!userRepository.isLoginWindowOpen(user.id(), now)) {
                loginAuditRepository.recordFailure(user.username(), user.id(), "AUTH_SUB_ACCOUNT_EXPIRED",
                        ip(metadata), userAgent(metadata), now);
                return LoginAttempt.failure("AUTH_SUB_ACCOUNT_EXPIRED");
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
            // 入口角色校验（只对**显式** CONSOLE 入口）。放在密码校验之后，且**先于** clearLoginFailures
            // 与 tokenService.issue：密码错误时仍然只有 AUTH_INVALID_CREDENTIALS，
            // 不会变成"用户名是否存在"的探测器；不满足时既不建会话也不写任何 Cookie。
            if (SessionRecord.ENTRY_CONSOLE.equals(sessionEntry)
                    && !permissionService.findRoleCodes(user.id()).contains(AGENT_ROLE_CODE)) {
                loginAuditRepository.recordFailure(user.username(), user.id(), ENTRY_FORBIDDEN_CODE,
                        ip(metadata), userAgent(metadata), now);
                return LoginAttempt.failure(ENTRY_FORBIDDEN_CODE);
            }

            userRepository.clearLoginFailures(user.id(), ip(metadata), now);
            TokenService.IssuedTokens tokens = tokenService.issue(user.id(), deviceLabel, metadata, sessionEntry);
            loginAuditRepository.recordSuccess(user.username(), user.id(), ip(metadata), userAgent(metadata), now);
            return LoginAttempt.success(tokens, currentUser(user));
        });
        if (!attempt.success()) {
            throw loginFailure(attempt.failureCode());
        }
        // BY220 Login.php::check() 第 51-56 行：登录成功后把该账号的 online 置 1 并记录 logtime。
        // 只更新状态，**绝不用 online 拦截登录**（check() 只校验密码与失效时间）。
        markSubAccountOnline(attempt.user().id(), true);
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
        return refresh(rawRefreshToken, metadata, null, null);
    }

    public TokenService.IssuedTokens refresh(String rawRefreshToken, TokenService.RequestMetadata metadata,
                                              String requiredAuthMode) {
        return refresh(rawRefreshToken, metadata, requiredAuthMode, null);
    }

    /** {@code requiredEntry} 非空时校验请求入口与会话入口一致，不一致 401（见 TokenService#refresh）。 */
    public TokenService.IssuedTokens refresh(String rawRefreshToken, TokenService.RequestMetadata metadata,
                                              String requiredAuthMode, String requiredEntry) {
        try {
            return tokenService.refresh(rawRefreshToken, metadata, requiredAuthMode, requiredEntry);
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
            // BY220 Login.php::logout() 第 67 行：退出时把 online 置 0。
            markSubAccountOnline(user.id(), false);
            loginAuditRepository.recordLogout(user.username(), user.id(), ip(metadata), userAgent(metadata), clock.instant());
        });
    }

    /**
     * 子账号在线状态（BY220 {@code x_admin.online} / {@code x_admin.logtime}）。
     *
     * <p>BY220 {@code application/admin/controller/Login.php}：{@code check()} 登录成功后写
     * {@code online = 1, logtime = time()}，{@code logout()} 写 {@code online = 0}；
     * 且 {@code check()} **不校验 online**（只看密码与 {@code time} 失效时间），所以这里只在
     * 登录成功 / 退出时更新展示状态，绝不参与登录拦截。</p>
     *
     * <p>非子账号用户（平台管理员、机器账号、玩家）没有 {@code agent_group} 行，UPDATE 自然不命中。</p>
     */
    private void markSubAccountOnline(long userId, boolean online) {
        if (online) {
            jdbcTemplate.update("""
                    UPDATE agent_group SET online = 1, logtime = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE sys_user_id = ? AND deleted_at IS NULL
                    """, Timestamp.from(clock.instant()), userId);
        } else {
            jdbcTemplate.update("""
                    UPDATE agent_group SET online = 0, updated_at = CURRENT_TIMESTAMP
                     WHERE sys_user_id = ? AND deleted_at IS NULL
                    """, userId);
        }
    }

    /** 子账号可用积分（BY220 {@code head.html:192} 顶部「可用积分：N」取 {@code x_admin.score}）；非子账号返回 null。 */
    private BigDecimal findSubAccountScore(long userId) {
        return jdbcTemplate.query("""
                SELECT score FROM agent_group WHERE sys_user_id = ? AND deleted_at IS NULL ORDER BY id LIMIT 1
                """, (rs, rowNum) -> rs.getBigDecimal("score"), userId).stream().findFirst().orElse(null);
    }

    private CurrentUser currentUser(UserAccount user) {
        return currentUser(user, user.authMode(), null);
    }

    public CurrentUser currentUser(long userId, String authMode, String scope) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailure("AUTH_UNAUTHENTICATED"));
        if (!"ACTIVE".equals(user.status())) {
            throw new AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return currentUser(user, authMode, scope);
    }

    private CurrentUser currentUser(UserAccount user, String authMode, String scope) {
        if (!userRepository.isLoginWindowOpen(user.id(), clock.instant())) {
            throw new AuthenticationFailure("AUTH_SUB_ACCOUNT_EXPIRED");
        }
        // BY220 head.html:192：子账号在顶部显示「可用积分：N」（取 x_admin.score）。
        BigDecimal subAccountScore = findSubAccountScore(user.id());
        if ("CHAT_ONLY".equals(scope)) {
            return new CurrentUser(user.id(), user.username(), user.displayName(), user.avatarKey(),
                    "PLAYER_LINK", "CHAT_ONLY", List.of(), List.of("CHAT_ROOM_READ", "CHAT_MESSAGE_SEND"),
                    subAccountScore);
        }
        return new CurrentUser(user.id(), user.username(), user.displayName(), user.avatarKey(),
                authMode == null || authMode.isBlank() ? "PASSWORD" : authMode, scope,
                permissionService.findRoleCodes(user.id()),
                permissionService.findPermissionCodes(user.id()).stream().sorted().toList(),
                subAccountScore);
    }

    /**
     * 登录失败的错误映射。
     *
     * <p>入口角色不满足是 **403**（凭据本身有效，只是这个入口不接受这种身份，语义上不是"未认证"），
     * 其余认证失败维持 401 {@link AuthenticationFailure}，避免把"用户名是否存在"暴露成状态码差异。</p>
     */
    private static RuntimeException loginFailure(String failureCode) {
        if (ENTRY_FORBIDDEN_CODE.equals(failureCode)) {
            return BusinessException.forbidden(ENTRY_FORBIDDEN_CODE, ENTRY_FORBIDDEN_MESSAGE);
        }
        return new AuthenticationFailure(failureCode);
    }

    private static String statusFailureCode(UserAccount user) {
        if ("TEST".equals(user.userType())) {
            return "AUTH_TEST_PLAYER_LOGIN_DISABLED";
        }
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

    /**
     * 当前登录用户视图。
     *
     * <p>{@code subAccountScore} 是 BY220 {@code head.html:192} 顶部「可用积分：N」的来源
     * （子账号 {@code x_admin.score}）；非子账号为 {@code null}。</p>
     */
    public record CurrentUser(long id, String username, String displayName, String avatarKey,
                              String authMode, String scope, List<String> roles, List<String> permissions,
                              BigDecimal subAccountScore) {
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
