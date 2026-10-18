-- New bots should begin in automatic mode. Version zero identifies rows that
-- have never had their behavior settings changed through the management API.
ALTER TABLE test_player_behavior
    ALTER COLUMN enabled SET DEFAULT TRUE;

ALTER TABLE test_player_behavior
    ALTER COLUMN run_mode SET DEFAULT 'AUTOMATIC';

UPDATE test_player_behavior b
   SET enabled = TRUE,
       run_mode = 'AUTOMATIC',
       updated_at = CURRENT_TIMESTAMP(6)
 WHERE b.enabled = FALSE
   AND b.run_mode = 'MANUAL'
   AND b.version = 0
   AND EXISTS (
       SELECT 1
         FROM demo_user_account a
        WHERE a.id = b.account_id
          AND a.player_kind = 'BOT'
   );
