CREATE INDEX idx_game_bet_user_created
    ON game_bet (user_id, created_at, id);

CREATE INDEX idx_demo_balance_ledger_user_created
    ON demo_balance_ledger (user_id, created_at, id);

CREATE INDEX idx_test_player_action_account_created
    ON test_player_action (account_id, created_at, id);
