package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Restricts upstream reporting and center settlement to immutable normal-player bets. */
public class V107__中心报网只允许普通玩家注单 extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("h2");
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("UPDATE game_bet SET player_kind_snapshot = "
                    + "(SELECT a.player_kind FROM demo_user_account a WHERE a.id = game_bet.user_id) "
                    + "WHERE player_kind_snapshot IS NULL AND user_id IS NOT NULL");
            statement.execute("ALTER TABLE test_player_behavior DROP COLUMN player_feidan_enabled");

            if (h2) {
                statement.execute("ALTER TABLE game_bet ADD CONSTRAINT ck_game_bet_player_kind_snapshot "
                        + "CHECK (player_kind_snapshot IS NULL OR player_kind_snapshot IN ('NORMAL','BOT'))");
            } else {
                statement.execute("ALTER TABLE game_bet ADD CONSTRAINT ck_game_bet_player_kind_snapshot "
                        + "CHECK (player_kind_snapshot IS NULL OR player_kind_snapshot IN ('NORMAL','BOT'))");
                createMysqlTriggers(statement);
            }
        }
    }

    private static void createMysqlTriggers(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TRIGGER trg_game_bet_player_kind_immutable
                BEFORE UPDATE ON game_bet
                FOR EACH ROW
                BEGIN
                    IF OLD.player_kind_snapshot IS NOT NULL
                       AND NOT (NEW.player_kind_snapshot <=> OLD.player_kind_snapshot) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Player kind snapshot is immutable';
                    END IF;
                END
                """);
        statement.execute("""
                CREATE TRIGGER trg_report_order_normal_player_insert
                BEFORE INSERT ON report_upstream_order
                FOR EACH ROW
                BEGIN
                    DECLARE player_kind VARCHAR(16) DEFAULT NULL;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET player_kind = NULL;
                    SELECT player_kind_snapshot INTO player_kind FROM game_bet WHERE id = NEW.bet_id;
                    IF player_kind IS NULL OR player_kind <> 'NORMAL' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Only normal-player bets may be reported';
                    END IF;
                END
                """);
        statement.execute("""
                CREATE TRIGGER trg_report_order_normal_player_update
                BEFORE UPDATE ON report_upstream_order
                FOR EACH ROW
                BEGIN
                    DECLARE player_kind VARCHAR(16) DEFAULT NULL;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET player_kind = NULL;
                    SELECT player_kind_snapshot INTO player_kind FROM game_bet WHERE id = NEW.bet_id;
                    IF player_kind IS NULL OR player_kind <> 'NORMAL' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Only normal-player bets may be reported';
                    END IF;
                END
                """);
        statement.execute("""
                CREATE TRIGGER trg_center_order_normal_player_insert
                BEFORE INSERT ON center_order
                FOR EACH ROW
                BEGIN
                    DECLARE player_kind VARCHAR(16) DEFAULT NULL;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET player_kind = NULL;
                    SELECT player_kind_snapshot INTO player_kind FROM game_bet WHERE id = NEW.local_bet_id;
                    IF player_kind IS NULL OR player_kind <> 'NORMAL' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Center orders require a normal-player bet';
                    END IF;
                END
                """);
        statement.execute("""
                CREATE TRIGGER trg_center_order_normal_player_update
                BEFORE UPDATE ON center_order
                FOR EACH ROW
                BEGIN
                    DECLARE player_kind VARCHAR(16) DEFAULT NULL;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET player_kind = NULL;
                    SELECT player_kind_snapshot INTO player_kind FROM game_bet WHERE id = NEW.local_bet_id;
                    IF player_kind IS NULL OR player_kind <> 'NORMAL' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Center orders require a normal-player bet';
                    END IF;
                END
                """);
    }
}
