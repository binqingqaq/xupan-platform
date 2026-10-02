-- 账本地基（G4）：同一上游账号不得被多台机器共用。
--
-- 依据：docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md §3 原则 8
--   「同一上游账号被多个代理共用时必须禁止或明确建立归属隔离方案」
-- 业务依据：《代理报网盘口与结算业务规则说明》§14 —— 共用账号会导致
--   余额混合、订单归属不明、风险共用、退款不清。
--
-- 做法：对 agent.report_username 加唯一约束。
--   MySQL 与 H2 的 UNIQUE 索引都允许多个 NULL，因此"未配置飞单账号"的机器不受影响；
--   软删除的机器会在删除时清空该字段（见 PlatformAdminService），避免占位阻挡复用。

-- 1. 先清理历史重复：同一上游账号只保留 id 最小的一台机器，其余清空并置离线。
UPDATE agent
   SET report_username = NULL, report_password_ciphertext = NULL, report_online = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE report_username IS NOT NULL
   AND id NOT IN (SELECT keep_id FROM (
         SELECT MIN(id) AS keep_id FROM agent WHERE report_username IS NOT NULL GROUP BY report_username
       ) AS keepers);

-- 2. 软删除的机器不应继续占用上游账号（含凭据）
UPDATE agent
   SET report_username = NULL, report_password_ciphertext = NULL, report_online = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE deleted_at IS NOT NULL
   AND (report_username IS NOT NULL OR report_password_ciphertext IS NOT NULL);

-- 3. 唯一约束
CREATE UNIQUE INDEX uk_agent_report_username ON agent (report_username);
