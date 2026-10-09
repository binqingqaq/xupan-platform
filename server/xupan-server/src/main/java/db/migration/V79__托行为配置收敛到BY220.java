package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;
import java.util.Locale;

/** Retires Xupan-only bot behavior controls and persists BY220 bot-level flags. */
public class V79__托行为配置收敛到BY220 extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("h2");
        String dropConstraint = h2 ? "DROP CONSTRAINT " : "DROP CHECK ";
        try (Statement s = context.getConnection().createStatement()) {
            s.execute("UPDATE test_player_action SET status='SKIPPED', error_code='BY220_RULES_REPLACED', "
                    + "error_message='旧托行为计划已由BY220规则替代', lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE status IN ('PENDING','FAILED') AND action_type IN ('BET_TEXT','TOP_UP_REQUEST','CHAT_TEXT')");

            s.execute("UPDATE test_player_behavior SET stake_range_code='30-300' WHERE stake_range_code='RANDOM'");
            s.execute("UPDATE test_player_behavior SET stake_min=30, stake_max=300 WHERE stake_range_code='30-300'");
            s.execute("UPDATE test_player_behavior SET stake_min=300, stake_max=1000 WHERE stake_range_code='300-1000'");
            s.execute("UPDATE test_player_behavior SET stake_min=1000, stake_max=3000 WHERE stake_range_code='1000-3000'");
            s.execute("UPDATE test_player_behavior SET stake_min=3000, stake_max=10000 WHERE stake_range_code='3000-10000'");
            s.execute("UPDATE test_player_behavior SET stake_min=10000, stake_max=30000 WHERE stake_range_code='10000-30000'");
            s.execute("ALTER TABLE test_player_action " + dropConstraint + "ck_test_player_action_type");
            s.execute("UPDATE test_player_action SET action_type='LEGACY_BET_TEXT' WHERE action_type='BET_TEXT'");
            s.execute("UPDATE test_player_action SET action_type='LEGACY_TOP_UP_REQUEST' WHERE action_type='TOP_UP_REQUEST'");

            for (String constraint : new String[]{
                    "ck_test_player_behavior_bets", "ck_test_player_behavior_messages",
                    "ck_behavior_stake_range", "ck_behavior_round_ten", "ck_behavior_activity", "ck_behavior_topup"}) {
                s.execute("ALTER TABLE test_player_behavior " + dropConstraint + constraint);
            }
            s.execute("ALTER TABLE test_player_action ADD CONSTRAINT ck_test_player_action_type "
                    + "CHECK (action_type IN ('CHAT_TEXT','BET_TEXT','AUTO_UP','AUTO_DOWN','AUTO_QUERY','LEGACY_BET_TEXT','LEGACY_TOP_UP_REQUEST'))");
            s.execute("DROP TABLE test_player_behavior_play_type");
            for (String column : new String[]{"bets_per_issue", "chat_enabled", "messages_per_issue",
                    "stake_round_ten", "activity_percent", "play_random", "topup_probability_percent",
                    "topup_min", "topup_max"}) {
                s.execute("ALTER TABLE test_player_behavior DROP COLUMN " + column);
            }
            s.execute("ALTER TABLE test_player_behavior ADD CONSTRAINT ck_behavior_stake_range "
                    + "CHECK (stake_range_code IN ('30-300','300-1000','1000-3000','3000-10000','10000-30000'))");
            s.execute("ALTER TABLE test_player_behavior ADD COLUMN player_chase_enabled BOOLEAN NOT NULL DEFAULT FALSE");
            s.execute("ALTER TABLE test_player_behavior ADD COLUMN player_feidan_enabled BOOLEAN NOT NULL DEFAULT FALSE");
            s.execute("ALTER TABLE test_player_behavior ALTER COLUMN run_mode SET DEFAULT 'AUTOMATIC'");
            s.execute("ALTER TABLE test_player_behavior ALTER COLUMN enabled SET DEFAULT TRUE");
            s.execute("ALTER TABLE test_player_behavior ALTER COLUMN stake_min SET DEFAULT 30.00");
            s.execute("ALTER TABLE test_player_behavior ALTER COLUMN stake_max SET DEFAULT 300.00");
            s.execute("ALTER TABLE test_player_behavior ALTER COLUMN stake_range_code SET DEFAULT '30-300'");

            s.execute("ALTER TABLE game_betting_config " + dropConstraint + "ck_betting_config_bot_risk");
            for (String column : new String[]{"bot_issue_total_bets", "bot_issue_total_stake",
                    "bot_night_activity_override_percent"}) {
                s.execute("ALTER TABLE game_betting_config DROP COLUMN " + column);
            }
            s.execute("ALTER TABLE agent ALTER COLUMN chase_enabled SET DEFAULT TRUE");
        }
    }
}
