ALTER TABLE agent
    ADD COLUMN betting_config_version INT NOT NULL DEFAULT 1;

ALTER TABLE demo_user_account
    ADD COLUMN rebate_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0000;

UPDATE demo_user_account d
   SET rebate_rate = (SELECT a.rebate_rate FROM agent a WHERE a.id = d.agent_id);

ALTER TABLE game_bet
    ADD COLUMN batch_id BIGINT NULL,
    ADD COLUMN instruction_no INT NULL,
    ADD COLUMN machine_id_snapshot BIGINT NULL,
    ADD COLUMN machine_config_version_snapshot INT NOT NULL DEFAULT 0,
    ADD COLUMN machine_odds_rate_snapshot DECIMAL(10,4) NULL,
    ADD COLUMN special_odds_rate_snapshot DECIMAL(10,4) NULL,
    ADD COLUMN quota_bucket VARCHAR(32) NULL,
    ADD COLUMN selection_key VARCHAR(64) NULL;

CREATE TABLE game_bet_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    machine_id BIGINT NOT NULL,
    game_code VARCHAR(64) NOT NULL,
    issue_number VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    total_stake DECIMAL(18,2) NOT NULL,
    machine_config_version INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_bet_batch_account_key UNIQUE (account_id, idempotency_key),
    CONSTRAINT fk_game_bet_batch_account FOREIGN KEY (account_id) REFERENCES demo_user_account(id),
    CONSTRAINT fk_game_bet_batch_machine FOREIGN KEY (machine_id) REFERENCES agent(id),
    CONSTRAINT ck_game_bet_batch_total CHECK (total_stake > 0),
    CONSTRAINT ck_game_bet_batch_version CHECK (machine_config_version > 0),
    CONSTRAINT ck_game_bet_batch_status CHECK (status = 'ACCEPTED')
);

CREATE INDEX idx_game_bet_batch_issue
    ON game_bet_batch (machine_id, account_id, game_code, issue_number, id);

ALTER TABLE game_bet
    ADD CONSTRAINT fk_game_bet_batch FOREIGN KEY (batch_id) REFERENCES game_bet_batch(id);

CREATE UNIQUE INDEX uk_game_bet_batch_instruction
    ON game_bet (batch_id, instruction_no);

CREATE INDEX idx_game_bet_quota_usage
    ON game_bet (user_id, game_code, issue_number, quota_bucket, selection_key, settlement_status);

CREATE TABLE game_bet_selection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bet_id BIGINT NOT NULL,
    selection_no INT NOT NULL,
    selection_key VARCHAR(64) NOT NULL,
    quota_bucket VARCHAR(32) NOT NULL,
    number_value SMALLINT NULL,
    unit_stake DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_bet_selection_order UNIQUE (bet_id, selection_no),
    CONSTRAINT fk_game_bet_selection_bet FOREIGN KEY (bet_id) REFERENCES game_bet(id) ON DELETE CASCADE,
    CONSTRAINT ck_game_bet_selection_no CHECK (selection_no > 0),
    CONSTRAINT ck_game_bet_selection_stake CHECK (unit_stake > 0),
    CONSTRAINT ck_game_bet_selection_number CHECK (number_value IS NULL OR number_value BETWEEN 1 AND 20)
);

CREATE INDEX idx_game_bet_selection_quota
    ON game_bet_selection (quota_bucket, number_value, bet_id);
