ALTER TABLE demo_user_account
    ADD COLUMN player_kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE demo_user_account
    ADD CONSTRAINT ck_demo_user_account_player_kind
        CHECK (player_kind IN ('NORMAL', 'BOT'));

UPDATE demo_user_account
   SET player_kind = 'BOT'
 WHERE identity_type = 'TEST';

CREATE INDEX idx_demo_user_account_player_kind
    ON demo_user_account (player_kind, identity_type, status, id);

CREATE TABLE test_player_behavior (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    bets_per_issue INT NOT NULL DEFAULT 0,
    stake_min DECIMAL(14, 2) NOT NULL DEFAULT 100.00,
    stake_max DECIMAL(14, 2) NOT NULL DEFAULT 100.00,
    chat_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    messages_per_issue INT NOT NULL DEFAULT 0,
    next_run_at TIMESTAMP(6) NULL,
    last_issue_number VARCHAR(64) NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(255) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_test_player_behavior_account UNIQUE (account_id),
    CONSTRAINT fk_test_player_behavior_account FOREIGN KEY (account_id) REFERENCES demo_user_account(id),
    CONSTRAINT ck_test_player_behavior_bets CHECK (bets_per_issue BETWEEN 0 AND 20),
    CONSTRAINT ck_test_player_behavior_messages CHECK (messages_per_issue BETWEEN 0 AND 20),
    CONSTRAINT ck_test_player_behavior_stakes CHECK (stake_min > 0 AND stake_max >= stake_min)
);

CREATE TABLE test_player_action (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    behavior_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    issue_number VARCHAR(64) NULL,
    action_no INT NOT NULL,
    action_type VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    source_text VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    message_id BIGINT NULL,
    bet_id BIGINT NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_until TIMESTAMP(6) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(255) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_test_player_action_key UNIQUE (idempotency_key),
    CONSTRAINT uk_test_player_action_slot UNIQUE (behavior_id, issue_number, action_no, action_type),
    CONSTRAINT fk_test_player_action_behavior FOREIGN KEY (behavior_id) REFERENCES test_player_behavior(id),
    CONSTRAINT fk_test_player_action_account FOREIGN KEY (account_id) REFERENCES demo_user_account(id),
    CONSTRAINT ck_test_player_action_type CHECK (action_type IN ('CHAT_TEXT', 'BET_TEXT')),
    CONSTRAINT ck_test_player_action_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'SKIPPED', 'FAILED'))
);

CREATE INDEX idx_test_player_action_dispatch
    ON test_player_action (status, lease_until, id);

CREATE INDEX idx_test_player_action_user_issue
    ON test_player_action (user_id, issue_number, id);
