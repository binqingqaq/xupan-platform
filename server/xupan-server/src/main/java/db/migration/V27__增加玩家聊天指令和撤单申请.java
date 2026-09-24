package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;
import java.util.Locale;

/** Adds durable chat-command state and allows a bet to be marked as canceled. */
public class V27__增加玩家聊天指令和撤单申请 extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        boolean h2 = context.getConnection().getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("h2");
        String dropConstraint = h2 ? "DROP CONSTRAINT " : "DROP CHECK ";
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE game_bet " + dropConstraint + "ck_game_bet_status");
            statement.execute("ALTER TABLE game_bet ADD CONSTRAINT ck_game_bet_status "
                    + "CHECK (settlement_status IN ('PENDING', 'WIN', 'DRAW', 'LOSE', 'CANCELED'))");
            statement.execute("ALTER TABLE game_bet ADD COLUMN canceled_at TIMESTAMP NULL");
            statement.execute("CREATE INDEX idx_game_bet_cancel_window "
                    + "ON game_bet (user_id, issue_number, settlement_status, created_at)");

            statement.execute("""
                    CREATE TABLE player_point_request (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        request_type VARCHAR(16) NOT NULL,
                        amount DECIMAL(18, 2) NOT NULL,
                        status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                        client_message_id VARCHAR(128) NOT NULL,
                        source_message_id BIGINT NOT NULL,
                        requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        reviewed_at TIMESTAMP NULL,
                        reviewer_user_id BIGINT NULL,
                        review_reason VARCHAR(255) NULL,
                        CONSTRAINT fk_point_request_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
                        CONSTRAINT uk_point_request_client UNIQUE (user_id, client_message_id),
                        CONSTRAINT ck_point_request_type CHECK (request_type IN ('TOP_UP', 'DOWN')),
                        CONSTRAINT ck_point_request_amount CHECK (amount > 0),
                        CONSTRAINT ck_point_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
                    )
                    """);
            statement.execute("CREATE INDEX idx_point_request_status_time "
                    + "ON player_point_request (status, requested_at)");
        }
    }
}
