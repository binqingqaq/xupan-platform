ALTER TABLE game_bet DROP CONSTRAINT ck_game_bet_status;
ALTER TABLE game_bet
    ADD CONSTRAINT ck_game_bet_status
    CHECK (settlement_status IN ('PENDING', 'WIN', 'DRAW', 'LOSE', 'CANCELED'));
