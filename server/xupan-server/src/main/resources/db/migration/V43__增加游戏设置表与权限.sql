CREATE TABLE game_definition (
    id BIGINT PRIMARY KEY,
    game_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    ball_indexes VARCHAR(255) NOT NULL,
    draw_source_url VARCHAR(512) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    algorithm VARCHAR(16) NOT NULL DEFAULT 'SUM',
    play_prefix VARCHAR(32) NULL,
    switch_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    special_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    special_model VARCHAR(16) NOT NULL DEFAULT 'MODEL_ONE',
    keyboard_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_game_definition_code UNIQUE (game_code),
    CONSTRAINT fk_game_definition_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_game_definition_updater FOREIGN KEY (updated_by) REFERENCES sys_user(id),
    CONSTRAINT ck_game_definition_algorithm CHECK (algorithm IN ('SUM', 'CONCAT')),
    CONSTRAINT ck_game_definition_special_model CHECK (special_model IN ('MODEL_ONE', 'MODEL_TWO')),
    CONSTRAINT ck_game_definition_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

INSERT INTO game_definition
    (id, game_code, display_name, ball_indexes, algorithm, switch_enabled,
     special_enabled, special_model, keyboard_enabled, status)
VALUES (1, 'AU8', '澳8番摊', '1,2,3,4,5,6,7,8', 'SUM', FALSE,
        TRUE, 'MODEL_ONE', TRUE, 'ACTIVE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'GAME_SETTINGS_READ', '查看游戏设置'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'GAME_SETTINGS_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'GAME_SETTINGS_WRITE', '修改游戏设置'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'GAME_SETTINGS_WRITE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('GAME_SETTINGS_READ', 'GAME_SETTINGS_WRITE')
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
