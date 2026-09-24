package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/** Adds the singleton betting configuration used by the admin console and bet validation. */
public class V30__增加下注限额与展示配置 extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE game_betting_config (
                        id TINYINT NOT NULL PRIMARY KEY,
                        display_odds INT NOT NULL,
                        special_rebate INT NOT NULL,
                        special_limit INT NOT NULL,
                        issue_total_limit INT NOT NULL,
                        positive_limit INT NOT NULL,
                        angle_limit INT NOT NULL,
                        strict_limit INT NOT NULL,
                        tong_limit INT NOT NULL,
                        car_limit INT NOT NULL,
                        odd_even_limit INT NOT NULL,
                        big_small_limit INT NOT NULL,
                        fan_limit INT NOT NULL,
                        add_limit INT NOT NULL,
                        player_max_stake INT NOT NULL,
                        player_min_stake INT NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT ck_betting_config_display_odds CHECK (display_odds >= 0),
                        CONSTRAINT ck_betting_config_rebate CHECK (special_rebate >= 0),
                        CONSTRAINT ck_betting_config_limits CHECK (
                            special_limit > 0 AND issue_total_limit > 0
                            AND positive_limit > 0 AND angle_limit > 0
                            AND strict_limit > 0 AND tong_limit > 0
                            AND car_limit > 0 AND odd_even_limit > 0
                            AND big_small_limit > 0 AND fan_limit > 0
                            AND add_limit > 0
                        ),
                        CONSTRAINT ck_betting_config_stake_range CHECK (
                            player_min_stake > 0 AND player_max_stake >= player_min_stake
                        )
                    )
                    """);
            statement.execute("""
                    INSERT INTO game_betting_config (
                        id, display_odds, special_rebate,
                        special_limit, issue_total_limit, positive_limit, angle_limit,
                        strict_limit, tong_limit, car_limit, odd_even_limit,
                        big_small_limit, fan_limit, add_limit,
                        player_max_stake, player_min_stake
                    ) VALUES (
                        1, 95, 1,
                        200, 5000, 20000, 1000,
                        20000, 20000, 20000, 20000,
                        20000, 20000, 20000,
                        5001, 1
                    )
                    """);
        }
    }
}
