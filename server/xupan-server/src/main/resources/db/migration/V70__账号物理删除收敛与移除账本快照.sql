-- 使用者 2026-10-03 确认「完全对齐 BY220、不要保留」：机器/子账号/全量账号删除全部改为**物理删除**。
-- 因此 V69 引入的「账本归属快照」不再需要（流水行本身会被删除，而不是解除归属后保留）。
--
-- 本迁移做三件事：
-- 1) 清掉 V69 短暂采用「解除归属」时留下的孤儿流水（agent_id/group_id 都为空的行）；
-- 2) 恢复 V60 的原约束：每条代理层账本流水必须挂在机器或子账号上（物理删除会删行，不会再产生孤儿）；
-- 3) 去掉不再使用的两个快照列。
--
-- 说明：V69 里把 4 个「操作者」引用列改成可空仍然保留——审计/登录日志等历史行在账号被物理删除后
-- 需要保留并把操作者置空，这是项目审计基线要求，与业务数据的物理删除不冲突。

DELETE FROM agent_ledger WHERE agent_id IS NULL AND group_id IS NULL;

ALTER TABLE agent_ledger DROP CONSTRAINT ck_agent_ledger_target;
ALTER TABLE agent_ledger ADD CONSTRAINT ck_agent_ledger_target CHECK (
    (agent_id IS NULL AND group_id IS NOT NULL) OR (agent_id IS NOT NULL AND group_id IS NULL));

ALTER TABLE agent_ledger DROP COLUMN agent_code_snapshot;
ALTER TABLE agent_ledger DROP COLUMN group_code_snapshot;
