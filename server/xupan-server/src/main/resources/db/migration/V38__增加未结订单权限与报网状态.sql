ALTER TABLE game_bet
    ADD COLUMN report_status VARCHAR(16) NOT NULL DEFAULT 'UNREPORTED';

ALTER TABLE game_bet
    ADD CONSTRAINT ck_game_bet_report_status
        CHECK (report_status IN ('UNREPORTED', 'REPORTED', 'FAILED'));

CREATE INDEX idx_game_bet_unsettled_admin
    ON game_bet (settlement_status, created_at, id);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'UNSETTLED_ORDER_READ', '查看未结订单'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'UNSETTLED_ORDER_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'UNSETTLED_ORDER_DELETE', '删除未结订单'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'UNSETTLED_ORDER_DELETE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('UNSETTLED_ORDER_READ', 'UNSETTLED_ORDER_DELETE')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
