ALTER TABLE game_bet ADD COLUMN edit_version INT NOT NULL DEFAULT 0;
ALTER TABLE game_bet ADD COLUMN last_edited_at TIMESTAMP NULL;

CREATE TABLE game_bet_edit_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bet_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    old_play_type VARCHAR(32) NOT NULL,
    new_play_type VARCHAR(32) NOT NULL,
    old_parameters_text VARCHAR(255) NOT NULL,
    new_parameters_text VARCHAR(255) NOT NULL,
    old_stake DECIMAL(18, 2) NOT NULL,
    new_stake DECIMAL(18, 2) NOT NULL,
    old_odds DECIMAL(10, 3) NOT NULL,
    new_odds DECIMAL(10, 3) NOT NULL,
    stake_delta DECIMAL(18, 2) NOT NULL,
    ledger_id BIGINT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_bet_edit_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_game_bet_edit_bet FOREIGN KEY (bet_id) REFERENCES game_bet(id),
    CONSTRAINT fk_game_bet_edit_account FOREIGN KEY (account_id) REFERENCES demo_user_account(id),
    CONSTRAINT fk_game_bet_edit_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_game_bet_edit_ledger FOREIGN KEY (ledger_id) REFERENCES demo_balance_ledger(id)
);

CREATE INDEX idx_game_bet_edit_bet ON game_bet_edit_record (bet_id, created_at, id);
CREATE INDEX idx_game_bet_edit_operator ON game_bet_edit_record (operator_user_id, created_at, id);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ORDER_CORRECTION_READ', '查看订单改单'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'ORDER_CORRECTION_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ORDER_CORRECTION_MANAGE', '执行订单改单'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'ORDER_CORRECTION_MANAGE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('ORDER_CORRECTION_READ', 'ORDER_CORRECTION_MANAGE')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
