INSERT INTO sys_permission (permission_code, display_name, resource_type, description)
SELECT 'SYSTEM_MONITOR_READ', '查看系统运行监控', 'API', '读取健康、运行信息和指标'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'SYSTEM_MONITOR_READ'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'SYSTEM_MONITOR_READ'
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );
