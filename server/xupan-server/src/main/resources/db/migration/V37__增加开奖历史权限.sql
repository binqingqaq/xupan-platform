INSERT INTO sys_permission (permission_code, display_name)
SELECT 'DRAW_HISTORY_READ', '查看开奖历史'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'DRAW_HISTORY_READ');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'DRAW_HISTORY_READ'
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
