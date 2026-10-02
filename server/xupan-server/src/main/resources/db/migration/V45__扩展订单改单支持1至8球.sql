ALTER TABLE game_bet_edit_record
    ADD COLUMN old_ball_number TINYINT NOT NULL DEFAULT 1;

ALTER TABLE game_bet_edit_record
    ADD COLUMN new_ball_number TINYINT NOT NULL DEFAULT 1;

ALTER TABLE game_bet_edit_record
    ADD CONSTRAINT ck_game_bet_edit_old_ball CHECK (old_ball_number BETWEEN 1 AND 8);

ALTER TABLE game_bet_edit_record
    ADD CONSTRAINT ck_game_bet_edit_new_ball CHECK (new_ball_number BETWEEN 1 AND 8);
