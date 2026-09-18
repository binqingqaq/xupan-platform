ALTER TABLE sys_user
    ADD COLUMN user_type VARCHAR(16) NOT NULL DEFAULT 'REAL';

ALTER TABLE sys_user
    ADD CONSTRAINT ck_sys_user_user_type CHECK (user_type IN ('REAL', 'TEST'));

ALTER TABLE demo_user_account
    ADD COLUMN identity_type VARCHAR(16) NOT NULL DEFAULT 'DEMO';

ALTER TABLE demo_user_account
    ADD CONSTRAINT ck_demo_user_account_identity_type
        CHECK (identity_type IN ('REAL', 'TEST', 'DEMO'));

CREATE INDEX idx_sys_user_user_type_status ON sys_user (user_type, status, id);
CREATE INDEX idx_demo_user_account_identity_status
    ON demo_user_account (identity_type, status, id);
