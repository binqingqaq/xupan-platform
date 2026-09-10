ALTER TABLE game_issue ADD COLUMN phase VARCHAR(16) NOT NULL DEFAULT 'BETTING';
ALTER TABLE game_issue ADD COLUMN issue_started_at TIMESTAMP NULL;
ALTER TABLE game_issue ADD COLUMN betting_ends_at TIMESTAMP NULL;
ALTER TABLE game_issue ADD COLUMN draw_ends_at TIMESTAMP NULL;
ALTER TABLE game_issue ADD COLUMN settled_at TIMESTAMP NULL;

UPDATE game_issue
   SET phase = CASE WHEN status = 'CLOSED' THEN 'SETTLED' ELSE 'BETTING' END;

CREATE INDEX idx_game_issue_phase ON game_issue (phase);
