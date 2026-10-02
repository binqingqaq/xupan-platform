-- 阶段 B 第二步：中心订单表与状态机。
--
-- 依据：《代理报网盘口与结算业务规则说明》§12（上报与失败退单）、§16.4（订单状态）、
--   §23.10（闭环判据）、§14（订单必须归属到具体代理身份）。
-- 关联：docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md §5 阶段 B。
--
-- 定位：
--   * 本地侧 `report_upstream_order` 记录"我这台机器上报了哪些注单、每次尝试的结果"（第一层视角）
--   * 中心侧 `center_order` 记录"我作为上游盘口接收到了哪些订单、如何结算"（第二层视角）
--   两者靠 idempotency_key（代理订单号）一一对应，这样才能对账。
--
-- 状态机（§16.4）：
--   待处理 PENDING → 已接受 ACCEPTED / 已拒绝 REJECTED / 超时 TIMEOUT
--   ACCEPTED → 已结算 SETTLED（开奖后第二层结算）
--   ACCEPTED → 已撤单 CANCELLED（比赛取消/流局）
--   任何已接受但需要退单的 → 已退款 REFUNDED
--
-- 合规：金额全部是虚拟积分与内部风险敞口。

CREATE TABLE center_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    center_agent_id BIGINT NOT NULL,
    -- 代理侧订单号（幂等键）：同一个代理下不允许重复，这是"重复上报只接一次"的根据
    agent_order_no VARCHAR(80) NOT NULL,
    -- 本地注单号与本地机器（便于中心回查来源；不设外键，因为中心不应依赖本地表结构）
    local_bet_id BIGINT NULL,
    local_agent_id BIGINT NULL,
    game_code VARCHAR(32) NOT NULL,
    issue_number VARCHAR(32) NOT NULL,
    play_type VARCHAR(32) NOT NULL,
    play_text VARCHAR(64) NOT NULL,
    odds DECIMAL(12,4) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    -- 受理时的校验快照：便于事后复核"当时为什么接受/拒绝"
    reject_code VARCHAR(64) NULL,
    reject_reason VARCHAR(255) NULL,
    -- 冻结时占用的代理可用额度；接受后转入上游未结算
    frozen_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    -- 第二层结算结果：中心赔给代理（赢）或中心从代理收（输）
    settle_result VARCHAR(16) NULL,
    settle_amount DECIMAL(18,2) NULL,
    -- 期号开奖号码快照（对账时不必回查开奖表）
    draw_numbers VARCHAR(64) NULL,
    accepted_at TIMESTAMP NULL,
    settled_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_center_order_agent_order UNIQUE (center_agent_id, agent_order_no),
    CONSTRAINT fk_center_order_agent FOREIGN KEY (center_agent_id) REFERENCES center_agent (id),
    CONSTRAINT ck_center_order_status CHECK (status IN (
        'PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT', 'SETTLED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_center_order_settle_result CHECK (
        settle_result IS NULL OR settle_result IN ('AGENT_WIN', 'AGENT_LOSE', 'VOID')),
    CONSTRAINT ck_center_order_amount CHECK (amount > 0 AND frozen_amount >= 0),
    CONSTRAINT ck_center_order_odds CHECK (odds > 0)
);

CREATE INDEX idx_center_order_agent ON center_order (center_agent_id, status, id);
CREATE INDEX idx_center_order_status ON center_order (status, id);
CREATE INDEX idx_center_order_issue ON center_order (game_code, issue_number, id);
CREATE INDEX idx_center_order_local_bet ON center_order (local_bet_id);
