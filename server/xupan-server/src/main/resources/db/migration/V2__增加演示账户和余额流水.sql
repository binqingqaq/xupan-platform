CREATE TABLE demo_user_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    balance DECIMAL(18, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_demo_user_account_code UNIQUE (user_code),
    CONSTRAINT ck_demo_user_account_balance CHECK (balance >= 0),
    CONSTRAINT ck_demo_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE demo_balance_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    balance_before DECIMAL(18, 2) NOT NULL,
    balance_after DECIMAL(18, 2) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    operator_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_demo_balance_ledger_user FOREIGN KEY (user_id) REFERENCES demo_user_account(id),
    CONSTRAINT ck_demo_balance_ledger_amount CHECK (amount <> 0)
);

INSERT INTO demo_user_account (user_code, display_name, balance)
VALUES ('DEMO-USER', '演示用户', 1000.00);

ALTER TABLE game_bet ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE game_bet
    ADD CONSTRAINT fk_game_bet_demo_user FOREIGN KEY (user_id) REFERENCES demo_user_account(id);

CREATE INDEX idx_demo_balance_ledger_user ON demo_balance_ledger (user_id, id);
CREATE INDEX idx_game_bet_user ON game_bet (user_id, id);
