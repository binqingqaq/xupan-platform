CREATE TABLE chat_robot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    robot_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(32) NOT NULL,
    avatar_key VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    weight INT NOT NULL DEFAULT 100,
    delay_seconds INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chat_robot_code UNIQUE (robot_code),
    CONSTRAINT ck_chat_robot_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_chat_robot_weight CHECK (weight BETWEEN 1 AND 100),
    CONSTRAINT ck_chat_robot_delay CHECK (delay_seconds BETWEEN 0 AND 300)
);

CREATE TABLE chat_robot_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    robot_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    template_code VARCHAR(32) NOT NULL,
    template_text VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_robot_template_robot FOREIGN KEY (robot_id) REFERENCES chat_robot(id),
    CONSTRAINT uk_chat_robot_template_version UNIQUE (robot_id, event_type, template_code, version),
    CONSTRAINT ck_chat_robot_template_event CHECK (
        event_type IN ('ISSUE_STARTED', 'BETTING_WARNING', 'BETTING_CLOSED', 'DRAW_RESULT')
    ),
    CONSTRAINT ck_chat_robot_template_version CHECK (version >= 1),
    CONSTRAINT ck_chat_robot_template_text CHECK (CHAR_LENGTH(template_text) > 0)
);

CREATE INDEX idx_chat_robot_template_active
    ON chat_robot_template (robot_id, event_type, enabled, version);

INSERT INTO chat_robot
    (robot_code, display_name, avatar_key, status, weight, delay_seconds)
SELECT 'issue-helper', '开奖助手', 'robot-default', 'ENABLED', 100, 0
 WHERE NOT EXISTS (
       SELECT 1 FROM chat_robot WHERE robot_code = 'issue-helper'
 );

INSERT INTO chat_robot_template
    (robot_id, event_type, template_code, template_text, enabled, version)
SELECT r.id, 'ISSUE_STARTED', 'default', '{{issueNumber}}期开始，欢迎进入聊天室。', TRUE, 1
  FROM chat_robot r
 WHERE r.robot_code = 'issue-helper'
   AND NOT EXISTS (
       SELECT 1 FROM chat_robot_template t
        WHERE t.robot_id = r.id
          AND t.event_type = 'ISSUE_STARTED'
          AND t.template_code = 'default'
          AND t.version = 1
   );

INSERT INTO chat_robot_template
    (robot_id, event_type, template_code, template_text, enabled, version)
SELECT r.id, 'BETTING_WARNING', 'default', '{{eventMessage}}', TRUE, 1
  FROM chat_robot r
 WHERE r.robot_code = 'issue-helper'
   AND NOT EXISTS (
       SELECT 1 FROM chat_robot_template t
        WHERE t.robot_id = r.id
          AND t.event_type = 'BETTING_WARNING'
          AND t.template_code = 'default'
          AND t.version = 1
   );

INSERT INTO chat_robot_template
    (robot_id, event_type, template_code, template_text, enabled, version)
SELECT r.id, 'BETTING_CLOSED', 'default', '{{eventMessage}}', TRUE, 1
  FROM chat_robot r
 WHERE r.robot_code = 'issue-helper'
   AND NOT EXISTS (
       SELECT 1 FROM chat_robot_template t
        WHERE t.robot_id = r.id
          AND t.event_type = 'BETTING_CLOSED'
          AND t.template_code = 'default'
          AND t.version = 1
   );

INSERT INTO chat_robot_template
    (robot_id, event_type, template_code, template_text, enabled, version)
SELECT r.id, 'DRAW_RESULT', 'default', '{{eventMessage}}', TRUE, 1
  FROM chat_robot r
 WHERE r.robot_code = 'issue-helper'
   AND NOT EXISTS (
       SELECT 1 FROM chat_robot_template t
        WHERE t.robot_id = r.id
          AND t.event_type = 'DRAW_RESULT'
          AND t.template_code = 'default'
          AND t.version = 1
   );
