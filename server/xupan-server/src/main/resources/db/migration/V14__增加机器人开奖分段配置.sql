CREATE TABLE chat_robot_draw_component (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    robot_id BIGINT NOT NULL,
    component VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_order TINYINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_robot_draw_component_robot FOREIGN KEY (robot_id)
        REFERENCES chat_robot(id),
    CONSTRAINT uk_chat_robot_draw_component UNIQUE (robot_id, component),
    CONSTRAINT uk_chat_robot_draw_order UNIQUE (robot_id, display_order),
    CONSTRAINT ck_chat_robot_draw_component CHECK (
        component IN ('DRAW_SUMMARY', 'DRAW_HISTORY', 'WINNER_LIST')
    ),
    CONSTRAINT ck_chat_robot_draw_order CHECK (display_order BETWEEN 1 AND 3)
);

CREATE INDEX idx_chat_robot_draw_component_order
    ON chat_robot_draw_component (robot_id, enabled, display_order);

INSERT INTO chat_robot_draw_component
    (robot_id, component, enabled, display_order)
SELECT r.id, 'DRAW_SUMMARY', TRUE, 1
  FROM chat_robot r
 WHERE NOT EXISTS (
       SELECT 1 FROM chat_robot_draw_component c
        WHERE c.robot_id = r.id AND c.component = 'DRAW_SUMMARY'
   );

INSERT INTO chat_robot_draw_component
    (robot_id, component, enabled, display_order)
SELECT r.id, 'DRAW_HISTORY', TRUE, 2
  FROM chat_robot r
 WHERE NOT EXISTS (
       SELECT 1 FROM chat_robot_draw_component c
        WHERE c.robot_id = r.id AND c.component = 'DRAW_HISTORY'
   );

INSERT INTO chat_robot_draw_component
    (robot_id, component, enabled, display_order)
SELECT r.id, 'WINNER_LIST', TRUE, 3
  FROM chat_robot r
 WHERE NOT EXISTS (
       SELECT 1 FROM chat_robot_draw_component c
        WHERE c.robot_id = r.id AND c.component = 'WINNER_LIST'
   );
