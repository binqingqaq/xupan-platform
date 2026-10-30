CREATE TABLE robot_notice_intent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_code VARCHAR(48) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    game_code VARCHAR(32) NOT NULL DEFAULT 'AU8',
    issue_number VARCHAR(64) NOT NULL,
    visibility VARCHAR(12) NOT NULL,
    agent_id BIGINT NULL,
    content VARCHAR(500) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMP NULL,
    message_id BIGINT NULL,
    last_error VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_robot_notice_intent_key UNIQUE (idempotency_key),
    CONSTRAINT fk_robot_notice_intent_message FOREIGN KEY (message_id)
        REFERENCES chat_message (id) ON DELETE SET NULL,
    CONSTRAINT ck_robot_notice_intent_visibility CHECK (visibility IN ('PUBLIC', 'MACHINE')),
    CONSTRAINT ck_robot_notice_intent_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_robot_notice_intent_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_robot_notice_intent_machine_scope CHECK (
        (visibility = 'PUBLIC' AND agent_id IS NULL)
        OR (visibility = 'MACHINE' AND agent_id IS NOT NULL AND agent_id > 0))
);

CREATE INDEX idx_robot_notice_intent_due
    ON robot_notice_intent (status, next_attempt_at, locked_until, id);
CREATE INDEX idx_robot_notice_intent_source
    ON robot_notice_intent (source_type, source_id, event_code);
CREATE INDEX idx_robot_notice_intent_created
    ON robot_notice_intent (created_at, status, id);
