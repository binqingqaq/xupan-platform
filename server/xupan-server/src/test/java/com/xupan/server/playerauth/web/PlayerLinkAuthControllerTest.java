package com.xupan.server.playerauth.web;

import com.jayway.jsonpath.JsonPath;
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

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "链接管理员", passwordPolicy.encode(ADMIN_PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        walletService.ensureWalletForUser(adminId, "链接管理员");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void normalPlayerLinkExchangesToFullRoomAndCannotUseAdminApis() throws Exception {
        MvcResult adminLogin = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String adminToken = JsonPath.read(adminLogin.getResponse().getContentAsString(), "$.accessToken");
        Cookie adminRefreshCookie = adminLogin.getResponse().getCookie(
                com.xupan.server.auth.web.AuthController.REFRESH_COOKIE);
        assertThat(adminRefreshCookie).isNotNull();
        long playerId = createNormal(adminToken);
        long botId = createBot(adminToken);

        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + PLAYER + "\",\"password\":\"AnyPassword123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        MvcResult issued = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links")
                        .header("Authorization", bearer(adminToken)))
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

        MvcResult adminRefreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(adminRefreshCookie)
                        .header(com.xupan.server.auth.web.AuthController.REFRESH_AUDIENCE_HEADER, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.authMode").value("PASSWORD"))
                .andExpect(jsonPath("$.user.username").value(ADMIN))
                .andReturn();
        adminToken = JsonPath.read(adminRefreshed.getResponse().getContentAsString(), "$.accessToken");

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
                        .header("Authorization", bearer(adminToken)))
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

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(adminToken)))
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
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        long playerId = createNormal(adminToken);
        String rawToken = issueRawToken(adminToken, playerId);

        String firstAccessToken = exchange(rawToken);
        String secondAccessToken = exchange(rawToken);

        assertThat(firstAccessToken).isNotBlank().isNotEqualTo(secondAccessToken);
    }

    @Test
    void currentLinkCanBeDisplayedAfterPlayerCreation() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        long playerId = createNormal(adminToken);

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId + "/access-links/current")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(playerId))
                .andExpect(jsonPath("$.scope").value("PLAYER_FULL"))
                .andExpect(jsonPath("$.accessUrl").value(org.hamcrest.Matchers.containsString("/33/")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void savesConfiguredDaysForNextRotationAndUsesSevenDaysByDefault() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        long playerId = createNormal(adminToken);
        Instant initialExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM player_access_link WHERE user_id=? ORDER BY id DESC LIMIT 1",
                java.sql.Timestamp.class, playerId).toInstant();

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkStatus.configuredDays").value(7));

        mockMvc.perform(patch("/api/admin/player-desk/players/" + playerId + "/access-links/expiration")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content("{\"days\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30));

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkStatus.configuredDays").value(30));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT expires_at FROM player_access_link WHERE user_id=? ORDER BY id DESC LIMIT 1",
                java.sql.Timestamp.class, playerId).toInstant()).isEqualTo(initialExpiresAt);

        MvcResult rotated = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/rotate")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        Instant rotatedExpiresAt = Instant.parse(JsonPath.read(rotated.getResponse().getContentAsString(), "$.expiresAt"));
        Instant now = Instant.now();
        assertThat(rotatedExpiresAt).isAfter(now.plusSeconds(29 * 86400L));
        assertThat(rotatedExpiresAt).isBefore(now.plusSeconds(31 * 86400L));
    }

    @Test
    void revokeRotateAndExpiryInvalidatePlayerLinksAndSessions() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        long playerId = createNormal(adminToken);
        String firstToken = issueRawToken(adminToken, playerId);
        String firstAccessToken = exchange(firstToken);

        long linkId = jdbcTemplate.queryForObject(
                "SELECT id FROM player_access_link WHERE user_id = (SELECT id FROM sys_user WHERE username = ?)",
                Long.class, PLAYER);
        mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/" + linkId + "/revoke")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(firstAccessToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + firstToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLAYER_LINK_INVALID"));

        mockMvc.perform(get("/api/admin/player-desk/players/" + playerId + "/access-links/current")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(linkId))
                .andExpect(jsonPath("$.accessUrl").value(org.hamcrest.Matchers.containsString("/33/")));
        mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/" + linkId + "/restore")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        exchange(firstToken);

        String secondToken = issueRawToken(adminToken, playerId);
        jdbcTemplate.update("UPDATE player_access_link SET expires_at = CURRENT_TIMESTAMP WHERE token_hash = ?",
                com.xupan.server.auth.service.TokenService.sha256(secondToken));
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + secondToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLAYER_LINK_INVALID"));

        String thirdToken = issueRawToken(adminToken, playerId);
        String thirdAccessToken = exchange(thirdToken);
        String rotatedToken = rotateRawToken(adminToken, playerId);
        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(thirdAccessToken)))
                .andExpect(status().isUnauthorized());
        assertThat(rotatedToken).isNotEqualTo(thirdToken);
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + thirdToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void softDeleteRevokesIdentityAccessAndKeepsHistoricalRows() throws Exception {
        String adminToken = login(ADMIN, ADMIN_PASSWORD);
        long playerId = createNormal(adminToken);
        String rawToken = issueRawToken(adminToken, playerId);
        String playerToken = exchange(rawToken);

        mockMvc.perform(delete("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.internalCode").value(org.hamcrest.Matchers.matchesPattern("^wxid_[A-Za-z0-9]{16}$")))
                .andExpect(jsonPath("$.memberCode").value(org.hamcrest.Matchers.startsWith("v")))
                .andExpect(jsonPath("$.displayName").value(PLAYER));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sys_user WHERE id=?", String.class, playerId))
                .isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM demo_user_account WHERE sys_user_id=?", String.class, playerId))
                .isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM player_access_link WHERE user_id=? AND revoked_at IS NOT NULL", Integer.class, playerId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NOT NULL", Integer.class, playerId))
                .isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/chat/rooms/main").header("Authorization", bearer(playerToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/player-auth/exchange").contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLAYER_LINK_INVALID"));
        mockMvc.perform(get("/api/admin/player-desk/players").param("status", "DELETED")
                        .param("includeDeleted", "true").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("DELETED"));

        mockMvc.perform(delete("/api/admin/player-desk/players/" + playerId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
    }

    private long createNormal(String adminToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"displayName\":\"" + PLAYER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("PLAYER_LINK"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
    }

    private long createBot(String adminToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/bot")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"userCode\":\"" + BOT + "\",\"displayName\":\"链接托\"}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
    }

    private String issueRawToken(String adminToken, long playerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andReturn();
        String url = JsonPath.read(result.getResponse().getContentAsString(), "$.accessUrl");
        return pathToken(url);
    }

    private String rotateRawToken(String adminToken, long playerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/" + playerId + "/access-links/rotate")
                        .header("Authorization", bearer(adminToken)))
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
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", ADMIN, PLAYER, BOT);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN (?, ?, ?)", ADMIN, PLAYER, BOT);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
