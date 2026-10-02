ALTER TABLE agent_group ADD COLUMN sys_user_id BIGINT NULL;

ALTER TABLE agent_group ADD CONSTRAINT uk_agent_group_sys_user UNIQUE (sys_user_id);

ALTER TABLE agent_group ADD CONSTRAINT fk_agent_group_sys_user FOREIGN KEY (sys_user_id) REFERENCES sys_user(id);

INSERT INTO sys_user (username, display_name, password_hash, status, user_type, auth_mode, internal_code)
SELECT g.username, g.display_name, g.password_hash, 'ACTIVE', 'REAL', 'PASSWORD', CONCAT('SUB-', g.id)
FROM agent_group g
WHERE g.username IS NOT NULL
  AND g.password_hash IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.username = g.username);

UPDATE agent_group g
   SET sys_user_id = (SELECT u.id FROM sys_user u WHERE u.username = g.username)
 WHERE g.sys_user_id IS NULL
   AND g.username IS NOT NULL
   AND EXISTS (SELECT 1 FROM sys_user u WHERE u.username = g.username);

INSERT INTO sys_role (role_code, display_name)
SELECT 'SUB_ACCOUNT', '超级管理子账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'SUB_ACCOUNT');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'PLATFORM_HOME_READ', '查看超级管理开奖信息'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'PLATFORM_HOME_READ');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.permission_code IN (
    'PLATFORM_HOME_READ', 'GAME_CURRENT_READ', 'MACHINE_MANAGE',
    'REPORT_READ', 'DRAW_HISTORY_READ', 'PLATFORM_PASSWORD_MANAGE'
) WHERE r.role_code = 'SUB_ACCOUNT'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission e WHERE e.role_id=r.id AND e.permission_id=p.id);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.permission_code = 'PLATFORM_HOME_READ'
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission e WHERE e.role_id=r.id AND e.permission_id=p.id);
