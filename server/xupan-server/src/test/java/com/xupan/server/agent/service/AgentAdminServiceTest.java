package com.xupan.server.agent.service;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.system.service.UserAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AgentAdminServiceTest {

    @Autowired
    private AgentAdminService adminService;

    @Autowired
    private AgentConsoleService consoleService;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agenttest_%')");
        jdbcTemplate.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agenttest_%'))");
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agenttest_%')");
        jdbcTemplate.update("DELETE FROM agent WHERE UPPER(agent_code) LIKE 'AGENTTEST_%'");
        jdbcTemplate.update("DELETE FROM agent_group WHERE group_code LIKE 'agenttest_%'");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'agenttest_%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'agenttest_%'");
    }

    @Test
    void createsAgentAssignsDefaultPlayerAndLimitsAgentConsoleToOwnScope() {
        long operatorId = operator();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String prefix = "agenttest_" + suffix;
        var group = adminService.createGroup(prefix + "_group", prefix + "_group_name", operatorId);
        var agent = adminService.createAgent(prefix + "_code", prefix + "_user",
                prefix + "_agent", "AgentPassword123", group.id(), operatorId);

        long playerUserId = userAdminService.createPlayerLinkUser(prefix + "_player", operatorId);
        long defaultAgentId = agentRepository.defaultAgentId();
        assertThat(agentRepository.findCurrentAgentId(playerUserId)).contains(defaultAgentId);

        adminService.assignPlatformDirectPlayer(playerUserId, agent.id(), operatorId);

        assertThat(agentRepository.findCurrentAgentId(playerUserId)).contains(agent.id());
        assertThat(agentRepository.findAgent(agent.id())).get()
                .extracting(AgentRepository.AgentRow::normalCount, AgentRepository.AgentRow::botCount)
                .containsExactly(1L, 0L);
        assertThat(consoleService.overview(agent.accountUserId()).id()).isEqualTo(agent.id());
        assertThat(consoleService.listPlayers(agent.accountUserId(), "NORMAL", null, 1, 20).items())
                .extracting(AgentRepository.AgentPlayerRow::userId)
                .containsExactly(playerUserId);
        assertThatThrownBy(() -> adminService.assignPlatformDirectPlayer(playerUserId, agent.id(), operatorId))
                .hasMessageContaining("AGENT_PLAYER_TRANSFER_FORBIDDEN");
    }

    @Test
    void disablingAgentRevokesAccountAccess() {
        long operatorId = operator();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String prefix = "agenttest_" + suffix;
        var agent = adminService.createAgent(prefix + "_code", prefix + "_user",
                prefix + "_agent", "AgentPassword123", null, operatorId);

        adminService.changeAgentStatus(agent.id(), "DISABLED", operatorId);

        assertThat(agentRepository.findAgent(agent.id())).get().extracting(AgentRepository.AgentRow::status)
                .isEqualTo("DISABLED");
        assertThat(userRepository.findById(agent.accountUserId())).get()
                .extracting(user -> user.status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> consoleService.overview(agent.accountUserId()))
                .hasMessageContaining("AGENT_CONSOLE_FORBIDDEN");
    }

    private long operator() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long operatorId = userRepository.insert("agenttest_operator_" + suffix, "代理管理员", "hash", "ACTIVE");
        userRepository.assignRole(operatorId, "ADMIN");
        return operatorId;
    }
}
