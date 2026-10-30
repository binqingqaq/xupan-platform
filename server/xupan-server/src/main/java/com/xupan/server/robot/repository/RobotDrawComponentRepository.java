package com.xupan.server.robot.repository;

import com.xupan.server.robot.domain.RobotDrawComponent;
import com.xupan.server.robot.domain.RobotDrawComponentConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Repository
public class RobotDrawComponentRepository {

    private final JdbcTemplate jdbcTemplate;

    public RobotDrawComponentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RobotDrawComponentConfig> findAll(long robotId) {
        validateRobotId(robotId);
        List<RobotDrawComponentConfig> rows = jdbcTemplate.query("""
                SELECT component, enabled, display_order
                  FROM chat_robot_draw_component
                 WHERE robot_id = ?
                 ORDER BY display_order, component
                """, (rs, rowNum) -> new RobotDrawComponentConfig(
                RobotDrawComponent.fromDatabaseValue(rs.getString("component")),
                rs.getBoolean("enabled"), rs.getInt("display_order")), robotId);
        Map<RobotDrawComponent, RobotDrawComponentConfig> configured = new EnumMap<>(RobotDrawComponent.class);
        rows.forEach(row -> configured.put(row.component(), row));
        for (RobotDrawComponent component : RobotDrawComponent.values()) {
            configured.putIfAbsent(component, new RobotDrawComponentConfig(
                    component, true, component.defaultOrder()));
        }
        return configured.values().stream()
                .sorted(Comparator.comparingInt(RobotDrawComponentConfig::order)
                        .thenComparing(config -> config.component().name()))
                .toList();
    }

    public List<RobotDrawComponentConfig> findEnabledOrdered(long robotId) {
        return findAll(robotId).stream().filter(RobotDrawComponentConfig::enabled)
                .sorted(Comparator.comparingInt(RobotDrawComponentConfig::order))
                .toList();
    }

    public void insertDefaults(long robotId) {
        requireTransaction("insertDefaults");
        validateRobotId(robotId);
        for (RobotDrawComponent component : RobotDrawComponent.defaults()) {
            jdbcTemplate.update("""
                    INSERT INTO chat_robot_draw_component
                        (robot_id, component, enabled, display_order, created_at, updated_at)
                    VALUES (?, ?, TRUE, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, robotId, component.name(), component.defaultOrder());
        }
    }

    public void replaceAll(long robotId, List<RobotDrawComponentConfig> configs) {
        requireTransaction("replaceAll");
        validateRobotId(robotId);
        if (configs == null || configs.size() != RobotDrawComponent.values().length) {
            throw new IllegalArgumentException("ROBOT_DRAW_COMPONENT_INVALID: 必须提供四段配置");
        }
        if (configs.stream().anyMatch(config -> config == null || config.component() == null
                || config.order() < 1 || config.order() > configs.size())
                || configs.stream().map(RobotDrawComponentConfig::component).distinct().count()
                != RobotDrawComponent.values().length
                || configs.stream().map(RobotDrawComponentConfig::order).distinct().count()
                != configs.size()
                || configs.stream().map(RobotDrawComponentConfig::component).anyMatch(component ->
                component == null)) {
            throw new IllegalArgumentException("ROBOT_DRAW_COMPONENT_INVALID: 组件或排序配置无效");
        }
        jdbcTemplate.update("DELETE FROM chat_robot_draw_component WHERE robot_id = ?", robotId);
        for (RobotDrawComponentConfig config : configs) {
            jdbcTemplate.update("""
                    INSERT INTO chat_robot_draw_component
                        (robot_id, component, enabled, display_order, created_at, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, robotId, config.component().name(), config.enabled(), config.order());
        }
    }

    private static void validateRobotId(long robotId) {
        if (robotId <= 0) {
            throw new IllegalArgumentException("ROBOT_ID_INVALID: robotId 必须为正数");
        }
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " 必须在外层事务中调用");
        }
    }
}
