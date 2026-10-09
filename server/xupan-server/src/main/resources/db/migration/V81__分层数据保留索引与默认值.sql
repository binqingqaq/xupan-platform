-- 将聊天消息保留期默认值收敛到已确认的五天，并把已有聊天室设置同步到该期限。
ALTER TABLE chat_room ALTER COLUMN message_retention_days SET DEFAULT 5;
UPDATE chat_room SET message_retention_days = 5;

-- 定时保留清理按状态 + 时间 + 主键游标分批选取，避免全表扫描和长事务。
CREATE INDEX idx_chat_outbox_retention
    ON chat_outbox (status, created_at, id);

CREATE INDEX idx_chat_robot_dispatch_retention
    ON chat_robot_dispatch (status, created_at, id);

CREATE INDEX idx_game_issue_event_retention
    ON game_issue_event (created_at, id);

CREATE INDEX idx_test_player_action_retention
    ON test_player_action (status, created_at, id);

CREATE INDEX idx_test_player_action_bet
    ON test_player_action (bet_id);

CREATE INDEX idx_test_player_action_message
    ON test_player_action (message_id);

CREATE INDEX idx_center_recon_disposition_bet
    ON center_reconciliation_disposition (bet_id, id);

CREATE INDEX idx_demo_balance_ledger_retention
    ON demo_balance_ledger (created_at, id);

CREATE INDEX idx_game_bet_settled_retention
    ON game_bet (settlement_status, settled_at, id);
