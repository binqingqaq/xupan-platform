package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Fixes AU8 to seven balls for configuration, issue snapshots and direct MySQL writes. */
public class V108__AU8固定七球业务规则 extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        boolean mysql = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("mysql");
        try (Statement statement = context.getConnection().createStatement()) {
            if (count(statement, "SELECT COUNT(*) FROM game_issue "
                    + "WHERE game_code='AU8' AND (ball_count <> 7 OR number_8 IS NOT NULL)") > 0) {
                throw new IllegalStateException("AU8 存在非七球期次，请按开发计划重建数据库后再迁移");
            }
            if (count(statement, "SELECT COUNT(*) FROM game_ball_rule_change "
                    + "WHERE game_code='AU8' AND (from_ball_count <> 7 OR to_ball_count <> 7)") > 0) {
                throw new IllegalStateException("AU8 存在球数切换记录，请按开发计划重建数据库后再迁移");
            }
            int updated = statement.executeUpdate("UPDATE game_definition "
                    + "SET next_issue_ball_count=7, ball_indexes='1,2,3,4,5,6,7', "
                    + "updated_at=CURRENT_TIMESTAMP WHERE game_code='AU8' AND deleted_at IS NULL");
            if (updated != 1) {
                throw new IllegalStateException("AU8 游戏配置不存在或不唯一，无法固定为七球");
            }
            statement.execute("ALTER TABLE game_definition ADD CONSTRAINT ck_au8_definition_seven_balls "
                    + "CHECK (game_code <> 'AU8' OR "
                    + "(next_issue_ball_count=7 AND ball_indexes='1,2,3,4,5,6,7'))");
            statement.execute("ALTER TABLE game_issue ADD CONSTRAINT ck_au8_issue_seven_balls "
                    + "CHECK (game_code <> 'AU8' OR ball_count=7)");
            statement.execute("ALTER TABLE game_issue ADD CONSTRAINT ck_au8_issue_no_eighth_number "
                    + "CHECK (game_code <> 'AU8' OR number_8 IS NULL)");
            statement.execute("ALTER TABLE game_ball_rule_change ADD CONSTRAINT ck_au8_no_ball_switch "
                    + "CHECK (game_code <> 'AU8' OR (from_ball_count=7 AND to_ball_count=7))");
            if (mysql) createMysqlTriggers(statement);
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void createMysqlTriggers(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TRIGGER trg_au8_game_definition_seven_insert
                BEFORE INSERT ON game_definition
                FOR EACH ROW
                BEGIN
                    IF NEW.game_code = 'AU8'
                       AND (NEW.next_issue_ball_count <> 7 OR NEW.ball_indexes <> '1,2,3,4,5,6,7') THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AU8 supports seven balls only';
                    END IF;
                END
                """);
        statement.execute("""
                CREATE TRIGGER trg_au8_game_definition_seven_update
                BEFORE UPDATE ON game_definition
                FOR EACH ROW
                BEGIN
                    IF NEW.game_code = 'AU8'
                       AND (NEW.next_issue_ball_count <> 7 OR NEW.ball_indexes <> '1,2,3,4,5,6,7') THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AU8 supports seven balls only';
                    END IF;
                END
                """);
        statement.execute("""
                CREATE TRIGGER trg_au8_game_issue_seven_insert
                BEFORE INSERT ON game_issue
                FOR EACH ROW
                BEGIN
                    IF NEW.game_code = 'AU8' AND (NEW.ball_count <> 7 OR NEW.number_8 IS NOT NULL) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AU8 issues require seven balls';
                    END IF;
                END
                """);
        statement.execute("""
                CREATE TRIGGER trg_au8_game_issue_seven_update
                BEFORE UPDATE ON game_issue
                FOR EACH ROW
                BEGIN
                    IF NEW.game_code = 'AU8' AND (NEW.ball_count <> 7 OR NEW.number_8 IS NOT NULL) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AU8 issues require seven balls';
                    END IF;
                END
                """);
    }
}
