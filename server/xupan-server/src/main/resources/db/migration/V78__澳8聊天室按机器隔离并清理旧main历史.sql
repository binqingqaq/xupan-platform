-- Old main-room chat content is intentionally removed for the isolation cutover.
-- Draw issues, bets, wallets, player/agent accounts and agent ledgers are not touched.
UPDATE chat_robot_dispatch
   SET message_id = NULL
 WHERE message_id IN (SELECT id FROM chat_message WHERE room_id =
       (SELECT id FROM chat_room WHERE room_code = 'main'));

UPDATE test_player_action
   SET message_id = NULL
 WHERE message_id IN (SELECT id FROM chat_message WHERE room_id =
       (SELECT id FROM chat_room WHERE room_code = 'main'));

DELETE FROM chat_outbox
 WHERE message_id IN (SELECT id FROM chat_message WHERE room_id =
       (SELECT id FROM chat_room WHERE room_code = 'main'));

DELETE FROM chat_read_cursor
 WHERE room_id = (SELECT id FROM chat_room WHERE room_code = 'main');

DELETE FROM chat_message
 WHERE room_id = (SELECT id FROM chat_room WHERE room_code = 'main');

ALTER TABLE chat_message ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE chat_message ADD COLUMN agent_id BIGINT NULL;

ALTER TABLE chat_message ADD CONSTRAINT ck_chat_message_visibility CHECK (
    (visibility = 'PUBLIC' AND agent_id IS NULL)
    OR (visibility = 'MACHINE' AND agent_id IS NOT NULL)
);

CREATE INDEX idx_chat_message_visibility_sequence
    ON chat_message (room_id, visibility, agent_id, sequence_no);
