UPDATE chat_robot
   SET display_name = '机器人',
       avatar_key = 'robot-default.png',
       status = 'ENABLED',
       weight = 100,
       delay_seconds = 0,
       updated_at = CURRENT_TIMESTAMP
 WHERE robot_code = 'issue-helper';

UPDATE chat_robot
   SET status = 'DISABLED',
       updated_at = CURRENT_TIMESTAMP
 WHERE robot_code <> 'issue-helper';

UPDATE chat_message m
JOIN chat_robot r ON r.robot_code = 'issue-helper'
   SET m.message_type = CASE WHEN m.message_type IN ('SYSTEM', 'ADMIN') THEN 'ROBOT' ELSE m.message_type END,
       m.sender_type = 'ROBOT',
       m.sender_id = r.id,
       m.sender_name = '机器人',
       m.updated_at = CURRENT_TIMESTAMP
 WHERE m.sender_type IN ('SYSTEM', 'ADMIN', 'ROBOT');

UPDATE chat_outbox o
JOIN chat_message m ON m.id = o.message_id
JOIN chat_robot r ON r.robot_code = 'issue-helper'
   SET o.payload_json = JSON_SET(
           o.payload_json,
           '$.messageType', m.message_type,
           '$.senderType', 'ROBOT',
           '$.senderId', r.id,
           '$.senderName', '机器人'
       ),
       o.updated_at = CURRENT_TIMESTAMP
 WHERE m.sender_type = 'ROBOT';
