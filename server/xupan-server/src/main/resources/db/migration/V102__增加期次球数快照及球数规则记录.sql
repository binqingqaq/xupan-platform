ALTER TABLE game_issue
    ADD COLUMN ball_count TINYINT UNSIGNED NOT NULL DEFAULT 8;

ALTER TABLE game_issue
    ADD CONSTRAINT ck_game_issue_ball_count CHECK (ball_count IN (7, 8));

ALTER TABLE game_definition
    ADD COLUMN next_issue_ball_count TINYINT UNSIGNED NOT NULL DEFAULT 8;

ALTER TABLE game_definition
    ADD CONSTRAINT ck_game_definition_next_issue_ball_count CHECK (next_issue_ball_count IN (7, 8));

CREATE TABLE game_ball_rule_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_code VARCHAR(32) NOT NULL,
    effective_issue_number VARCHAR(64) NOT NULL,
    from_ball_count TINYINT UNSIGNED NOT NULL,
    to_ball_count TINYINT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    operator_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_ball_rule_change_key UNIQUE (idempotency_key),
    CONSTRAINT fk_game_ball_rule_change_game FOREIGN KEY (game_code)
        REFERENCES game_definition (game_code),
    CONSTRAINT fk_game_ball_rule_change_operator FOREIGN KEY (operator_user_id)
        REFERENCES sys_user (id),
    CONSTRAINT ck_game_ball_rule_change_from CHECK (from_ball_count IN (7, 8)),
    CONSTRAINT ck_game_ball_rule_change_to CHECK (to_ball_count IN (7, 8)),
    CONSTRAINT ck_game_ball_rule_change_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 255)
);

CREATE INDEX idx_game_ball_rule_change_effective
    ON game_ball_rule_change (game_code, effective_issue_number, id);
