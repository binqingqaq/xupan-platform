ALTER TABLE chat_robot_draw_component DROP CONSTRAINT ck_chat_robot_draw_component;
ALTER TABLE chat_robot_draw_component ADD CONSTRAINT ck_chat_robot_draw_component CHECK (
    component IN ('DRAW_SUMMARY', 'DRAW_HISTORY', 'WINNER_LIST', 'TOTAL_BOARD')
);

ALTER TABLE chat_robot_draw_component DROP CONSTRAINT ck_chat_robot_draw_order;
ALTER TABLE chat_robot_draw_component ADD CONSTRAINT ck_chat_robot_draw_order CHECK (display_order BETWEEN 1 AND 4);

INSERT INTO chat_robot_draw_component
    (robot_id, component, enabled, display_order)
SELECT r.id, 'TOTAL_BOARD', TRUE, 4
  FROM chat_robot r
 WHERE NOT EXISTS (
       SELECT 1 FROM chat_robot_draw_component c
        WHERE c.robot_id = r.id AND c.component = 'TOTAL_BOARD'
   );
