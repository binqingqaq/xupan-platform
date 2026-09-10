CREATE TABLE game_issue_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_number VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    message VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_issue_event UNIQUE (issue_number, event_type)
);

CREATE INDEX idx_game_issue_event_issue ON game_issue_event (issue_number, id);
