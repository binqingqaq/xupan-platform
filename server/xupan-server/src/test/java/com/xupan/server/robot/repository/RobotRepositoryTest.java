package com.xupan.server.robot.repository;

import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.ChatRobotTemplate;
import com.xupan.server.robot.domain.RobotEventType;
import com.xupan.server.robot.domain.RobotStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RobotRepositoryTest {

    private static final String CODE_PREFIX = "robot-repo-test-";

    @Autowired
    private RobotRepository robotRepository;
    @Autowired
    private RobotTemplateRepository templateRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM chat_robot_template WHERE robot_id IN "
                + "(SELECT id FROM chat_robot WHERE robot_code LIKE ?)", CODE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM chat_robot WHERE robot_code LIKE ?", CODE_PREFIX + "%");
    }

    @Test
    void migrationSeedsOneEnabledRobotAndFourActiveTemplates() {
        assertThat(robotRepository.findByCode("issue-helper")).get()
                .extracting(ChatRobot::displayName, ChatRobot::status, ChatRobot::weight,
                        ChatRobot::delaySeconds)
                .containsExactly("开奖助手", RobotStatus.ENABLED, 100, 0);

        ChatRobot robot = robotRepository.findByCode("issue-helper").orElseThrow();
        assertThat(templateRepository.findAll(robot.id())).hasSize(4)
                .allSatisfy(template -> assertThat(template.enabled()).isTrue());
        assertThat(templateRepository.findActive(robot.id(), RobotEventType.DRAW_RESULT)).get()
                .extracting(ChatRobotTemplate::templateText)
                .isEqualTo("{{eventMessage}}");
    }

    @Test
    void insertsFindsAndUpdatesRobot() {
        long id = robotRepository.insert(CODE_PREFIX + "crud", "测试机器人", "robot-test",
                RobotStatus.ENABLED, 20, 15);

        assertThat(robotRepository.findById(id)).get()
                .extracting(ChatRobot::robotCode, ChatRobot::displayName, ChatRobot::weight,
                        ChatRobot::delaySeconds)
                .containsExactly(CODE_PREFIX + "crud", "测试机器人", 20, 15);
        assertThat(robotRepository.findEnabledOrderByCode())
                .extracting(ChatRobot::robotCode).contains(CODE_PREFIX + "crud");

        assertThat(robotRepository.update(id, "更新机器人", "robot-updated", RobotStatus.DISABLED,
                100, 300)).isEqualTo(1);
        assertThat(robotRepository.updateStatus(id, RobotStatus.ENABLED)).isEqualTo(1);
        assertThat(robotRepository.findById(id)).get()
                .extracting(ChatRobot::displayName, ChatRobot::avatarKey, ChatRobot::status,
                        ChatRobot::weight, ChatRobot::delaySeconds)
                .containsExactly("更新机器人", "robot-updated", RobotStatus.ENABLED, 100, 300);
    }

    @Test
    void rejectsRobotBoundariesBeforeDatabaseWrite() {
        assertThatThrownBy(() -> robotRepository.insert(CODE_PREFIX + "bad-weight", "测试",
                "robot-test", RobotStatus.ENABLED, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROBOT_WEIGHT_INVALID");
        assertThatThrownBy(() -> robotRepository.insert(CODE_PREFIX + "bad-delay", "测试",
                "robot-test", RobotStatus.ENABLED, 1, 301))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROBOT_DELAY_INVALID");
        assertThatThrownBy(() -> robotRepository.insert(CODE_PREFIX + "bad-code-BAD", "测试",
                "robot-test", RobotStatus.ENABLED, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void templateVersionsCanBeLockedAndActiveVersionsCanBeDisabled() {
        long robotId = robotRepository.insert(CODE_PREFIX + "template", "模板机器人", "robot-test",
                RobotStatus.ENABLED, 10, 0);
        long first = templateRepository.insertVersion(robotId, RobotEventType.ISSUE_STARTED,
                "default", "第一版", true, 1);
        assertThat(first).isPositive();
        assertThat(templateRepository.findLatestVersionForUpdate(robotId, RobotEventType.ISSUE_STARTED))
                .get().extracting(ChatRobotTemplate::version, ChatRobotTemplate::templateText)
                .containsExactly(1, "第一版");

        templateRepository.disableVersions(robotId, RobotEventType.ISSUE_STARTED);
        assertThat(templateRepository.findActive(robotId, RobotEventType.ISSUE_STARTED)).isEmpty();
    }
}
