-- 账本地基（G3）：代理层追加式账本流水。
--
-- 依据：docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md §3
--   原则 2「余额变化必须同时写流水，不允许只改余额」
--   原则 3「账本记录应追加保留，不应直接覆盖或删除历史」
--
-- 现状问题（本次修复）：代理积分只有单个 agent.score / agent_group.score 列，
--   机器编辑、子账号编辑都是整值覆盖，代理上下分是裸 UPDATE，**全程没有流水**，
--   因此第二层（代理 ↔ 上游）结算没有凭据，也无法对账与审计。
--
-- 设计：
--   * 一次余额变动一行，追加保留，不更新、不删除历史
--   * 同时记录 balance_before / balance_after，使账本可独立验算（不依赖当时的余额列）
--   * agent_id 与 group_id 恰好一个非空：机器流水与子账号流水共用一张表，靠 target 区分
--   * idempotency_key 唯一，重复提交写不进第二行
--
-- 合规：只记录虚拟积分，不涉及任何真实资金。

CREATE TABLE agent_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NULL,
    group_id BIGINT NULL,
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
    CONSTRAINT uk_agent_ledger_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_agent_ledger_target CHECK (
        (agent_id IS NULL AND group_id IS NOT NULL) OR (agent_id IS NOT NULL AND group_id IS NULL)),
    CONSTRAINT ck_agent_ledger_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_agent_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_agent_ledger_balance CHECK (balance_before >= 0 AND balance_after >= 0),
    CONSTRAINT ck_agent_ledger_entry_type CHECK (entry_type IN (
        'MACHINE_INIT', 'MACHINE_ADJUST', 'SUB_ACCOUNT_INIT', 'SUB_ACCOUNT_ADJUST',
        'PLAYER_TOP_UP', 'PLAYER_DOWN', 'OPENING_BALANCE')),
    CONSTRAINT fk_agent_ledger_agent FOREIGN KEY (agent_id) REFERENCES agent (id),
    CONSTRAINT fk_agent_ledger_group FOREIGN KEY (group_id) REFERENCES agent_group (id)
);

CREATE INDEX idx_agent_ledger_agent ON agent_ledger (agent_id, id);
CREATE INDEX idx_agent_ledger_group ON agent_ledger (group_id, id);
CREATE INDEX idx_agent_ledger_created ON agent_ledger (created_at, id);

-- 期初余额（OPENING_BALANCE）：账本启用时已有余额的机器与子账号。
-- 如果不回填，这些余额在账本里没有来源，对账时会对不上，所以补一条真实的期初记录。
INSERT INTO agent_ledger
    (agent_id, group_id, entry_type, direction, amount, balance_before, balance_after,
     reason, reference_type, reference_id, idempotency_key)
SELECT id, NULL, 'OPENING_BALANCE', 'IN', score, 0.00, score,
       '账本启用时的期初余额（迁移回填）', 'OPENING', CAST(id AS CHAR),
       CONCAT('LEDGER-OPENING-AGENT-', id)
  FROM agent
 WHERE score > 0;

INSERT INTO agent_ledger
    (agent_id, group_id, entry_type, direction, amount, balance_before, balance_after,
     reason, reference_type, reference_id, idempotency_key)
SELECT NULL, id, 'OPENING_BALANCE', 'IN', score, 0.00, score,
       '账本启用时的期初余额（迁移回填）', 'OPENING', CAST(id AS CHAR),
       CONCAT('LEDGER-OPENING-GROUP-', id)
  FROM agent_group
 WHERE score > 0;
