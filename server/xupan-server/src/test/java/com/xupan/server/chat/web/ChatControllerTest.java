package com.xupan.server.chat.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerTest {

    private static final String USER_A = "chat-controller-user-a";
    private static final String USER_B = "chat-controller-user-b";
    private static final String NO_PERMISSION = "chat-controller-no-permission";
    private static final String PASSWORD = "ChatPassword123";
    private static final String NO_CHAT_ROLE = "CHAT_TEST_NONE";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordPolicyService passwordPolicy;

    @BeforeEach
    void setUp() {
        clean();
        insertUser(USER_A, "聊天室用户甲", "USER");
        insertUser(USER_B, "聊天室用户乙", "USER");
        jdbcTemplate.update("INSERT INTO sys_role (role_code, display_name) VALUES (?, ?)",
                NO_CHAT_ROLE, "聊天测试无权限角色");
        insertUser(NO_PERMISSION, "无聊天室权限用户", NO_CHAT_ROLE);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void userCanSendReplayReadAndAdvanceCursor() throws Exception {
        String tokenA = login(USER_A);
        String tokenB = login(USER_B);
        String body = "{\"clientMessageId\":\"controller-1\",\"content\":\"大家好\"}";

        MvcResult sent = mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messageType").value("USER_CHAT"))
                .andExpect(jsonPath("$.senderType").value("USER"))
                .andExpect(jsonPath("$.senderName").value("聊天室用户甲"))
                .andExpect(jsonPath("$.content").value("大家好"))
                .andReturn();
        int sequence = JsonPath.read(sent.getResponse().getContentAsString(), "$.sequenceNo");

        mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequenceNo").value(sequence));

        mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"clientMessageId\":\"controller-1\",\"content\":\"不同正文\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"clientMessageId\":\"controller-1\",\"content\":\"用户乙发言\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderName").value("聊天室用户乙"))
                .andExpect(jsonPath("$.content").value("用户乙发言"));

        mockMvc.perform(get("/api/chat/rooms/main/messages")
                        .param("afterSequence", "0")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomCode").value("main"))
                .andExpect(jsonPath("$.items[?(@.sequenceNo == " + sequence + ")].content")
                        .value(org.hamcrest.Matchers.hasItem("大家好")));

        mockMvc.perform(post("/api/chat/rooms/main/read-cursor")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"lastReadSequence\":" + sequence + "}"))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT last_read_sequence FROM chat_read_cursor "
                + "WHERE room_id = (SELECT id FROM chat_room WHERE room_code = 'main') "
                + "AND user_id = (SELECT id FROM sys_user WHERE username = ?)", Long.class, USER_A))
                .isEqualTo((long) sequence);
    }

    @Test
    void securityAndBusinessErrorsUseStableResponses() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/main"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));

        String noPermissionToken = login(NO_PERMISSION);
        mockMvc.perform(get("/api/chat/rooms/main")
                        .header("Authorization", "Bearer " + noPermissionToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
        mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .header("Authorization", "Bearer " + noPermissionToken)
                        .contentType("application/json")
                        .content("{\"clientMessageId\":\"denied-1\",\"content\":\"越权\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));
        mockMvc.perform(post("/api/chat/rooms/main/read-cursor")
                        .header("Authorization", "Bearer " + noPermissionToken)
                        .contentType("application/json")
                        .content("{\"lastReadSequence\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PERMISSION_DENIED"));

        String token = login(USER_A);
        mockMvc.perform(get("/api/chat/rooms/missing" ).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
        mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"clientMessageId\":\"bad-1\",\"content\":\"<b>标签</b>\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT_MESSAGE_HTML_FORBIDDEN"));
    }

    @Test
    void clientCannotChooseSenderOrSequenceFields() throws Exception {
        String token = login(USER_A);
        mockMvc.perform(post("/api/chat/rooms/main/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                .content("{\"clientMessageId\":\"polluted-1\",\"content\":\"正文\","
                                + "\"senderId\":999999,\"senderType\":\"ROBOT\","
                                + "\"messageType\":\"ROBOT\",\"roomId\":999,"
                                + "\"sequenceNo\":999,\"issueNumber\":\"forged\","
                                + "\"payloadJson\":\"{\\\"forged\\\":true}\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messageType").value("USER_CHAT"))
                .andExpect(jsonPath("$.senderType").value("USER"))
                .andExpect(jsonPath("$.senderId").value(userId(USER_A)))
                .andExpect(jsonPath("$.sequenceNo").isNumber())
                .andExpect(jsonPath("$.content").value("正文"));
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void insertUser(String username, String displayName, String roleCode) {
        long userId = userRepository.insert(username, displayName,
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        assertThat(userRepository.assignRole(userId, roleCode)).isEqualTo(1);
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM chat_read_cursor WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM chat_user_mute WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM chat_outbox WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM sys_login_log WHERE username_snapshot IN (?, ?, ?)",
                USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN (?, ?, ?)", USER_A, USER_B, NO_PERMISSION);
        jdbcTemplate.update("DELETE FROM sys_role WHERE role_code = ?", NO_CHAT_ROLE);
    }
}
