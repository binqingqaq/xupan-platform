-- P2：明确中心账号归属的报网记录，支持账号业务数据物理删除。
-- 玩家原始注单仍保留并继续本地结算；删除最后一组中心报网尝试后标记该注单不再自动报送。

ALTER TABLE report_upstream_order
    ADD COLUMN center_agent_id BIGINT NULL;

CREATE INDEX idx_report_upstream_order_center_agent
    ON report_upstream_order (center_agent_id, bet_id, id);

ALTER TABLE report_upstream_order
    ADD CONSTRAINT fk_report_upstream_order_center_agent
        FOREIGN KEY (center_agent_id) REFERENCES center_agent (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_report_upstream_order_center_type
        CHECK (center_agent_id IS NULL OR report_type = 'CENTER');

-- 只回填可由已有中心订单直接证明归属的尝试；同一注单出现多个中心账号时留空，避免猜测。
UPDATE report_upstream_order r
JOIN (
    SELECT local_bet_id, local_agent_id, MIN(center_agent_id) AS center_agent_id
      FROM center_order
     WHERE local_bet_id IS NOT NULL AND local_agent_id IS NOT NULL
     GROUP BY local_bet_id, local_agent_id
    HAVING COUNT(DISTINCT center_agent_id) = 1
) o ON o.local_bet_id = r.bet_id AND o.local_agent_id = r.agent_id
   SET r.center_agent_id = o.center_agent_id
 WHERE r.report_type = 'CENTER';

ALTER TABLE game_bet
    ADD COLUMN center_report_deleted_at TIMESTAMP NULL;
