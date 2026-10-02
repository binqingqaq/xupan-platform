-- 代理后台（/console，BY220 robot.php）机器级报网、线路与显示控制字段；
-- 订单上报状态扩展超时态；平台开放注册开关；子账号进运营后台的权限授予。
--
-- 依据：用户 2026-10-02 确认的 7 点（docs/需求基线/2026-10-02-代理后台统一到console与机器账号收敛.md）
-- 与实施计划第 3、4、11 节。报网只做内部持久化与本地模拟，不接入真实外部上游、不保存真实凭据。

-- 1. 机器级报网配置（BY220 x_robot.flyers_*）
ALTER TABLE agent ADD COLUMN report_type VARCHAR(32) NULL;
ALTER TABLE agent ADD COLUMN report_auto BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent ADD COLUMN report_online BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent ADD COLUMN report_withdraw BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent ADD COLUMN report_username VARCHAR(128) NULL;
-- 飞单密码只保存密文，禁止明文入库、入日志、入审计
ALTER TABLE agent ADD COLUMN report_password_ciphertext VARCHAR(512) NULL;
-- 上游地址只保存不请求；本地模拟不会访问该地址
ALTER TABLE agent ADD COLUMN report_url VARCHAR(512) NULL;
ALTER TABLE agent ADD COLUMN report_network_code VARCHAR(64) NULL;
ALTER TABLE agent ADD COLUMN report_route_code VARCHAR(16) NULL;
ALTER TABLE agent ADD COLUMN report_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN report_stake DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent ADD COLUMN report_last_login_at TIMESTAMP NULL;

-- 2. 机器级线路与开放注册（BY220 x_robot.url / wxlink / 开放注册）
ALTER TABLE agent ADD COLUMN link_domain VARCHAR(255) NULL;
ALTER TABLE agent ADD COLUMN wechat_link VARCHAR(255) NULL;
ALTER TABLE agent ADD COLUMN registration_open BOOLEAN NULL;

-- 3. 机器级显示控制：仅 BY220 有明确写入点的 hideMsg（rbt/Index.php:245 UpdateHideMsg）
ALTER TABLE agent ADD COLUMN hide_message BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent ADD CONSTRAINT ck_agent_report_route
    CHECK (report_route_code IS NULL OR report_route_code IN ('A', 'B', 'C', 'D'));
ALTER TABLE agent ADD CONSTRAINT ck_agent_report_amounts
    CHECK (report_balance >= 0 AND report_stake >= 0);
ALTER TABLE agent ADD CONSTRAINT ck_agent_report_type
    CHECK (report_type IS NULL OR report_type IN ('NONE', 'TT', 'K28', 'LS8899', 'WNS', 'LX', 'HY', 'CENTER'));

-- 4. 订单上报状态扩展超时态（BY220 x_record.flyers_status 的 3 同时表示失败与超时，
--    这里拆成 FAILED 与 TIMEOUT 两个可区分状态，便于"超时订单"计数与重报）
ALTER TABLE game_bet DROP CONSTRAINT ck_game_bet_report_status;
ALTER TABLE game_bet ADD CONSTRAINT ck_game_bet_report_status
    CHECK (report_status IN ('UNREPORTED', 'REPORTED', 'FAILED', 'TIMEOUT'));
CREATE INDEX idx_game_bet_report_status ON game_bet (report_status, created_at, id);

-- 5. 平台级开放注册开关（BY220 设置页；机器级未设置时按平台值兜底）
ALTER TABLE platform_setting ADD COLUMN registration_open BOOLEAN NOT NULL DEFAULT FALSE;

-- 6. 子账号进运营后台（BY220 admin.php 子账号也能做代理运营）：
--    阶段 1 已实现按 agent_group.sys_user_id 的 GROUP 范围，但子账号缺少进 /console 的权限，
--    生产上会被 URL 层 403。这里补齐。
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('AGENT_CONSOLE_READ', 'AGENT_PLAYER_MANAGE')
WHERE r.role_code = 'SUB_ACCOUNT'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
