ALTER TABLE game_issue ADD COLUMN game_code VARCHAR(32) NOT NULL DEFAULT 'AU8';
ALTER TABLE game_issue ADD CONSTRAINT uk_game_issue_game_number UNIQUE (game_code, issue_number);
ALTER TABLE game_issue ADD CONSTRAINT fk_game_issue_game FOREIGN KEY (game_code) REFERENCES game_definition(game_code);
CREATE INDEX idx_game_issue_game_phase ON game_issue (game_code, phase, id);

ALTER TABLE game_issue_event ADD COLUMN game_code VARCHAR(32) NOT NULL DEFAULT 'AU8';
ALTER TABLE game_issue_event ADD CONSTRAINT uk_game_issue_event_game UNIQUE (game_code, issue_number, event_type);
ALTER TABLE game_issue_event ADD CONSTRAINT fk_game_issue_event_game FOREIGN KEY (game_code) REFERENCES game_definition(game_code);
CREATE INDEX idx_game_issue_event_game_issue ON game_issue_event (game_code, issue_number, id);

ALTER TABLE game_bet ADD COLUMN game_code VARCHAR(32) NOT NULL DEFAULT 'AU8';
ALTER TABLE game_bet ADD CONSTRAINT fk_game_bet_game FOREIGN KEY (game_code) REFERENCES game_definition(game_code);
CREATE INDEX idx_game_bet_game_issue_status ON game_bet (game_code, issue_number, settlement_status, id);