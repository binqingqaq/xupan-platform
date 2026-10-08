-- 机器账号与子账号的「删除」对齐 BY220：BY220 admin.php 删除机器/子账号是物理删除
-- （application/admin/controller/Rb.php 直接 DELETE x_robot 及关联表；Users.php 直接 DELETE x_admin），
-- 删除后同名可立即重建。xupan 之前用软删除，用户名被永久占用，实测重建同名返回 USER_USERNAME_EXISTS。
--
-- 本迁移为物理删除做两件准备：
-- 1) 账本流水在机器/子账号被删后不再挂 agent_id/group_id（外键可空），补充分组编码快照保留可读归属；
--    流水行本身**不删除**，保持「账本不可变」原则。
-- 2) 少数「操作者」引用列原本是 NOT NULL，账号被物理删除后无法保留这些历史行，改为可空
--    （行仍保留，只是操作者置空，审计摘要里本来就有用户名文本）。

ALTER TABLE agent_ledger ADD COLUMN agent_code_snapshot VARCHAR(64) NULL AFTER agent_id;
ALTER TABLE agent_ledger ADD COLUMN group_code_snapshot VARCHAR(64) NULL AFTER group_id;

-- V60 的 ck_agent_ledger_target 要求「agent_id 与 group_id 恰好有一个非空」。
-- 机器/子账号被物理删除后，流水行的归属对象已不存在，必须允许两者都为空
-- （原意是「不能同时挂机器和子账号」，改为「至多挂一个」即可，归属对象由 *_code_snapshot 追溯）。
ALTER TABLE agent_ledger DROP CONSTRAINT ck_agent_ledger_target;
ALTER TABLE agent_ledger ADD CONSTRAINT ck_agent_ledger_target CHECK (
    agent_id IS NULL OR group_id IS NULL);

ALTER TABLE chat_user_mute MODIFY COLUMN operator_user_id BIGINT NULL;
ALTER TABLE player_access_link MODIFY COLUMN created_by BIGINT NULL;
ALTER TABLE game_bet_edit_record MODIFY COLUMN operator_user_id BIGINT NULL;
ALTER TABLE player_admin_notice MODIFY COLUMN sender_user_id BIGINT NULL;
