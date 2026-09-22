package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/** Allows the linked wallet account to retain history after a player is soft-deleted. */
public class V21__完善玩家软删除状态 extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(java.util.Locale.ROOT).contains("h2");
        String dropConstraint = h2 ? "DROP CONSTRAINT " : "DROP CHECK ";
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE demo_user_account " + dropConstraint + "ck_demo_user_account_status");
            statement.execute("ALTER TABLE demo_user_account ADD CONSTRAINT ck_demo_user_account_status "
                    + "CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'))");
            statement.execute("CREATE INDEX idx_demo_user_account_status_user "
                    + "ON demo_user_account (status, sys_user_id, id)");
        }
    }
}
