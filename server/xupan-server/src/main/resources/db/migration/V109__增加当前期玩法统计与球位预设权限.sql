INSERT INTO sys_permission (permission_code, display_name)
SELECT 'BETTING_PLAY_STATS_READ', '查看当前期下注玩法统计'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'BETTING_PLAY_STATS_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'BETTING_DRAW_PRESET_MANAGE', '预设当前期开奖号码'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'BETTING_DRAW_PRESET_MANAGE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'BETTING_PLAY_STATS_READ'
WHERE r.role_code IN ('ADMIN', 'SUB_ACCOUNT')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'BETTING_DRAW_PRESET_MANAGE'
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
