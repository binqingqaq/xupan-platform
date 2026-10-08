-- P1-C2 / C-3：中心侧订单处置记录（超时确认、失败确认、退款、撤单）。
--
-- 背景（docs/plans/2026-10-02-上线可用收口与阶段C至D实施规划.md §5 C-2、C-3）：
--   * 阶段 B 的 center_order 状态机里 REFUNDED / CANCELLED 已声明，但**没有任何写入方**；
--   * "超时 ≠ 失败"（需求 §12.3）要求超时单必须能由人工查证后明确落到终态，
--     而阶段 B 只有自动流转，没有处置入口；
--   * 对账差异只能看见、不能处置。
--
-- 本迁移新增 center_order_disposition：**追加式**的处置流水。
--   * 为什么追加而不是只改 center_order.status：
--     处置是有人为判断的操作，必须留下"谁、何时、按什么理由、把哪一笔从什么状态改到什么状态"。
--     只改状态列会丢掉这条审计链，事后无法解释一笔退款是谁批的。
--   * idempotency_key 唯一：重复提交同一处置只生效一次（原则 6）。
--
-- 合规：全部为虚拟积分与内部风险敞口，不涉及任何真实资金链路。

CREATE TABLE center_order_disposition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    center_order_id BIGINT NOT NULL,
    -- 动作码：CONFIRM_ACCEPTED / CONFIRM_FAILED / REFUND / CANCEL
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(16) NOT NULL,
    to_status VARCHAR(16) NOT NULL,
    -- 本次处置涉及的金额（退款/撤单为退回冻结额，确认受理为 0）
    amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    reason VARCHAR(255) NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    operator_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_center_order_disposition_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_center_order_disposition_order FOREIGN KEY (center_order_id)
        REFERENCES center_order (id),
    CONSTRAINT fk_center_order_disposition_operator FOREIGN KEY (operator_user_id)
        REFERENCES sys_user (id),
    CONSTRAINT ck_center_order_disposition_action CHECK (action IN
        ('CONFIRM_ACCEPTED', 'CONFIRM_FAILED', 'REFUND', 'CANCEL')),
    CONSTRAINT ck_center_order_disposition_amount CHECK (amount >= 0)
);

-- 按订单看处置历史（详情页与审计回查）。
CREATE INDEX idx_center_order_disposition_order ON center_order_disposition (center_order_id, id);
-- 按时间聚合（管理端"最近处置"）。
CREATE INDEX idx_center_order_disposition_created ON center_order_disposition (created_at, id);
