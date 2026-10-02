-- BY220 代理赔率/返水/封盘/取消的真实结算映射（第一批）
--
-- 1. 返水口径开关：对应 BY220 x_robot.ls（单边/双边），默认 SINGLE，保证存量机器行为不变。
-- 2. 机器赔率与封盘/取消秒数的取值边界校验。
-- 3. 撤单与未结订单删除按 BY220 物理删除注单行：
--    解除指向 game_bet 的外键后，钱包流水（demo_balance_ledger）与改单记录
--    （game_bet_edit_record）仍保留原注单号，作为退款和操作审计的最终证据。

ALTER TABLE agent ADD COLUMN rebate_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE';

ALTER TABLE agent
    ADD CONSTRAINT ck_agent_rebate_mode CHECK (rebate_mode IN ('SINGLE', 'DOUBLE'));

ALTER TABLE agent
    ADD CONSTRAINT ck_agent_odds_rate CHECK (odds_rate >= 0 AND special_odds_rate >= 0);

ALTER TABLE demo_balance_ledger DROP CONSTRAINT fk_demo_balance_ledger_bet;

ALTER TABLE game_bet_edit_record DROP CONSTRAINT fk_game_bet_edit_bet;
