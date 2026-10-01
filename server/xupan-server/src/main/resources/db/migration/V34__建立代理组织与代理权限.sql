CREATE TABLE agent_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_group_code UNIQUE (group_code),
    CONSTRAINT fk_agent_group_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT ck_agent_group_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_agent_group_status ON agent_group (status, id);

CREATE TABLE agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    group_id BIGINT NULL,
    account_user_id BIGINT NULL,
    system_owned BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_code UNIQUE (agent_code),
    CONSTRAINT uk_agent_account_user UNIQUE (account_user_id),
    CONSTRAINT fk_agent_group FOREIGN KEY (group_id) REFERENCES agent_group(id),
    CONSTRAINT fk_agent_account_user FOREIGN KEY (account_user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_agent_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT ck_agent_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_agent_account_binding CHECK (system_owned = TRUE OR account_user_id IS NOT NULL)
);

CREATE INDEX idx_agent_group_lookup ON agent (group_id, status, id);
CREATE INDEX idx_agent_account_status ON agent (status, id);

INSERT INTO agent (id, agent_code, display_name, system_owned, status)
VALUES (1, 'PLATFORM_DIRECT', '平台直属代理', TRUE, 'ACTIVE');

ALTER TABLE demo_user_account
    ADD COLUMN agent_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE demo_user_account
    ADD CONSTRAINT fk_demo_user_account_agent FOREIGN KEY (agent_id) REFERENCES agent(id);

CREATE INDEX idx_demo_user_account_agent
    ON demo_user_account (agent_id, status, player_kind, id);

INSERT INTO sys_role (role_code, display_name)
SELECT 'AGENT', '代理'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'AGENT');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'AGENT_MANAGE', '管理渠道组与代理'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'AGENT_MANAGE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'AGENT_CONSOLE_READ', '访问代理后台'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'AGENT_CONSOLE_READ');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('AGENT_MANAGE', 'AGENT_CONSOLE_READ')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'AGENT_CONSOLE_READ'
WHERE r.role_code = 'AGENT'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
