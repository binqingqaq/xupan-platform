-- 修正 V63 中心订单表的两个真实缺陷（在**本机 MySQL 实测中暴露**，H2 未能发现）。
--
-- 背景：V63 已经在本机 MySQL 应用过，因此**不能改写 V63 本身**（Flyway 校验和会不一致）。
-- 按项目规则"已应用的迁移只能前向修复"，这里用新迁移订正。
--
-- 缺陷 1：`play_text NOT NULL` 导致代理只传玩法代码、不传展示文案时直接 500。
--   中心是"被上报方"，不应因为上游少传一个展示字段就拒收订单；该列改为可空。
--
-- 缺陷 2：`amount > 0` / `odds > 0` 是一刀切约束，但"金额为 0""赔率为 0"**正是被拒绝的原因**，
--   被拒绝的订单又必须落库（代理要据此走失败退单，《代理报网盘口与结算业务规则说明》§12.4），
--   结果就是"想记录一次拒绝，却因为拒绝本身而写入失败"。
--   改为：这两个约束只对**有效订单**（已接受及之后的状态）成立。
--
-- 合规：仅调整约束与可空性，不涉及任何真实资金。

ALTER TABLE center_order MODIFY COLUMN play_text VARCHAR(64) NULL;

ALTER TABLE center_order DROP CONSTRAINT ck_center_order_amount;
ALTER TABLE center_order ADD CONSTRAINT ck_center_order_amount CHECK (
    frozen_amount >= 0
    AND (status IN ('REJECTED', 'TIMEOUT', 'PENDING') OR amount > 0));

ALTER TABLE center_order DROP CONSTRAINT ck_center_order_odds;
ALTER TABLE center_order ADD CONSTRAINT ck_center_order_odds CHECK (
    status IN ('REJECTED', 'TIMEOUT', 'PENDING') OR odds > 0);
