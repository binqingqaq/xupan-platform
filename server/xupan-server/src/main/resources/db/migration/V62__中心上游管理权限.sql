-- 中心上游管理权限（阶段 B）。
--
-- 依据：docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md §5 阶段 B
--   与《代理报网盘口与结算业务规则说明》§23.7「可运营档：在超管后台加中心上游菜单」。
--
-- 口径：中心上游是超级管理员的运营能力（`/platform-admin` 面），与代理后台 `/console` 不混。
--   * CENTER_AGENT_MANAGE  中心代理账户、额度、Token 的管理权（默认只给 ADMIN）
--   * CENTER_ORDER_READ    中心订单与对账的查看权（默认只给 ADMIN）
--
-- 不授予 SUB_ACCOUNT / AGENT / USER / MODERATOR：中心是"代理的上级盘口"，
-- 代理自己能看的是自己那台机器的报网状态，不是中心的全量账户与全量订单。

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CENTER_AGENT_MANAGE', '中心上游代理管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CENTER_AGENT_MANAGE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CENTER_ORDER_READ', '中心上游订单查看'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CENTER_ORDER_READ');

-- ADMIN 持有全部权限（V5 的既有口径），这里只需保证新增权限也进 ADMIN。
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('CENTER_AGENT_MANAGE', 'CENTER_ORDER_READ')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
