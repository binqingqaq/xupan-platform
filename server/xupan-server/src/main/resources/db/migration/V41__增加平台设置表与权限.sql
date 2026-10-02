CREATE TABLE platform_setting (
    id BIGINT PRIMARY KEY,
    site_title VARCHAR(128) NOT NULL,
    announcement TEXT NULL,
    domain_links TEXT NULL,
    chat_warning VARCHAR(2000) NULL,
    information TEXT NULL,
    header_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status_bar_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    keyboard_mode BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_platform_setting_operator FOREIGN KEY (updated_by) REFERENCES sys_user(id)
);

INSERT INTO platform_setting
    (id, site_title, chat_warning, header_enabled, status_bar_enabled, keyboard_mode)
SELECT 1,
       COALESCE((SELECT display_name FROM chat_room WHERE room_code = 'main'), '公开大厅'),
       '仅供本地研究',
       TRUE,
       TRUE,
       FALSE;

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'PLATFORM_SETTINGS_READ', '查看平台设置'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'PLATFORM_SETTINGS_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'PLATFORM_SETTINGS_WRITE', '修改平台设置'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'PLATFORM_SETTINGS_WRITE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('PLATFORM_SETTINGS_READ', 'PLATFORM_SETTINGS_WRITE')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
