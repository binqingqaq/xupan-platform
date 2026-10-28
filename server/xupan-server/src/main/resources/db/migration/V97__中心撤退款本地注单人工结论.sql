-- 代理对中心已撤单/退款后的玩家本地注单作出一次性人工结论。
-- 处理决定与中心结果分开保存；账号删除清除上报尝试时保留本地处理事实。
CREATE TABLE report_local_disposition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bet_id BIGINT NOT NULL,
    report_attempt_id BIGINT NULL,
    agent_id_snapshot BIGINT NOT NULL,
    center_agent_id_snapshot BIGINT NULL,
    center_outcome VARCHAR(16) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operator_user_id_snapshot BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_local_disposition_bet UNIQUE (bet_id),
    CONSTRAINT uk_report_local_disposition_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_report_local_disposition_bet FOREIGN KEY (bet_id)
        REFERENCES game_bet (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_local_disposition_attempt FOREIGN KEY (report_attempt_id)
        REFERENCES report_upstream_order (id) ON DELETE SET NULL,
    CONSTRAINT ck_report_local_disposition_outcome CHECK (center_outcome IN ('CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_report_local_disposition_decision CHECK (decision IN ('KEEP_LOCAL', 'REFUND_CANCEL_LOCAL'))
);

CREATE INDEX idx_report_local_disposition_agent_created
    ON report_local_disposition (agent_id_snapshot, created_at, id);
