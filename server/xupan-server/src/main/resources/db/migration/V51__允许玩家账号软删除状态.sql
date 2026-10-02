ALTER TABLE demo_user_account DROP CONSTRAINT ck_demo_user_account_status;
ALTER TABLE demo_user_account ADD CONSTRAINT ck_demo_user_account_status
    CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'));
CREATE INDEX idx_demo_user_account_status_kind ON demo_user_account (status, player_kind, id);