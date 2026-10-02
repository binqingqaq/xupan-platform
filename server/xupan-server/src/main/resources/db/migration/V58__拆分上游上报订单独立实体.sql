-- 账本地基（G1）：把"上游上报订单"从 game_bet.report_status 单列拆成独立实体。
--
-- 依据：docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md
--   §3 原则 4「本地玩家注单和上游上报订单是不同实体，不能只靠一个状态字段区分」
--   §3 原则 3「账本记录应追加保留，不应直接覆盖或删除历史」
--   业务定义见 BY220 仓库 docs/代理报网盘口与结算业务规则说明.md 第 12、16.4 节。
--
-- 模型：
--   * 本地玩家注单 = game_bet（第一层，玩家 ↔ 代理）
--   * 上游上报订单 = report_upstream_order（第二层，代理 ↔ 上游；一次上报尝试一行，**追加保留**）
--   * 注单当前的上报状态 = 该注单最新一行上报订单的状态（读时派生），不再用 game_bet 上的单列
--   * 状态机含 待处理/已接受/已拒绝/超时/已撤单/已退款/已结算；本阶段使用前四个，
--     后三个由中心上游结算（阶段 B/C）写入。
--
-- 合规：只做内部持久化，不发起任何外部网络请求。

CREATE TABLE report_upstream_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bet_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    report_type VARCHAR(32) NULL,
    route_code VARCHAR(16) NULL,
    network_code VARCHAR(64) NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    upstream_order_no VARCHAR(64) NULL,
    request_summary VARCHAR(512) NULL,
    response_code VARCHAR(64) NULL,
    response_message VARCHAR(255) NULL,
    settled_amount DECIMAL(18,2) NULL,
    sent_at TIMESTAMP NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_upstream_order_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_report_upstream_order_bet FOREIGN KEY (bet_id) REFERENCES game_bet (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_upstream_order_agent FOREIGN KEY (agent_id) REFERENCES agent (id),
    CONSTRAINT ck_report_upstream_order_status CHECK (status IN
        ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT', 'CANCELLED', 'REFUNDED', 'SETTLED')),
    CONSTRAINT ck_report_upstream_order_attempt CHECK (attempt_no > 0),
    CONSTRAINT ck_report_upstream_order_amount CHECK (settled_amount IS NULL OR settled_amount >= 0)
);

CREATE INDEX idx_report_upstream_order_status ON report_upstream_order (status, created_at, id);
CREATE INDEX idx_report_upstream_order_bet ON report_upstream_order (bet_id, attempt_no);
CREATE INDEX idx_report_upstream_order_agent ON report_upstream_order (agent_id, status, id);

-- 迁移既有单列数据：只有"曾经上报过"的注单才产生上报订单行（UNREPORTED 表示还没报，不建行）。
-- REPORTED → ACCEPTED；FAILED → REJECTED；TIMEOUT → TIMEOUT。
-- 迁移行没有真实发送时间，sent_at/resolved_at 用注单创建时间近似，response_code 标注为迁移来源。
INSERT INTO report_upstream_order
    (bet_id, agent_id, attempt_no, status, report_type, route_code, network_code,
     idempotency_key, response_code, response_message, sent_at, resolved_at, created_at, updated_at)
SELECT b.id, a.agent_id, 1,
       CASE b.report_status
           WHEN 'REPORTED' THEN 'ACCEPTED'
           WHEN 'FAILED' THEN 'REJECTED'
           WHEN 'TIMEOUT' THEN 'TIMEOUT'
           ELSE 'PENDING'
       END,
       ag.report_type, ag.report_route_code, ag.report_network_code,
       CONCAT('REPORT-', b.id, '-1'),
       'MIGRATED', '由 game_bet.report_status 迁移',
       b.created_at, b.created_at, b.created_at, b.created_at
  FROM game_bet b
  JOIN demo_user_account a ON a.id = b.user_id
  JOIN agent ag ON ag.id = a.agent_id
 WHERE b.report_status <> 'UNREPORTED';

-- 摘除单列：上报状态的唯一事实源改为 report_upstream_order。
ALTER TABLE game_bet DROP CONSTRAINT ck_game_bet_report_status;
DROP INDEX idx_game_bet_report_status ON game_bet;
ALTER TABLE game_bet DROP COLUMN report_status;
