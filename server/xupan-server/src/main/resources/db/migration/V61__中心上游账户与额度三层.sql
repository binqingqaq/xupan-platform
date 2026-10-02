-- 阶段 B 第一步：中心上游账户与额度三层（补账本原则 1）。
--
-- 依据：
--   * docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md §3 原则 1
--     「玩家余额、代理可用额度、代理冻结额度、上游未结算金额是不同事实，不能共用一个余额字段」
--   * docs/需求基线/... §6（G2 与中心账户合并到阶段 B）
--   * 《代理报网盘口与结算业务规则说明》§10（两层账）、§14（盘口不是代理身份，代理身份靠 Token/上游账号）
--     §17（最小/可运营/最终形态）、§23.9（报网类型只有一个「中心上游」，用不同 Token 区分代理）
--
-- 术语对齐（重要）：
--   * center_agent = 中心侧的一个**代理身份**（需求文档 §14）。它有独立的 upstream_account（上游账号）
--     与 token，绝不允许两个代理共用同一上游账号（原则 8 在中心侧的落地）。
--   * 本地 `agent` 表是"机器"（BY220 robot.php 的机器账号），与 center_agent 是**多对一**：
--     一台本地机器通过自己的报网配置指向一个中心代理身份。
--     报网类型 `report_type = 'CENTER'` 表示"本机器把订单报给中心上游"。
--
-- 额度三层（原则 1 的落地，三者是不同事实，分列存放）：
--   * available_balance   可用额度：代理现在就能动用的积分
--   * frozen_balance      冻结额度：已下单但结果未定的敞口（订单被中心接受后占用）
--   * upstream_unsettled  上游未结算：已开奖等待第二层结算的金额
--
-- 合规：全部为虚拟积分与内部风险敞口，不涉及任何真实资金链路。

CREATE TABLE center_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_code VARCHAR(64) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    -- 中心侧代理身份凭证：Token 只保存哈希，原文只在创建时返回一次（原则 9）
    upstream_account VARCHAR(128) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_prefix VARCHAR(12) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- 信用额度：中心允许该代理的最大敞口（虚拟积分）
    credit_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    -- 额度三层，默认 0，只能由账本服务修改
    available_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    upstream_unsettled DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    created_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 上游账号唯一：禁止多代理共用（否则余额混合、订单归属不明、风险共用、退款不清）
    CONSTRAINT uk_center_agent_upstream_account UNIQUE (upstream_account),
    CONSTRAINT uk_center_agent_code UNIQUE (agent_code),
    CONSTRAINT uk_center_agent_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_center_agent_creator FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT ck_center_agent_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_center_agent_amounts CHECK (
        credit_limit >= 0 AND available_balance >= 0 AND frozen_balance >= 0 AND upstream_unsettled >= 0)
);

CREATE INDEX idx_center_agent_status ON center_agent (status, id);

-- 中心侧追加式账本（与 agent_ledger 同构：一次变动一行、只追加、前后余额可验算、幂等键唯一）。
-- 原则 2、3 在中心侧同样适用；这是第二层结算的唯一凭据来源。
CREATE TABLE center_agent_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    center_agent_id BIGINT NOT NULL,
    -- 三层里被动的那一层
    bucket VARCHAR(24) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    balance_before DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    reason VARCHAR(255) NULL,
    reference_type VARCHAR(32) NULL,
    reference_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    operator_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_center_agent_ledger_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_center_agent_ledger_agent FOREIGN KEY (center_agent_id) REFERENCES center_agent (id),
    CONSTRAINT ck_center_agent_ledger_bucket CHECK (
        bucket IN ('AVAILABLE', 'FROZEN', 'UPSTREAM_UNSETTLED', 'CREDIT_LIMIT')),
    CONSTRAINT ck_center_agent_ledger_entry CHECK (entry_type IN (
        'OPENING_BALANCE', 'CREDIT_GRANT', 'CREDIT_ADJUST', 'BET_FREEZE', 'BET_UNFREEZE',
        'UPSTREAM_MOVE', 'SETTLE_WIN', 'SETTLE_LOSE', 'SETTLE_REFUND')),
    CONSTRAINT ck_center_agent_ledger_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_center_agent_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_center_agent_ledger_balance CHECK (balance_before >= 0 AND balance_after >= 0)
);

CREATE INDEX idx_center_agent_ledger_agent ON center_agent_ledger (center_agent_id, id);
CREATE INDEX idx_center_agent_ledger_created ON center_agent_ledger (created_at, id);

-- 本机机器 → 中心代理身份的绑定。
-- 报网类型 CENTER 时使用；一台机器同时只能绑定一个中心代理身份。
ALTER TABLE agent ADD COLUMN center_agent_id BIGINT NULL;
ALTER TABLE agent ADD CONSTRAINT fk_agent_center_agent FOREIGN KEY (center_agent_id) REFERENCES center_agent (id);
ALTER TABLE agent ADD CONSTRAINT ck_agent_center_binding CHECK (
    (report_type IS NULL OR report_type <> 'CENTER') OR center_agent_id IS NOT NULL);
CREATE INDEX idx_agent_center_agent ON agent (center_agent_id);
