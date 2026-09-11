CREATE TABLE chat_room (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    message_retention_days INT NOT NULL DEFAULT 30,
    next_sequence_no BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chat_room_code UNIQUE (room_code),
    CONSTRAINT ck_chat_room_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_chat_room_retention CHECK (message_retention_days >= 1),
    CONSTRAINT ck_chat_room_sequence CHECK (next_sequence_no >= 0)
);

CREATE TABLE chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    client_message_id VARCHAR(128) NULL,
    idempotency_key VARCHAR(128) NULL,
    issue_number VARCHAR(64) NULL,
    message_type VARCHAR(24) NOT NULL,
    sender_type VARCHAR(16) NOT NULL,
    sender_id BIGINT NULL,
    sender_name VARCHAR(128) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    payload_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_message_room FOREIGN KEY (room_id) REFERENCES chat_room(id),
    CONSTRAINT uk_chat_message_room_sequence UNIQUE (room_id, sequence_no),
    CONSTRAINT uk_chat_message_client_key UNIQUE (room_id, sender_id, client_message_id),
    CONSTRAINT uk_chat_message_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_chat_message_type CHECK (message_type IN ('USER_CHAT', 'USER_BET', 'ROBOT', 'SYSTEM', 'RESULT', 'ADMIN')),
    CONSTRAINT ck_chat_message_sender_type CHECK (sender_type IN ('USER', 'ROBOT', 'SYSTEM', 'ADMIN')),
    CONSTRAINT ck_chat_message_status CHECK (status IN ('ACTIVE', 'RECALLED', 'DELETED')),
    CONSTRAINT ck_chat_message_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_chat_message_content CHECK (CHAR_LENGTH(content) > 0)
);

CREATE INDEX idx_chat_message_room_id ON chat_message (room_id, id);
CREATE INDEX idx_chat_message_room_created ON chat_message (room_id, created_at, id);
CREATE INDEX idx_chat_message_sender ON chat_message (sender_type, sender_id, id);

INSERT INTO chat_room (room_code, display_name, status, message_retention_days, next_sequence_no)
SELECT 'main', '公开大厅', 'OPEN', 30, 0
WHERE NOT EXISTS (SELECT 1 FROM chat_room WHERE room_code = 'main');
