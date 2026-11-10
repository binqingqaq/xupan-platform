CREATE TABLE betting_play_control_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    game_code VARCHAR(32) NOT NULL,
    issue_number VARCHAR(64) NOT NULL,
    expected_draw_result_version INT UNSIGNED NOT NULL,
    ratio_percent DECIMAL(5,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_digest CHAR(64) NULL,
    result_version INT UNSIGNED NULL,
    eligible_order_count BIGINT UNSIGNED NULL,
    total_stake DECIMAL(18,2) NULL,
    target_center_profit DECIMAL(18,2) NULL,
    numbers_text VARCHAR(32) NULL,
    predicted_center_profit DECIMAL(18,2) NULL,
    operator_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_betting_play_control_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_betting_play_control_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_betting_play_control_status CHECK (status IN ('PROCESSING', 'SUCCEEDED')),
    CONSTRAINT ck_betting_play_control_ratio CHECK (ratio_percent > 0 AND ratio_percent <= 100),
    CONSTRAINT ck_betting_play_control_outcome CHECK (
        (status = 'PROCESSING' AND result_version IS NULL AND eligible_order_count IS NULL
            AND total_stake IS NULL AND target_center_profit IS NULL AND numbers_text IS NULL
            AND predicted_center_profit IS NULL AND input_digest IS NULL)
        OR (status = 'SUCCEEDED' AND result_version IS NOT NULL AND eligible_order_count > 0
            AND total_stake > 0 AND target_center_profit >= 0 AND numbers_text IS NOT NULL
            AND predicted_center_profit >= target_center_profit AND input_digest IS NOT NULL)
    )
);

CREATE INDEX idx_betting_play_control_issue
    ON betting_play_control_operation (game_code, issue_number, id);
