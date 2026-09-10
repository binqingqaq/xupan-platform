ALTER TABLE demo_user_account ADD COLUMN sys_user_id BIGINT NULL;

ALTER TABLE demo_user_account
    ADD CONSTRAINT uk_demo_user_account_sys_user UNIQUE (sys_user_id);

ALTER TABLE demo_user_account
    ADD CONSTRAINT fk_demo_user_account_sys_user
        FOREIGN KEY (sys_user_id) REFERENCES sys_user(id);

ALTER TABLE demo_balance_ledger ADD COLUMN operator_user_id BIGINT NULL;
ALTER TABLE demo_balance_ledger ADD COLUMN idempotency_key VARCHAR(128) NULL;
ALTER TABLE demo_balance_ledger ADD COLUMN related_bet_id BIGINT NULL;
ALTER TABLE demo_balance_ledger ADD COLUMN issue_number VARCHAR(64) NULL;

ALTER TABLE demo_balance_ledger
    ADD CONSTRAINT uk_demo_balance_ledger_idempotency UNIQUE (idempotency_key);

ALTER TABLE demo_balance_ledger
    ADD CONSTRAINT fk_demo_balance_ledger_operator
        FOREIGN KEY (operator_user_id) REFERENCES sys_user(id);

ALTER TABLE demo_balance_ledger
    ADD CONSTRAINT fk_demo_balance_ledger_bet
        FOREIGN KEY (related_bet_id) REFERENCES game_bet(id);

ALTER TABLE game_bet ADD COLUMN request_idempotency_key VARCHAR(128) NULL;

ALTER TABLE game_bet
    ADD CONSTRAINT uk_game_bet_user_request_key
        UNIQUE (user_id, request_idempotency_key);

CREATE INDEX idx_demo_user_account_sys_user
    ON demo_user_account (sys_user_id, status);

CREATE INDEX idx_demo_balance_ledger_related_bet
    ON demo_balance_ledger (related_bet_id, id);

CREATE INDEX idx_demo_balance_ledger_issue
    ON demo_balance_ledger (issue_number, id);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'WALLET_READ', '查看本人虚拟余额'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'WALLET_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'WALLET_GRANT', '分配成员虚拟余额'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'WALLET_GRANT'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'WALLET_ADJUST', '调整成员虚拟余额'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'WALLET_ADJUST'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'WALLET_LEDGER_READ', '查看成员虚拟余额流水'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'WALLET_LEDGER_READ'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'WALLET_READ'
WHERE r.role_code IN ('USER', 'MODERATOR')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission x
      WHERE x.role_id = r.id AND x.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN (
    'WALLET_READ', 'WALLET_GRANT', 'WALLET_ADJUST', 'WALLET_LEDGER_READ'
)
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission x
      WHERE x.role_id = r.id AND x.permission_id = p.id
  );
