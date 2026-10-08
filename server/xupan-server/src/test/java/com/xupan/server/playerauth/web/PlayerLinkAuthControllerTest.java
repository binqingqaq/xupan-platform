package com.xupan.server.playerauth.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.auth.service.TokenService;
import com.xupan.server.game.service.VirtualWalletService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerLinkAuthControllerTest {

    private static final String ADMIN = "player-link-admin";
    private static final String ADMIN_PASSWORD = "PlayerLinkAdmin123";
    private static final String MACHINE_USER = "player-link-machine";
    private static final String MACHINE_PASSWORD = "PlayerLinkMachine123";
    private static final String MACHINE_CODE = "PLAYERLINK_MACHINE";
    private static final String PLAYER = "链接普通玩家";
    private static final String BOT = "player-link-bot";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private AgentAdminService agentAdminService;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "链接管理员", passwordPolicy.encode(ADMIN_PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        walletService.ensureWalletForUser(adminId, "链接管理员");
        // 阶段 3 起玩家运营（建玩家/托、玩家链接）只属于机器账号（BY220 robot.php），
        // 因此本类的操作者是机器账号；平台管理员只用于造机器（AGENT_MANAGE 属机器管理，不属玩家运营）。
        agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "链接测试机器",
                MACHINE_PASSWORD, null, adminId);
        // 机器建托受 BY220 的机器托上限约束（bot_count），本用例要建 1 个托
        jdbcTemplate.update("UPDATE agent SET bot_count = 5 WHERE agent_code = ?", MACHINE_CODE);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void normalPlayerLinkExchangesToFullRoomAndCannotUseAdminApis() throws Exception {
        MvcResult machineLogin = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + MACHINE_USER + "\",\"password\":\"" + MACHINE_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String machineToken = JsonPath.read(machineLogin.getResponse().getContentAsString(), "$.accessToken");
        Cookie machineRefreshCookie = machineLogin.getResponse().getCookie(
                com.xupan.server.auth.web.AuthController.REFRESH_COOKIE);
        assertThat(machineRefreshCookie).isNotNull();
        long playerId = createNormal(machineToken);
        long botId = createBot(machineToken);

        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + PLAYER + "\",\"password\":\"AnyPassword123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        MvcResult issued = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PLAYER_FULL"))
                .andExpect(jsonPath("$.accessUrl").value(org.hamcrest.Matchers.containsString("/33/")))
                .andExpect(jsonPath("$.accessUrl").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("?token="))))
                .andExpect(jsonPath("$.rawToken").doesNotExist())
                .andReturn();
        String rawToken = pathToken(JsonPath.read(issued.getResponse().getContentAsString(), "$.accessUrl"));

        MvcResult exchanged = mockMvc.perform(post("/api/player-auth/exchange")
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.authMode").value("PLAYER_LINK"))
                .andExpect(jsonPath("$.user.scope").value("PLAYER_FULL"))
                .andExpect(jsonPath("$.user.roles[0]").value("USER"))
                .andExpect(jsonPath("$.user.permissions").value(org.hamcrest.Matchers.hasItem("GAME_BET_PLACE")))
                .andExpect(jsonPath("$.user.permissions").value(org.hamcrest.Matchers.hasItem("WALLET_READ")))
                .andReturn();
        String playerToken = JsonPath.read(exchanged.getResponse().getContentAsString(), "$.accessToken");
        Cookie playerRefreshCookie = exchanged.getResponse().getCookie(
                com.xupan.server.auth.web.AuthController.PLAYER_REFRESH_COOKIE);
        assertThat(playerRefreshCookie).isNotNull();
        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(playerRefreshCookie)
                        .header(com.xupan.server.auth.web.AuthController.REFRESH_AUDIENCE_HEADER, "PLAYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.authMode").value("PLAYER_LINK"))
                .andExpect(jsonPath("$.user.scope").value("PLAYER_FULL"))
                .andReturn();
        playerToken = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
        Cookie currentPlayerRefreshCookie = refreshed.getResponse().getCookie(
                com.xupan.server.auth.web.AuthController.PLAYER_REFRESH_COOKIE);
        assertThat(currentPlayerRefreshCookie).isNotNull();

        MvcResult machineRefreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(machineRefreshCookie)
                        .header(com.xupan.server.auth.web.AuthController.REFRESH_AUDIENCE_HEADER, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.authMode").value("PASSWORD"))
                .andExpect(jsonPath("$.user.username").value(MACHINE_USER))
                .andReturn();
        machineToken = JsonPath.read(machineRefreshed.getResponse().getContentAsString(), "$.accessToken");

        Cookie legacySharedCookie = new Cookie(
                com.xupan.server.auth.web.AuthController.REFRESH_COOKIE, playerRefreshCookie.getValue());
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(legacySharedCookie)
                        .header(com.xupan.server.auth.web.AuthController.REFRESH_AUDIENCE_HEADER, "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REVOKED"))
                .andExpect(result -> assertThat(result.getResponse().getCookie(
                        com.xupan.server.auth.web.AuthController.REFRESH_COOKIE).getMaxAge()).isZero());

        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(playerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/ws-ticket").param("roomCode", "main")
                        .header("Authorization", bearer(playerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty());
        mockMvc.perform(get("/api/demo/game/current").header("Authorization", bearer(playerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me/wallet").header("Authorization", bearer(playerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/player-desk/summary").header("Authorization", bearer(playerToken)))
                .andExpect(status().isForbidden());
        MvcResult botIssued = mockMvc.perform(post("/api/admin/player-desk/players/" + botId + "/access-links")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PLAYER_FULL"))
                .andReturn();
        String botToken = pathToken(JsonPath.read(botIssued.getResponse().getContentAsString(), "$.accessUrl"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM player_access_link WHERE user_id=? ORDER BY id DESC LIMIT 1",
                String.class, botId)).isEqualTo(TokenService.sha256(botToken));
        String botAccessToken = exchange(botToken);
        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(botAccessToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(machineToken)))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getCookie(
                        com.xupan.server.auth.web.AuthController.REFRESH_COOKIE).getMaxAge()).isZero())
                .andExpect(result -> assertThat(result.getResponse().getCookie(
                        com.xupan.server.auth.web.AuthController.PLAYER_REFRESH_COOKIE)).isNull());
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(currentPlayerRefreshCookie)
                        .header(com.xupan.server.auth.web.AuthController.REFRESH_AUDIENCE_HEADER, "PLAYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.authMode").value("PLAYER_LINK"));
    }

    @Test
    void playerLinkCanBeExchangedMoreThanOnceBeforeExpiry() throws Exception {
        String machineToken = login(MACHINE_USER, MACHINE_PASSWORD);
        long playerId = createNormal(machineToken);
        String rawToken = issueRawToken(machineToken, playerId);

        String firstAccessToken = exchange(rawToken);
        String secondAccessToken = exchange(rawToken);

        assertThat(firstAccessToken).isNotBlank().isNotEqualTo(secondAccessToken);
    }

    @Test
    void currentLinkCanBeDisplayedAfterPlayerCreation() throws Exception {
        String machineToken = login(MACHINE_USER, MACHINE_PASSWORD);
        long playerId = createNormal(machineToken);

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId + "/access-links/current")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(playerId))
                .andExpect(jsonPath("$.scope").value("PLAYER_FULL"))
                .andExpect(jsonPath("$.accessUrl").value(org.hamcrest.Matchers.containsString("/33/")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void savesConfiguredDaysForNextRotationAndUsesSevenDaysByDefault() throws Exception {
        String machineToken = login(MACHINE_USER, MACHINE_PASSWORD);
        long playerId = createNormal(machineToken);
        Instant initialExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM player_access_link WHERE user_id=? ORDER BY id DESC LIMIT 1",
                java.sql.Timestamp.class, playerId).toInstant();

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkStatus.configuredDays").value(7));

        mockMvc.perform(patch("/api/admin/player-desk/players/" + playerId + "/access-links/expiration")
                        .header("Authorization", bearer(machineToken))
                        .contentType("application/json")
                        .content("{\"days\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30));

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkStatus.configuredDays").value(30));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT expires_at FROM player_access_link WHERE user_id=? ORDER BY id DESC LIMIT 1",
                java.sql.Timestamp.class, playerId).toInstant()).isEqualTo(initialExpiresAt);

        MvcResult rotated = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/rotate")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andReturn();
        Instant rotatedExpiresAt = Instant.parse(JsonPath.read(rotated.getResponse().getContentAsString(), "$.expiresAt"));
        Instant now = Instant.now();
        assertThat(rotatedExpiresAt).isAfter(now.plusSeconds(29 * 86400L));
        assertThat(rotatedExpiresAt).isBefore(now.plusSeconds(31 * 86400L));
    }

    @Test
    void revokeRotateAndExpiryInvalidatePlayerLinksAndSessions() throws Exception {
        String machineToken = login(MACHINE_USER, MACHINE_PASSWORD);
        long playerId = createNormal(machineToken);
        String firstToken = issueRawToken(machineToken, playerId);
        String firstAccessToken = exchange(firstToken);

        long linkId = jdbcTemplate.queryForObject(
                "SELECT id FROM player_access_link WHERE user_id = (SELECT id FROM sys_user WHERE username = ?)",
                Long.class, PLAYER);
        mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/" + linkId + "/revoke")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(firstAccessToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + firstToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLAYER_LINK_INVALID"));

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId + "/access-links/current")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(linkId))
                .andExpect(jsonPath("$.accessUrl").value(org.hamcrest.Matchers.containsString("/33/")));
        mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/" + linkId + "/restore")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk());
        exchange(firstToken);

        String secondToken = issueRawToken(machineToken, playerId);
        jdbcTemplate.update("UPDATE player_access_link SET expires_at = CURRENT_TIMESTAMP WHERE token_hash = ?",
                com.xupan.server.auth.service.TokenService.sha256(secondToken));
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + secondToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLAYER_LINK_INVALID"));

        String thirdToken = issueRawToken(machineToken, playerId);
        String thirdAccessToken = exchange(thirdToken);
        String rotatedToken = rotateRawToken(machineToken, playerId);
        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(thirdAccessToken)))
                .andExpect(status().isUnauthorized());
        assertThat(rotatedToken).isNotEqualTo(thirdToken);
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + thirdToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 删除玩家 = **物理删除**（严格对齐 BY220 {@code Index.php:235-243}），并撤销身份访问。
     *
     * <p>本用例由「软删除」翻面而来（2026-10-08 使用者拍板「玩家删除口径严格一致」）：
     * 玩家行与登录账号都不再存在，因此断言从「状态是 DELETED」改为「行已不存在」；
     * 身份域子行（链接、会话）随身份物理删除，玩家链接与会话立即失效；
     * 重复删除仍然幂等成功（不是 404）。</p>
     */
    @Test
    void physicalDeleteRevokesIdentityAccessAndRemovesPlayerRow() throws Exception {
        String machineToken = login(MACHINE_USER, MACHINE_PASSWORD);
        long playerId = createNormal(machineToken);
        String rawToken = issueRawToken(machineToken, playerId);
        String playerToken = exchange(rawToken);

        mockMvc.perform(delete("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDeleted").value(false))
                .andExpect(jsonPath("$.memberCode").value(org.hamcrest.Matchers.startsWith("v")))
                .andExpect(jsonPath("$.playerName").value(PLAYER));

        // 物理删除：玩家账号行与登录账号都不存在（BY220 的 delete()）
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, playerId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_user_account WHERE sys_user_id=?", Integer.class, playerId)).isZero();
        // 身份域子行随身份消失
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM player_access_link WHERE user_id=?", Integer.class, playerId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE user_id=?", Integer.class, playerId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", Integer.class, playerId)).isZero();

        // 已发出的玩家会话与链接立即失效
        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(playerToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLAYER_LINK_INVALID"));

        // 玩家已不在列表（连历史查询也查不到）
        mockMvc.perform(get("/api/admin/player-desk/players").param("status", "DELETED")
                        .param("includeDeleted", "true").header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // 重复删除幂等成功，不重复退款
        mockMvc.perform(delete("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDeleted").value(true));
    }

    private long createNormal(String machineToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(machineToken)).contentType("application/json")
                        .content("{\"displayName\":\"" + PLAYER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("PLAYER_LINK"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
    }

    private long createBot(String machineToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/bot")
                        .header("Authorization", bearer(machineToken)).contentType("application/json")
                        .content("{\"userCode\":\"" + BOT + "\",\"displayName\":\"链接托\"}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
    }

    private String issueRawToken(String machineToken, long playerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk()).andReturn();
        String url = JsonPath.read(result.getResponse().getContentAsString(), "$.accessUrl");
        return pathToken(url);
    }

    private String rotateRawToken(String machineToken, long playerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/rotate")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk()).andReturn();
        String url = JsonPath.read(result.getResponse().getContentAsString(), "$.accessUrl");
        return pathToken(url);
    }

    private String exchange(String rawToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String pathToken(String url) {
        String path = URI.create(url).getPath();
        if (!path.startsWith("/33/") || path.length() <= "/33/".length()) {
            throw new IllegalArgumentException("链接不是固定 33 房间格式: " + url);
        }
        return URLDecoder.decode(path.substring("/33/".length()), StandardCharsets.UTF_8);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM player_access_link");
        jdbcTemplate.update("DELETE FROM player_link_expiration_config");
        jdbcTemplate.update("DELETE FROM auth_ws_ticket");
        jdbcTemplate.update("DELETE FROM auth_session");
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM test_player_action");
        jdbcTemplate.update("DELETE FROM test_player_behavior");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM game_issue");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, PLAYER, BOT);
        // demo_user_account.agent_id → agent 外键：账号删完才能删机器
        jdbcTemplate.update("DELETE FROM agent_game WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbcTemplate.update("DELETE FROM agent_ledger WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbcTemplate.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?))", ADMIN, PLAYER, BOT, MACHINE_USER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN (?, ?, ?, ?)",
                ADMIN, PLAYER, BOT, MACHINE_USER);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
