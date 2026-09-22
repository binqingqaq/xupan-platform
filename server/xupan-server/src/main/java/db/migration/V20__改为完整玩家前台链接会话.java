package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/** Migrates the former chat-only link scope to the complete player frontend scope. */
public class V20__改为完整玩家前台链接会话 extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(java.util.Locale.ROOT).contains("h2");
        String dropConstraint = h2 ? "DROP CONSTRAINT " : "DROP CHECK ";
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE auth_session " + dropConstraint + "ck_auth_session_scope");
            statement.execute("ALTER TABLE auth_session ADD CONSTRAINT ck_auth_session_scope "
                    + "CHECK (scope IS NULL OR scope IN ('CHAT_ONLY', 'PLAYER_FULL'))");
            statement.execute("ALTER TABLE auth_ws_ticket " + dropConstraint + "ck_auth_ws_ticket_scope");
            statement.execute("ALTER TABLE auth_ws_ticket ADD CONSTRAINT ck_auth_ws_ticket_scope "
                    + "CHECK (scope IS NULL OR scope IN ('CHAT_ONLY', 'PLAYER_FULL'))");
            statement.execute("ALTER TABLE player_access_link " + dropConstraint + "ck_player_access_link_scope");
            statement.execute("ALTER TABLE player_access_link ADD CONSTRAINT ck_player_access_link_scope "
                    + "CHECK (scope IN ('CHAT_ONLY', 'PLAYER_FULL'))");
            statement.execute("UPDATE player_access_link SET scope = 'PLAYER_FULL' WHERE scope = 'CHAT_ONLY'");
            statement.execute("UPDATE auth_session SET scope = 'PLAYER_FULL' "
                    + "WHERE auth_mode = 'PLAYER_LINK' AND scope = 'CHAT_ONLY'");
            statement.execute("UPDATE auth_ws_ticket SET scope = 'PLAYER_FULL' WHERE scope = 'CHAT_ONLY'");
        }
    }
}
