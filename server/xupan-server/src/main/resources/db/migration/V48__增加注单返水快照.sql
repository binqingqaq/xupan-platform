ALTER TABLE game_bet ADD COLUMN rebate_rate_snapshot DECIMAL(8,4) NOT NULL DEFAULT 0.0000;

ALTER TABLE game_bet ADD COLUMN special_rebate_rate_snapshot DECIMAL(8,4) NOT NULL DEFAULT 0.0000;

UPDATE game_bet b
   SET rebate_rate_snapshot = COALESCE((
        SELECT a.rebate_rate FROM demo_user_account d JOIN agent a ON a.id=d.agent_id WHERE d.id=b.user_id
   ), 0.0000),
       special_rebate_rate_snapshot = COALESCE((
        SELECT a.special_rebate_rate FROM demo_user_account d JOIN agent a ON a.id=d.agent_id WHERE d.id=b.user_id
   ), 0.0000);

ALTER TABLE game_bet ADD CONSTRAINT ck_game_bet_rebate_snapshot CHECK (rebate_rate_snapshot >= 0);

ALTER TABLE game_bet ADD CONSTRAINT ck_game_bet_special_rebate_snapshot CHECK (special_rebate_rate_snapshot >= 0);
