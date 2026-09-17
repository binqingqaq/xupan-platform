package com.xupan.server.robot.repository;

import com.xupan.server.robot.domain.RobotDrawComponent;
import com.xupan.server.robot.domain.RobotDrawComponentConfig;
import com.xupan.server.robot.domain.RobotStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RobotDrawComponentRepositoryTest {

    private static final String ROBOT_CODE = "draw-component-test";

    @Autowired
    private RobotRepository robotRepository;
    @Autowired
    private RobotDrawComponentRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM chat_robot_draw_component WHERE robot_id IN "
                + "(SELECT id FROM chat_robot WHERE robot_code = ?)", ROBOT_CODE);
        jdbcTemplate.update("DELETE FROM chat_robot WHERE robot_code = ?", ROBOT_CODE);
    }

    @Test
    @Transactional
    void defaultsCanBeReadAndWinnerListCanBeDisabledAndReordered() {
        long robotId = robotRepository.insert(ROBOT_CODE, "开奖配置测试机器人", "robot-test",
                RobotStatus.ENABLED, 100, 0);
        repository.insertDefaults(robotId);

        assertThat(repository.findAll(robotId)).extracting(config -> config.component(),
                config -> config.enabled(), config -> config.order())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(RobotDrawComponent.DRAW_SUMMARY, true, 1),
                        org.assertj.core.groups.Tuple.tuple(RobotDrawComponent.DRAW_HISTORY, true, 2),
                        org.assertj.core.groups.Tuple.tuple(RobotDrawComponent.WINNER_LIST, true, 3));

        repository.replaceAll(robotId, List.of(
                new RobotDrawComponentConfig(RobotDrawComponent.WINNER_LIST, false, 1),
                new RobotDrawComponentConfig(RobotDrawComponent.DRAW_SUMMARY, true, 2),
                new RobotDrawComponentConfig(RobotDrawComponent.DRAW_HISTORY, true, 3)));

        assertThat(repository.findEnabledOrdered(robotId)).extracting(RobotDrawComponentConfig::component)
                .containsExactly(RobotDrawComponent.DRAW_SUMMARY, RobotDrawComponent.DRAW_HISTORY);
        assertThat(repository.findAll(robotId)).extracting(RobotDrawComponentConfig::component)
                .containsExactly(RobotDrawComponent.WINNER_LIST, RobotDrawComponent.DRAW_SUMMARY,
                        RobotDrawComponent.DRAW_HISTORY);
    }
}
