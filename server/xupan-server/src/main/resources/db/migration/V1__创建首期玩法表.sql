CREATE TABLE game_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_number VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    number_1 TINYINT,
    number_2 TINYINT,
    number_3 TINYINT,
    number_4 TINYINT,
    number_5 TINYINT,
    number_6 TINYINT,
    number_7 TINYINT,
    number_8 TINYINT,
    opened_at TIMESTAMP,
    closed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_issue_number UNIQUE (issue_number),
    CONSTRAINT ck_game_issue_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE TABLE game_odds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    play_type VARCHAR(32) NOT NULL,
    odds DECIMAL(10, 3) NOT NULL,
    draw_policy VARCHAR(16) NOT NULL DEFAULT 'REFUND',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_odds_play_type UNIQUE (play_type),
    CONSTRAINT ck_game_odds_value CHECK (odds >= 1.000),
    CONSTRAINT ck_game_odds_draw_policy CHECK (draw_policy IN ('REFUND', 'LOSE'))
);

CREATE TABLE game_bet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bet_code VARCHAR(64) NOT NULL,
    issue_number VARCHAR(64) NOT NULL,
    ball_number TINYINT NOT NULL,
    play_type VARCHAR(32) NOT NULL,
    parameters_text VARCHAR(255) NOT NULL,
    stake DECIMAL(18, 2) NOT NULL,
    odds_snapshot DECIMAL(10, 3) NOT NULL,
    settlement_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    net_profit DECIMAL(18, 2),
    explanation VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMP,
    CONSTRAINT uk_game_bet_code UNIQUE (bet_code),
    CONSTRAINT ck_game_bet_ball_number CHECK (ball_number BETWEEN 1 AND 8),
    CONSTRAINT ck_game_bet_stake CHECK (stake > 0),
    CONSTRAINT ck_game_bet_odds CHECK (odds_snapshot >= 1.000),
    CONSTRAINT ck_game_bet_status CHECK (settlement_status IN ('PENDING', 'WIN', 'DRAW', 'LOSE'))
);

CREATE INDEX idx_game_bet_issue ON game_bet (issue_number);
