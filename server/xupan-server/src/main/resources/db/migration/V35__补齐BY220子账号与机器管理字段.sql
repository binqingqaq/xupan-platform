ALTER TABLE agent_group ADD COLUMN username VARCHAR(64) NULL;
ALTER TABLE agent_group ADD COLUMN password_hash VARCHAR(255) NULL;
ALTER TABLE agent_group ADD COLUMN score DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent_group ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE agent_group ADD COLUMN sub_account_manage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_group ADD COLUMN machine_manage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_group ADD COLUMN unified_report_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_group ADD COLUMN report_username VARCHAR(128) NULL;
ALTER TABLE agent_group ADD COLUMN report_password_ciphertext VARCHAR(512) NULL;
ALTER TABLE agent_group ADD COLUMN report_network_code VARCHAR(64) NULL;
ALTER TABLE agent_group ADD COLUMN report_route_code VARCHAR(16) NULL;

ALTER TABLE agent_group ADD CONSTRAINT uk_agent_group_username UNIQUE (username);
CREATE INDEX idx_agent_group_username ON agent_group (username, status);

ALTER TABLE agent ADD COLUMN score DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE agent ADD COLUMN board_open BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE agent ADD COLUMN robot_manage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent ADD COLUMN chase_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent ADD COLUMN bot_count INT NOT NULL DEFAULT 0;
ALTER TABLE agent ADD COLUMN close_seconds INT NOT NULL DEFAULT 0;
ALTER TABLE agent ADD COLUMN cancel_seconds INT NOT NULL DEFAULT 0;
ALTER TABLE agent ADD COLUMN rebate_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0000;
ALTER TABLE agent ADD COLUMN odds_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0000;
ALTER TABLE agent ADD COLUMN special_rebate_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0000;
ALTER TABLE agent ADD COLUMN special_odds_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0000;
ALTER TABLE agent ADD COLUMN total_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN positive_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN angle_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN strict_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN tong_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN car_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN special_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN odd_even_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN big_small_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN fan_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN add_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN player_max_stake DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN player_min_stake DECIMAL(18,2) NOT NULL DEFAULT 0.00;

ALTER TABLE agent ADD CONSTRAINT ck_agent_score CHECK (score >= 0);
ALTER TABLE agent ADD CONSTRAINT ck_agent_bot_count CHECK (bot_count >= 0);
ALTER TABLE agent ADD CONSTRAINT ck_agent_timing CHECK (close_seconds >= 0 AND cancel_seconds >= 0);

CREATE TABLE agent_game (
    agent_id BIGINT NOT NULL,
    game_code VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, game_code),
    CONSTRAINT fk_agent_game_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'SUB_ACCOUNT_MANAGE', '管理子账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'SUB_ACCOUNT_MANAGE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'MACHINE_MANAGE', '管理机器账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'MACHINE_MANAGE');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('SUB_ACCOUNT_MANAGE', 'MACHINE_MANAGE')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
