-- C-2 ②：对账差异的处置动作（此前只有展示，无法处置）。
-- C-4 ③：长期差异的保留与清理策略。
--
-- 背景（docs/plans/2026-10-02-上线可用收口与阶段C至D实施规划.md §5）：
--   * C-2 明确要求"对账差异的**处置动作**（现在只有展示）"；
--   * C-4 明确要求"长期差异的保留与清理策略"。
--   规划同时说明：差异**不自动修复**，先人工确认——因此处置动作是"标记已知/接受/已修复"，
--   而不是让系统去改账。这一点很关键：自动修复差异会在真相未明时改写账本事实。
--
-- 为什么要建表而不是只加一个布尔列到 center_order：
--   差异存在于**两侧的比对结果**里，不是一个订单属性。同一笔注单可能在
--   MISSING_IN_CENTER 与 STATUS_MISMATCH 之间变化，处置必须记录"针对哪一类差异、在什么时点、
--   按什么结论"。也因此唯一键是 (差异类型, 注单号) 而不是只按注单号。
--
-- 保留与清理策略（C-4 ③）：
--   * 处置记录**只追加、不删除**：它是差异被认可的依据，删除等于抹掉判断历史；
--   * 但"仍在生效"的处置需要能过期：`expires_at` 允许人工处置在一段时间后自动失效，
--     让差异重新回到待处置列表——避免"标一次已知就永远不再看"。
--   * 已过期/已撤销的处置记录保留在表内，供审计追溯（原则 3）。
--
-- 合规：只涉及内部对账状态，不涉及任何资金或外部系统。

CREATE TABLE center_reconciliation_disposition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 差异类型，与 CenterReconciliationService.Difference.type 一致
    difference_type VARCHAR(32) NOT NULL,
    -- 注单 id（差异是按注单呈现的）
    bet_id BIGINT NOT NULL,
    -- 处置结论：ACKNOWLEDGED 已知悉 / ACCEPTED 接受差异 / FIXED 已修复 / IGNORED 忽略
    resolution VARCHAR(24) NOT NULL,
    -- 中心代理（可空：某些差异来自本地视角）
    center_agent_id BIGINT NULL,
    -- 处置理由：为什么可以接受这笔差异
    reason VARCHAR(255) NULL,
    -- 生效截止时间；NULL = 永久生效。到期后该差异重新回到待处置列表
    expires_at TIMESTAMP NULL,
    -- 撤销信息：撤销也是一条状态迁移，不物理删除
    revoked_at TIMESTAMP NULL,
    revoked_by BIGINT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    operator_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_center_recon_disposition_key UNIQUE (idempotency_key),
    CONSTRAINT fk_center_recon_disposition_agent FOREIGN KEY (center_agent_id) REFERENCES center_agent (id),
    CONSTRAINT fk_center_recon_disposition_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_center_recon_disposition_revoker FOREIGN KEY (revoked_by) REFERENCES sys_user (id),
    CONSTRAINT ck_center_recon_disposition_type CHECK (difference_type IN
        ('MISSING_IN_CENTER', 'MISSING_IN_LOCAL', 'STATUS_MISMATCH', 'AMOUNT_MISMATCH')),
    CONSTRAINT ck_center_recon_disposition_resolution CHECK (resolution IN
        ('ACKNOWLEDGED', 'ACCEPTED', 'FIXED', 'IGNORED'))
);

-- 查询"某类差异的某笔注单当前是否已被处置"——对账时按 (类型, 注单) 精确匹配。
CREATE INDEX idx_center_recon_disposition_target
    ON center_reconciliation_disposition (difference_type, bet_id, id);
-- 查询"最近处置"与 "即将过期的处置"。
CREATE INDEX idx_center_recon_disposition_created
    ON center_reconciliation_disposition (created_at, id);
CREATE INDEX idx_center_recon_disposition_expires
    ON center_reconciliation_disposition (expires_at, id);
