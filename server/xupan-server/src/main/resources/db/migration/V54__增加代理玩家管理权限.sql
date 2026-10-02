INSERT INTO sys_permission (permission_code, display_name)
SELECT 'AGENT_PLAYER_MANAGE', '管理代理玩家与托'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'AGENT_PLAYER_MANAGE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'AGENT_PLAYER_MANAGE'
WHERE r.role_code IN ('ADMIN', 'AGENT')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
