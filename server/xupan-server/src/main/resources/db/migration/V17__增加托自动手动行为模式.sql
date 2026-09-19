ALTER TABLE test_player_behavior
    ADD COLUMN run_mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER enabled;

UPDATE test_player_behavior
   SET run_mode = 'AUTOMATIC'
 WHERE enabled = TRUE;

ALTER TABLE test_player_behavior
    ADD CONSTRAINT ck_test_player_behavior_mode
        CHECK (run_mode IN ('AUTOMATIC', 'MANUAL'));

CREATE INDEX idx_test_player_behavior_mode
    ON test_player_behavior (run_mode, enabled, account_id);
