CREATE TABLE report_network (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    network_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    website_url VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    search_code VARCHAR(128) NULL,
    api_key_ciphertext VARCHAR(512) NULL,
    api_version VARCHAR(32) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_report_network_code UNIQUE (network_code),
    CONSTRAINT fk_report_network_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_report_network_updater FOREIGN KEY (updated_by) REFERENCES sys_user(id),
    CONSTRAINT ck_report_network_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_report_network_status ON report_network (status, deleted_at, id);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'REPORT_NETWORK_READ', '查看网盘设置'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'REPORT_NETWORK_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'REPORT_NETWORK_WRITE', '修改网盘设置'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'REPORT_NETWORK_WRITE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('REPORT_NETWORK_READ', 'REPORT_NETWORK_WRITE')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
