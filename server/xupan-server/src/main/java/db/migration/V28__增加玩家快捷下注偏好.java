package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/** Stores per-player quick-bet amount preferences for the player frontend. */
public class V28__增加玩家快捷下注偏好 extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE player_quick_bet_preference (
                        user_id BIGINT NOT NULL PRIMARY KEY,
                        amount_1 DECIMAL(18, 2) NOT NULL,
                        amount_2 DECIMAL(18, 2) NOT NULL,
                        amount_3 DECIMAL(18, 2) NOT NULL,
                        amount_4 DECIMAL(18, 2) NOT NULL,
                        amount_5 DECIMAL(18, 2) NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_quick_bet_preference_user
                            FOREIGN KEY (user_id) REFERENCES sys_user(id),
                        CONSTRAINT ck_quick_bet_preference_amounts
                            CHECK (amount_1 > 0 AND amount_2 > 0 AND amount_3 > 0
                                AND amount_4 > 0 AND amount_5 > 0)
                    )
                    """);
        }
    }
}
