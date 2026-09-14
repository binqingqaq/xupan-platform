CREATE TABLE chat_robot_dispatch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_event_id BIGINT NOT NULL,
    robot_id BIGINT NOT NULL,
    issue_number VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NULL,
    locked_until TIMESTAMP NULL,
    message_id BIGINT NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    CONSTRAINT fk_chat_robot_dispatch_event FOREIGN KEY (game_event_id)
        REFERENCES game_issue_event(id),
    CONSTRAINT fk_chat_robot_dispatch_robot FOREIGN KEY (robot_id)
        REFERENCES chat_robot(id),
    CONSTRAINT fk_chat_robot_dispatch_message FOREIGN KEY (message_id)
        REFERENCES chat_message(id),
    CONSTRAINT uk_chat_robot_dispatch_event UNIQUE (game_event_id),
    CONSTRAINT uk_chat_robot_dispatch_message UNIQUE (message_id),
    CONSTRAINT ck_chat_robot_dispatch_event CHECK (
        event_type IN ('ISSUE_STARTED', 'BETTING_WARNING', 'BETTING_CLOSED', 'DRAW_RESULT')
    ),
    CONSTRAINT ck_chat_robot_dispatch_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'FAILED', 'PUBLISHED', 'SKIPPED')
    ),
    CONSTRAINT ck_chat_robot_dispatch_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_chat_robot_dispatch_due
    ON chat_robot_dispatch (status, next_attempt_at, locked_until, id);

CREATE INDEX idx_chat_robot_dispatch_issue
    ON chat_robot_dispatch (issue_number, event_type, id);
