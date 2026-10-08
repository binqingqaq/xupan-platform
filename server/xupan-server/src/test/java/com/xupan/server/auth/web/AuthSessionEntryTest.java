package com.xupan.server.auth.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.TokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 5：会话入口维度（Flyway V75）——独立 Cookie / 独立退出 / 两入口并存 / 入口角色校验。
 *
 * <p>依据：计划 §3.3「会话入口维度」与 §4 阶段 5；BY220 {@code admin.php} 与 {@code robot.php}
 * 是两套独立入口，使用者 2026-10-08 确认 Q2 = C1（本批一起做），并确认**不复现**
 * BY220「登录 admin 清空整个 session 顺带把 robot 踢下线」的副作用。</p>
 *
 * <p>本用例锁定：</p>
 * <ol>
 *   <li>平台入口与代理入口的刷新 Cookie 可同时存在、互不覆盖；</li>
 *   <li>跨入口刷新 → 401，且**只清该入口的 Cookie**，另一个入口的会话与 Cookie 不受影响；</li>
 *   <li>退出只清当前入口的 Cookie，另一个入口仍可用；</li>
 *   <li>向后兼容：{@code X-Xupan-Auth-Audience: ADMIN} 与缺省头都按平台入口处理；</li>
 *   <li>老会话（V75 之前签发、{@code entry} 取默认值）在平台入口可用、在代理入口不可用；</li>
 *   <li>入口角色边界：**显式** {@code CONSOLE} 只接受含 {@code AGENT} 角色的机器账号，
 *       超管/子账号被 403 {@code AUTH_ENTRY_FORBIDDEN} 拒绝且不建会话、不写 Cookie；
 *       平台入口（缺省 / {@code ADMIN} / {@code PLATFORM}）不做角色校验，保持旧前端兼容路径。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSessionEntryTest {

    private static final String USERNAME = "entry-test-admin";
    private static final String SUB_ACCOUNT_USERNAME = "entry-test-sub";
    private static final String MACHINE_USERNAME = "entry-test-machine";
    private static final String MACHINE_CODE = "ENTRY_TEST_MACHINE";
    private static final String PASSWORD = "EntrySessionPassword123";
    private static final String PLATFORM_ENTRY = "PLATFORM";
    private static final String CONSOLE_ENTRY = "CONSOLE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    private long userId;
    private long subAccountId;
    private long machineUserId;

    @BeforeEach
    void setUp() {
        clean();
        userId = insertUser(USERNAME, "入口会话测试管理员", "ADMIN");
        subAccountId = insertUser(SUB_ACCOUNT_USERNAME, "入口会话测试子账号", "SUB_ACCOUNT");
        machineUserId = agentAdminService.createAgent(
                MACHINE_CODE, MACHINE_USERNAME, "入口会话测试机器", PASSWORD, null, userId).accountUserId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void platformAndConsoleSessionsCoexistWithoutOverwritingEachOther() throws Exception {
        MvcResult platformLogin = login(USERNAME, PASSWORD, null);
        Cookie platformCookie = platformLogin.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(platformCookie).isNotNull();
        // 平台入口不写代理入口的 Cookie
        assertThat(platformLogin.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE)).isNull();

        MvcResult consoleLogin = login(MACHINE_USERNAME, PASSWORD, "CONSOLE");
        Cookie consoleCookie = consoleLogin.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE);
        assertThat(consoleCookie).isNotNull();
        // 代理入口也不写平台入口的 Cookie：两个刷新 Cookie 可同时存在
        assertThat(consoleLogin.getResponse().getCookie(AuthController.REFRESH_COOKIE)).isNull();

        assertThat(storedEntry(platformCookie)).isEqualTo(PLATFORM_ENTRY);
        assertThat(storedEntry(consoleCookie)).isEqualTo(CONSOLE_ENTRY);

        // 刷新各自入口：成功响应只续写本入口的 Cookie，不会覆盖另一个入口
        MvcResult platformRefresh = refresh(platformCookie, null).andExpect(status().isOk()).andReturn();
        assertThat(platformRefresh.getResponse().getCookie(AuthController.REFRESH_COOKIE)).isNotNull();
        assertThat(platformRefresh.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE)).isNull();

        MvcResult consoleRefresh = refresh(consoleCookie, "CONSOLE").andExpect(status().isOk()).andReturn();
        assertThat(consoleRefresh.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE)).isNotNull();
        assertThat(consoleRefresh.getResponse().getCookie(AuthController.REFRESH_COOKIE)).isNull();
    }

    @Test
    void crossEntryRefreshIsRejectedWith401AndLeavesTheOtherEntryAlone() throws Exception {
        Cookie consoleCookie = login(MACHINE_USERNAME, PASSWORD, "CONSOLE").getResponse()
                .getCookie(AuthController.CONSOLE_REFRESH_COOKIE);
        assertThat(consoleCookie).isNotNull();

        // 把代理入口的刷新令牌放进平台入口的 Cookie 名里刷新 → 必须 401，绝不静默切换成平台身份
        Cookie misplacedConsole = new Cookie(AuthController.REFRESH_COOKIE, consoleCookie.getValue());
        MvcResult rejected = refresh(misplacedConsole, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REVOKED"))
                .andReturn();
        assertThat(rejected.getResponse().getCookie(AuthController.REFRESH_COOKIE).getMaxAge()).isZero();
        // 另一个入口的 Cookie 不在这次响应里被清
        assertThat(rejected.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE)).isNull();

        // 被拒不会撤销会话：代理入口用原 Cookie 仍然可用
        refresh(consoleCookie, "CONSOLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(MACHINE_USERNAME));

        // 反向：平台入口的会话放进代理入口 Cookie 名里，同样 401
        Cookie platformCookie = login(USERNAME, PASSWORD, null).getResponse()
                .getCookie(AuthController.REFRESH_COOKIE);
        assertThat(platformCookie).isNotNull();
        Cookie misplacedPlatform = new Cookie(AuthController.CONSOLE_REFRESH_COOKIE, platformCookie.getValue());
        MvcResult reverseRejected = refresh(misplacedPlatform, "CONSOLE")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REVOKED"))
                .andReturn();
        assertThat(reverseRejected.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE).getMaxAge()).isZero();
        assertThat(reverseRejected.getResponse().getCookie(AuthController.REFRESH_COOKIE)).isNull();

        // 平台入口会话本身未受影响
        refresh(platformCookie, null).andExpect(status().isOk());
    }

    @Test
    void logoutOnlyClearsTheCurrentEntryCookie() throws Exception {
        MvcResult platformLogin = login(USERNAME, PASSWORD, null);
        Cookie platformCookie = platformLogin.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        String platformAccessToken = accessToken(platformLogin);

        MvcResult consoleLogin = login(MACHINE_USERNAME, PASSWORD, "CONSOLE");
        Cookie consoleCookie = consoleLogin.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE);
        String consoleAccessToken = accessToken(consoleLogin);
        assertThat(platformCookie).isNotNull();
        assertThat(consoleCookie).isNotNull();

        // 在代理入口退出：只清 xupan_console_refresh，平台入口的 Cookie 不被清
        mockMvc.perform(post("/api/auth/logout").with(bearer(consoleAccessToken)))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse()
                        .getCookie(AuthController.CONSOLE_REFRESH_COOKIE).getMaxAge()).isZero())
                .andExpect(result -> assertThat(result.getResponse()
                        .getCookie(AuthController.REFRESH_COOKIE)).isNull());

        // 代理入口的访问令牌已被撤销
        mockMvc.perform(get("/api/auth/me").with(bearer(consoleAccessToken)))
                .andExpect(status().isUnauthorized());
        // 平台入口的访问令牌不受影响：退出只作用于当前入口
        mockMvc.perform(get("/api/auth/me").with(bearer(platformAccessToken)))
                .andExpect(status().isOk());
        // 平台入口的刷新会话仍然可用（BY220 里登录 admin 会顺带踢掉 robot，本系统确认不复现该副作用）
        refresh(platformCookie, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(USERNAME));
    }

    @Test
    void adminAudienceHeaderAndMissingHeaderBothMeanPlatformEntry() throws Exception {
        // 兼容用例 1：缺省头（现网前端登录时就不带头）→ 平台入口 + 旧 Cookie 名
        MvcResult noHeader = login(USERNAME, PASSWORD, null);
        Cookie noHeaderCookie = noHeader.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(noHeaderCookie).isNotNull();
        assertThat(storedEntry(noHeaderCookie)).isEqualTo(PLATFORM_ENTRY);

        // 兼容用例 2：旧前端仍发 ADMIN → 同样按平台入口处理，不打断中间态
        MvcResult adminHeader = login(USERNAME, PASSWORD, "ADMIN");
        Cookie adminHeaderCookie = adminHeader.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(adminHeaderCookie).isNotNull();
        assertThat(adminHeader.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE)).isNull();
        assertThat(storedEntry(adminHeaderCookie)).isEqualTo(PLATFORM_ENTRY);
        refresh(adminHeaderCookie, "ADMIN").andExpect(status().isOk());
    }

    @Test
    void legacySessionWithoutEntryIsUsableOnPlatformButNotOnConsole() throws Exception {
        // 模拟 V75 之前签发的会话：直接插库、不写 entry（取列默认值），刷新令牌 30 天有效
        String rawRefreshToken = "legacy-refresh-token-0001";
        long securityVersion = jdbcTemplate.queryForObject(
                "SELECT security_version FROM sys_user WHERE id = ?", Long.class, userId);
        jdbcTemplate.update("""
                INSERT INTO auth_session
                    (session_id, user_id, access_token_hash, access_expires_at,
                     refresh_token_hash, refresh_expires_at, security_version, auth_mode, scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PASSWORD', NULL)
                """, "legacy-entry-session", userId, TokenService.sha256("legacy-access-token-0001"),
                Timestamp.from(Instant.now().plusSeconds(1800)), TokenService.sha256(rawRefreshToken),
                Timestamp.from(Instant.now().plusSeconds(60 * 60 * 24)), securityVersion);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT entry FROM auth_session WHERE session_id = ?", String.class,
                "legacy-entry-session")).isEqualTo(PLATFORM_ENTRY);

        // 代理入口不可用（先测：被拒不轮换，不影响后面的平台入口刷新）
        Cookie legacyOnConsole = new Cookie(AuthController.CONSOLE_REFRESH_COOKIE, rawRefreshToken);
        refresh(legacyOnConsole, "CONSOLE")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REVOKED"));

        // 平台入口可用：老会话平滑过渡，只有代理入口需要重登一次
        Cookie legacyOnPlatform = new Cookie(AuthController.REFRESH_COOKIE, rawRefreshToken);
        refresh(legacyOnPlatform, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(USERNAME));
    }

    /**
     * 显式 {@code CONSOLE} 入口的角色边界：只接受机器账号（含 {@code AGENT} 角色）。
     *
     * <p>被拒时必须是 403 {@code AUTH_ENTRY_FORBIDDEN}，且**不写任何 Set-Cookie、不新增会话行**——
     * 入口隔离必须在服务端成立，不能只靠前端路由门禁。</p>
     */
    @Test
    void explicitConsoleEntryOnlyAcceptsMachineAccounts() throws Exception {
        // ① 机器账号 + CONSOLE → 成功，写代理入口 Cookie，落库 entry=CONSOLE
        MvcResult machineLogin = login(MACHINE_USERNAME, PASSWORD, "CONSOLE");
        Cookie consoleCookie = machineLogin.getResponse().getCookie(AuthController.CONSOLE_REFRESH_COOKIE);
        assertThat(consoleCookie).isNotNull();
        assertThat(machineLogin.getResponse().getCookie(AuthController.REFRESH_COOKIE)).isNull();
        assertThat(storedEntry(consoleCookie)).isEqualTo(CONSOLE_ENTRY);

        // ② 超管 + CONSOLE → 403，无任何 Set-Cookie，会话数不变，审计记 AUTH_ENTRY_FORBIDDEN
        int adminSessionsBefore = sessionCount(userId);
        MvcResult adminRejected = loginExpectingEntryForbidden(USERNAME, PASSWORD);
        assertThat(adminRejected.getResponse().getCookies()).isEmpty();
        assertThat(sessionCount(userId)).isEqualTo(adminSessionsBefore);
        assertThat(failureCodesFor(userId)).contains(AuthenticationService.ENTRY_FORBIDDEN_CODE);

        // ③ 子账号 + CONSOLE → 同样被拒（V76 之后子账号也不持有 AGENT_CONSOLE_READ）
        int subSessionsBefore = sessionCount(subAccountId);
        MvcResult subRejected = loginExpectingEntryForbidden(SUB_ACCOUNT_USERNAME, PASSWORD);
        assertThat(subRejected.getResponse().getCookies()).isEmpty();
        assertThat(sessionCount(subAccountId)).isEqualTo(subSessionsBefore);
        assertThat(failureCodesFor(subAccountId)).contains(AuthenticationService.ENTRY_FORBIDDEN_CODE);

        // 机器账号那一侧的会话仍然有效
        assertThat(sessionCount(machineUserId)).isEqualTo(1);
    }

    /**
     * 平台入口（缺省 / {@code ADMIN} / {@code PLATFORM}）**不做角色校验**，保持旧前端与老标签页可用。
     *
     * <p>这是刻意保留的兼容边界：前后端在同一仓库一起重建产物，不引入"后端必须先于前端上线"的发布耦合。
     * 机器账号走平台入口也被允许（入口不改变权限集合，它本来也进不了平台后台页面）。</p>
     */
    @Test
    void platformEntryKeepsNoRoleCheckForCompatibility() throws Exception {
        for (String audience : new String[] {null, "ADMIN", "PLATFORM"}) {
            MvcResult result = login(USERNAME, PASSWORD, audience);
            Cookie cookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
            assertThat(cookie).as("超管 + %s 头应成功且写平台入口 Cookie", audience).isNotNull();
            assertThat(storedEntry(cookie)).isEqualTo(PLATFORM_ENTRY);
        }

        // 子账号走平台入口同样成功（它是平台后台的正常使用者）
        assertThat(login(SUB_ACCOUNT_USERNAME, PASSWORD, null).getResponse()
                .getCookie(AuthController.REFRESH_COOKIE)).isNotNull();

        // 机器账号走平台入口也允许：PLATFORM 是"旧路径"，不校验角色（判断见报告）
        MvcResult machineOnPlatform = login(MACHINE_USERNAME, PASSWORD, "PLATFORM");
        Cookie machineCookie = machineOnPlatform.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(machineCookie).isNotNull();
        assertThat(storedEntry(machineCookie)).isEqualTo(PLATFORM_ENTRY);
    }

    private long insertUser(String username, String displayName, String roleCode) {
        long id = userRepository.insert(username, displayName, passwordPolicy.encode(PASSWORD), "ACTIVE");
        assertThat(userRepository.assignRole(id, roleCode)).isEqualTo(1);
        return id;
    }

    private MvcResult login(String username, String password, String audience) throws Exception {
        var request = post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        if (audience != null) {
            request = request.header(AuthController.REFRESH_AUDIENCE_HEADER, audience);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private MvcResult loginExpectingEntryForbidden(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                        .header(AuthController.REFRESH_AUDIENCE_HEADER, "CONSOLE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AuthenticationService.ENTRY_FORBIDDEN_CODE))
                .andExpect(jsonPath("$.message").value(AuthenticationService.ENTRY_FORBIDDEN_MESSAGE))
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions refresh(Cookie cookie, String audience)
            throws Exception {
        var request = post("/api/auth/refresh").cookie(cookie);
        if (audience != null) {
            request = request.header(AuthController.REFRESH_AUDIENCE_HEADER, audience);
        }
        return mockMvc.perform(request);
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** 刷新 Cookie 对应的会话入口（只看摘要，不存明文）。 */
    private String storedEntry(Cookie cookie) {
        return jdbcTemplate.queryForObject(
                "SELECT entry FROM auth_session WHERE refresh_token_hash = ?", String.class,
                TokenService.sha256(cookie.getValue()));
    }

    private int sessionCount(long accountUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE user_id = ?", Integer.class, accountUserId);
    }

    private List<String> failureCodesFor(long accountUserId) {
        return jdbcTemplate.queryForList(
                "SELECT failure_code FROM sys_login_log WHERE user_id = ? AND result = 'FAILURE'",
                String.class, accountUserId);
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    /** 只清理本用例造出的账号、机器与会话。 */
    private void clean() {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE username IN (?, ?, ?)", Long.class,
                USERNAME, SUB_ACCOUNT_USERNAME, MACHINE_USERNAME);
        List<Long> machineIds = jdbcTemplate.queryForList(
                "SELECT id FROM agent WHERE agent_code = ?", Long.class, MACHINE_CODE);
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!userIds.isEmpty()) {
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", userIds);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_user WHERE id IN (%s)", userIds);
        }
    }

    private void delete(String sqlTemplate, List<Long> ids) {
        jdbcTemplate.update(sqlTemplate.replace("%s", placeholders(ids.size())), ids.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(count, 1), "?"));
    }
}
