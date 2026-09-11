CREATE TABLE chat_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NULL,
    published_at TIMESTAMP NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_outbox_message FOREIGN KEY (message_id) REFERENCES chat_message(id),
    CONSTRAINT uk_chat_outbox_message_event UNIQUE (message_id, event_type),
    CONSTRAINT ck_chat_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_chat_outbox_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_chat_outbox_pending ON chat_outbox (status, next_attempt_at, id);

CREATE TABLE chat_user_mute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP NULL,
    reason VARCHAR(255) NOT NULL,
    operator_user_id BIGINT NOT NULL,
    revoked_at TIMESTAMP NULL,
    revoked_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_mute_room FOREIGN KEY (room_id) REFERENCES chat_room(id),
    CONSTRAINT fk_chat_mute_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_chat_mute_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_chat_mute_revoked_by FOREIGN KEY (revoked_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_chat_mute_active ON chat_user_mute (room_id, user_id, start_at, end_at, revoked_at);

CREATE TABLE chat_read_cursor (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (room_id, user_id),
    CONSTRAINT fk_chat_cursor_room FOREIGN KEY (room_id) REFERENCES chat_room(id),
    CONSTRAINT fk_chat_cursor_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_chat_cursor_sequence CHECK (last_read_sequence >= 0)
);
