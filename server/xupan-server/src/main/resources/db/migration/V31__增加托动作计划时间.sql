ALTER TABLE test_player_action
    ADD COLUMN scheduled_at TIMESTAMP(6) NULL;

CREATE INDEX idx_test_player_action_schedule
    ON test_player_action (status, scheduled_at, id);
