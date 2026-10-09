package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局赔率/限额配置（{@code /api/admin/player-desk/betting-config}）的读写。
 *
 * <p>阶段 3（严格对齐 BY220，2026-10-08 使用者拍板）之后的**真实权限形状**：
 * {@code /api/admin/player-desk/**} 只认机器账号（{@code PERM_AGENT_CONSOLE_READ}），
 * 因此本端点<b>读</b>只能由机器账号发起（平台账号 403）；
 * <b>写</b>仍由既有的 {@code PUT .../betting-config/**} → {@code PERM_USER_MANAGE} 规则把守
 * （该规则不能删：{@code GameBettingConfigService} 没有权限校验，删掉会让机器账号能改全局赔率/限额）。</p>
 *
 * <p>因此本类用**机器账号读、平台管理员写**来验证同一份持久化事实；
 * 这种"写读不同身份"的不对称正是该端点归属不清的证据，平台侧若确需读写应另开端点（见 SecurityConfig 注释）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class GameBettingConfigControllerTest {

    private static final String USERNAME = "betting-config-admin";
    private static final String MACHINE_USER = "betting-config-machine";
    private static final String MACHINE_CODE = "BETTINGCONFIG_MACHINE";
    private static final String PASSWORD = "BettingConfig123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    private String token;
    private String machineToken;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        resetBettingConfig();
        long userId = userRepository.insert(USERNAME, "限额配置管理员",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "ADMIN");
        agentAdminService.createAgent(MACHINE_CODE, MACHINE_USER, "限额配置机器", PASSWORD, null, userId);
        token = login();
        machineToken = login(MACHINE_USER);
    }

    @AfterEach
    void tearDown() {
        resetBettingConfig();
        clean();
    }

    @Test
    void returnsPersistedDisplayValuesAndLimits() throws Exception {
        // 读只允许机器账号（阶段 3：player-desk 仅机器账号）
        mockMvc.perform(get("/api/admin/player-desk/betting-config")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayOdds").value(95))
                .andExpect(jsonPath("$.specialOdds").value(18.0))
                .andExpect(jsonPath("$.specialRebate").value(1))
                .andExpect(jsonPath("$.playerMaxStake").value(5001))
                .andExpect(jsonPath("$.playerMinStake").value(1))
                .andExpect(jsonPath("$.specialLimit").value(200));

        // 平台管理员读同一条路径 → 403（"超管不碰玩家"在服务端成立）
        mockMvc.perform(get("/api/admin/player-desk/betting-config")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void savesDisplayValuesAndRejectsInvalidSpecialOdds() throws Exception {
        // 写仍由平台管理员执行（既有 PUT 规则），读回用机器账号
        mockMvc.perform(put("/api/admin/player-desk/betting-config/display")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayOdds\":96,\"specialOdds\":19.5,\"specialRebate\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayOdds").value(96))
                .andExpect(jsonPath("$.specialOdds").value(19.5))
                .andExpect(jsonPath("$.specialRebate").value(2));

        mockMvc.perform(get("/api/admin/player-desk/betting-config")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayOdds").value(96))
                .andExpect(jsonPath("$.specialRebate").value(2));

        mockMvc.perform(put("/api/admin/player-desk/betting-config/display")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayOdds\":96,\"specialOdds\":0.5,\"specialRebate\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BETTING_CONFIG_INVALID"));
    }

    @Test
    void savesLimitsAndRejectsEmptyOrInvertedValues() throws Exception {
        mockMvc.perform(put("/api/admin/player-desk/betting-config/limits")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specialLimit":300,"issueTotalLimit":6000,"positiveLimit":21000,
                                 "angleLimit":1100,"strictLimit":21000,"tongLimit":21000,
                                 "carLimit":21000,"oddEvenLimit":21000,"bigSmallLimit":21000,
                                 "fanLimit":21000,"addLimit":21000,"playerMaxStake":6001,
                                 "playerMinStake":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialLimit").value(300))
                .andExpect(jsonPath("$.playerMaxStake").value(6001))
                .andExpect(jsonPath("$.playerMinStake").value(2));

        mockMvc.perform(put("/api/admin/player-desk/betting-config/limits")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specialLimit":300,"issueTotalLimit":6000,"positiveLimit":21000,
                                 "angleLimit":1100,"strictLimit":21000,"tongLimit":21000,
                                 "carLimit":21000,"oddEvenLimit":21000,"bigSmallLimit":21000,
                                 "fanLimit":21000,"addLimit":21000,"playerMaxStake":1,
                                 "playerMinStake":10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BETTING_CONFIG_INVALID"));

        mockMvc.perform(put("/api/admin/player-desk/betting-config/limits")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specialLimit":0,"issueTotalLimit":6000,"positiveLimit":21000,
                                 "angleLimit":1100,"strictLimit":21000,"tongLimit":21000,
                                 "carLimit":21000,"oddEvenLimit":21000,"bigSmallLimit":21000,
                                 "fanLimit":21000,"addLimit":21000,"playerMaxStake":6001,
                                 "playerMinStake":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BETTING_CONFIG_INVALID"));

        mockMvc.perform(get("/api/admin/player-desk/betting-config")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerMinStake").value(2));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/player-desk/betting-config"))
                .andExpect(status().isUnauthorized());
    }

    private String login() throws Exception {
        return login(USERNAME);
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void resetBettingConfig() {
        jdbc.update("""
                UPDATE game_betting_config
                   SET display_odds = 95, special_rebate = 1,
                       special_limit = 200, issue_total_limit = 5000, positive_limit = 20000,
                       angle_limit = 1000, strict_limit = 20000, tong_limit = 20000,
                       car_limit = 20000, odd_even_limit = 20000, big_small_limit = 20000,
                       fan_limit = 20000, add_limit = 20000, player_max_stake = 5001,
                       player_min_stake = 1
                 WHERE id = 1
                """);
        jdbc.update("DELETE FROM game_odds WHERE play_type = 'SPECIAL'");
    }

    private void clean() {
        jdbc.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", USERNAME, MACHINE_USER);
        jdbc.update("DELETE FROM sys_login_log WHERE username_snapshot IN (?, ?)", USERNAME, MACHINE_USER);
        jdbc.update("DELETE FROM sys_operation_log");
        // demo_user_account.agent_id → agent 外键：本用例没有玩家账号，但顺序仍按"机器先于用户"处理
        jdbc.update("DELETE FROM agent_game WHERE agent_id IN (SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent_ledger WHERE agent_id IN (SELECT id FROM agent WHERE agent_code = ?)", MACHINE_CODE);
        jdbc.update("DELETE FROM agent WHERE agent_code = ?", MACHINE_CODE);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?))", USERNAME, MACHINE_USER);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?)", USERNAME, MACHINE_USER);
    }

    private static String bearer(String value) {
        return "Bearer " + value;
    }
}
