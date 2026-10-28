-- P5：接受中心订单时冻结最大净赔付风险，而不是玩家投注本金。
-- 来源注单的球号和机器赔率快照一并保存，供按单结算时使用。

ALTER TABLE center_order
    ADD COLUMN ball_number TINYINT NULL,
    ADD COLUMN risk_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN risk_policy_version TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN machine_funded_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN machine_odds_rate_snapshot DECIMAL(10,4) NULL,
    ADD CONSTRAINT ck_center_order_ball_number CHECK (ball_number IS NULL OR ball_number BETWEEN 1 AND 8),
    ADD CONSTRAINT ck_center_order_risk_amount CHECK (
        risk_amount >= 0 AND risk_policy_version IN (0, 1)
        AND machine_funded_amount >= 0 AND frozen_amount >= 0
    );

-- 历史订单只补齐已有来源快照；现有冻结账本不重写，避免迁移时暗中改动余额事实。
UPDATE center_order
   SET ball_number = (SELECT b.ball_number FROM game_bet b WHERE b.id = center_order.local_bet_id),
       machine_odds_rate_snapshot = (SELECT b.machine_odds_rate_snapshot
                                       FROM game_bet b WHERE b.id = center_order.local_bet_id)
 WHERE local_bet_id IS NOT NULL;

-- 存量已受理单的风险按订单赔率快照计算；旧冻结额仍保留原值，机器补足只适用于新单。
UPDATE center_order
   SET risk_amount = GREATEST(0.00,
       CASE
           WHEN local_bet_id IS NULL THEN GREATEST(frozen_amount, ROUND(amount * (odds - 1), 2))
           WHEN play_type = 'SPECIAL' THEN ROUND(
               (amount / GREATEST(1, COALESCE((SELECT COUNT(*) FROM game_bet_selection s
                                                WHERE s.bet_id = center_order.local_bet_id), 0))) * odds - amount, 2)
           WHEN play_type = 'CAR' AND machine_odds_rate_snapshot IS NOT NULL THEN
               TRUNCATE(amount * machine_odds_rate_snapshot / 300, 2)
           ELSE ROUND(amount * (odds - 1), 2)
       END)
 WHERE status = 'ACCEPTED';

CREATE INDEX idx_center_order_risk_issue
    ON center_order (center_agent_id, game_code, issue_number, status, risk_amount);

ALTER TABLE agent_ledger DROP CONSTRAINT ck_agent_ledger_entry_type;
ALTER TABLE agent_ledger ADD CONSTRAINT ck_agent_ledger_entry_type CHECK (entry_type IN (
    'MACHINE_INIT', 'MACHINE_ADJUST', 'SUB_ACCOUNT_INIT', 'SUB_ACCOUNT_ADJUST',
    'PLAYER_TOP_UP', 'PLAYER_DOWN', 'OPENING_BALANCE',
    'MACHINE_ALLOCATE', 'MACHINE_RETURN', 'PLAYER_DELETE_REFUND',
    'CENTER_RISK_RESERVE', 'CENTER_RISK_RELEASE'));
