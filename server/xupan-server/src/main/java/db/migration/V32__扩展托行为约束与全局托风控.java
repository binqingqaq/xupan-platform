package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;
import java.util.Locale;

/**
 * 托行为约束（下注范围、金额整十、指定玩法、活跃比例、随机上分申请）与全局托风控上限。
 */
public class V32__扩展托行为约束与全局托风控 extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("h2");
        String dropConstraint = h2 ? "DROP CONSTRAINT " : "DROP CHECK ";
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE test_player_behavior ADD COLUMN stake_range_code VARCHAR(24) NOT NULL DEFAULT '30-300'");
            statement.execute("ALTER TABLE test_player_behavior ADD COLUMN stake_round_ten VARCHAR(16) NOT NULL DEFAULT 'OFF'");
            statement.execute("ALTER TABLE test_player_behavior ADD COLUMN activity_percent INT NOT NULL DEFAULT 100");
            statement.execute("ALTER TABLE test_player_behavior ADD COLUMN play_random BOOLEAN NOT NULL DEFAULT TRUE");
            statement.execute("ALTER TABLE test_player_behavior ADD COLUMN topup_probability_percent INT NOT NULL DEFAULT 0");
            statement.execute("ALTER TABLE test_player_behavior ADD COLUMN topup_min DECIMAL(14, 2) NOT NULL DEFAULT 100.00");
            statement.execute("ALTER TABLE test_player_behavior ADD COLUMN topup_max DECIMAL(14, 2) NOT NULL DEFAULT 1000.00");
            statement.execute("ALTER TABLE test_player_behavior ADD CONSTRAINT ck_behavior_stake_range "
                    + "CHECK (stake_range_code IN ('RANDOM', '30-300', '300-1000', '1000-3000', '3000-10000', '10000-30000'))");
            statement.execute("ALTER TABLE test_player_behavior ADD CONSTRAINT ck_behavior_round_ten "
                    + "CHECK (stake_round_ten IN ('RANDOM', 'OFF', 'ON'))");
            statement.execute("ALTER TABLE test_player_behavior ADD CONSTRAINT ck_behavior_activity "
                    + "CHECK (activity_percent BETWEEN 0 AND 100)");
            statement.execute("ALTER TABLE test_player_behavior ADD CONSTRAINT ck_behavior_topup "
                    + "CHECK (topup_probability_percent BETWEEN 0 AND 100 AND topup_min > 0 AND topup_max >= topup_min)");
            statement.execute("""
                    CREATE TABLE test_player_behavior_play_type (
                        behavior_id BIGINT NOT NULL,
                        play_type VARCHAR(24) NOT NULL,
                        PRIMARY KEY (behavior_id, play_type),
                        CONSTRAINT fk_behavior_play_type_behavior
                            FOREIGN KEY (behavior_id) REFERENCES test_player_behavior(id),
                        CONSTRAINT ck_behavior_play_type CHECK (play_type IN
                            ('FAN', 'ANGLE', 'CAR', 'STRICT', 'ADD', 'POSITIVE', 'TONG', 'NONE',
                             'ODD_EVEN', 'BIG_SMALL', 'SPECIAL'))
                    )
                    """);
            statement.execute("ALTER TABLE test_player_action " + dropConstraint + "ck_test_player_action_type");
            statement.execute("ALTER TABLE test_player_action ADD CONSTRAINT ck_test_player_action_type "
                    + "CHECK (action_type IN ('CHAT_TEXT', 'BET_TEXT', 'TOP_UP_REQUEST'))");
            statement.execute("ALTER TABLE game_betting_config ADD COLUMN bot_issue_total_bets INT NOT NULL DEFAULT 20");
            statement.execute("ALTER TABLE game_betting_config ADD COLUMN bot_issue_total_stake INT NOT NULL DEFAULT 5000");
            statement.execute("ALTER TABLE game_betting_config ADD COLUMN bot_night_activity_override_percent INT NULL");
            statement.execute("ALTER TABLE game_betting_config ADD CONSTRAINT ck_betting_config_bot_risk "
                    + "CHECK (bot_issue_total_bets > 0 AND bot_issue_total_stake > 0 "
                    + "AND (bot_night_activity_override_percent IS NULL "
                    + "OR bot_night_activity_override_percent BETWEEN 0 AND 100))");
        }
    }
}
