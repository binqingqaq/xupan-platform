ALTER TABLE sys_user
    ADD COLUMN internal_code VARCHAR(64) NULL;

ALTER TABLE demo_user_account
    ADD COLUMN member_code VARCHAR(32) NULL;

UPDATE sys_user
   SET internal_code = CONCAT('wxid_', id)
 WHERE internal_code IS NULL;

UPDATE demo_user_account
   SET member_code = CONCAT('v', id)
 WHERE member_code IS NULL;

CREATE UNIQUE INDEX uk_sys_user_internal_code
    ON sys_user (internal_code);

CREATE UNIQUE INDEX uk_demo_user_account_member_code
    ON demo_user_account (member_code);

ALTER TABLE sys_user
    MODIFY COLUMN internal_code VARCHAR(64) NOT NULL;

ALTER TABLE demo_user_account
    MODIFY COLUMN member_code VARCHAR(32) NOT NULL;
