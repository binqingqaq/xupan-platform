package com.xupan.server.robot.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.web.AuthenticationExceptionHandler;
import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotStatus;
import com.xupan.server.robot.service.RobotAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RobotAdminControllerTest {

    private final RobotAdminService service = mock(RobotAdminService.class);
    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        RobotAdminController controller = new RobotAdminController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthenticationExceptionHandler())
                .build();
        authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new AuthenticatedUser(
                new UserAccount(7L, "robot-admin", "机器人管理员", "avatar", "hash", "ACTIVE",
                        0, null, 0L, null, null), List.of()));
    }

    @Test
    void listsRobotsUsingTheReadEndpoint() throws Exception {
        ChatRobot robot = robot();
        when(service.listRobots(7L, 1, 20)).thenReturn(
                new RobotAdminService.RobotPage(List.of(robot), 1, 20, 1));

        mockMvc.perform(get("/api/admin/robots").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].robotCode").value("issue-helper"))
                .andExpect(jsonPath("$.items[0].status").value("ENABLED"))
                .andExpect(jsonPath("$.total").value(1));

        verify(service).listRobots(7L, 1, 20);
    }

    @Test
    void validatesCreatePayloadBeforeCallingService() throws Exception {
                mockMvc.perform(post("/api/admin/robots").principal(authentication)
                        .contentType("application/json")
                        .content("{\"robotCode\":\"bad code\",\"displayName\":\"机器人\","
                                + "\"avatarKey\":\"robot-default\",\"weight\":101,\"delaySeconds\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
    }

    @Test
    void unauthenticatedRequestUsesExistingAuthenticationError() throws Exception {
        mockMvc.perform(get("/api/admin/robots"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    private static ChatRobot robot() {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        return new ChatRobot(1L, "issue-helper", "开奖助手", "robot-default",
                RobotStatus.ENABLED, 100, 0, now, now);
    }
}
