package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;
import java.util.Locale;

/** Repairs databases where the already-applied V79 check constraint omitted BET_TEXT. */
public class V80__修复托自动下注动作类型约束 extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("h2");
        String dropConstraint = h2 ? "DROP CONSTRAINT " : "DROP CHECK ";
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE test_player_action " + dropConstraint + "ck_test_player_action_type");
            statement.execute("ALTER TABLE test_player_action ADD CONSTRAINT ck_test_player_action_type "
                    + "CHECK (action_type IN ('CHAT_TEXT','BET_TEXT','AUTO_UP','AUTO_DOWN','AUTO_QUERY',"
                    + "'LEGACY_BET_TEXT','LEGACY_TOP_UP_REQUEST'))");
        }
    }
}
