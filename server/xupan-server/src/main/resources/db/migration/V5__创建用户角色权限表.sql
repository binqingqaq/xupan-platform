CREATE TABLE sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    avatar_key VARCHAR(255) NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP NULL,
    security_version BIGINT NOT NULL DEFAULT 0,
    last_login_at TIMESTAMP NULL,
    last_login_ip VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT ck_sys_user_status CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED', 'DELETED')),
    CONSTRAINT ck_sys_user_failed_login_count CHECK (failed_login_count >= 0)
);

CREATE TABLE sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_code UNIQUE (role_code),
    CONSTRAINT ck_sys_role_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE sys_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permission_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    resource_type VARCHAR(16) NOT NULL DEFAULT 'ACTION',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_permission_code UNIQUE (permission_code),
    CONSTRAINT ck_sys_permission_type CHECK (resource_type IN ('API', 'ACTION', 'MENU')),
    CONSTRAINT ck_sys_permission_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

CREATE TABLE sys_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_sys_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    CONSTRAINT fk_sys_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
);

CREATE INDEX idx_sys_user_status ON sys_user (status, id);
CREATE INDEX idx_sys_user_role_role ON sys_user_role (role_id, user_id);
CREATE INDEX idx_sys_role_permission_permission ON sys_role_permission (permission_id, role_id);

INSERT INTO sys_role (role_code, display_name)
SELECT 'USER', '普通用户'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'USER');

INSERT INTO sys_role (role_code, display_name)
SELECT 'MODERATOR', '聊天室管理员'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'MODERATOR');

INSERT INTO sys_role (role_code, display_name)
SELECT 'OPERATOR', '运营人员'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'OPERATOR');

INSERT INTO sys_role (role_code, display_name)
SELECT 'ADMIN', '系统管理员'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'ADMIN');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CHAT_ROOM_READ', '查看聊天室'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CHAT_ROOM_READ');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CHAT_MESSAGE_SEND', '发送聊天消息'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CHAT_MESSAGE_SEND');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'GAME_CURRENT_READ', '查看当前期'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'GAME_CURRENT_READ');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'GAME_BET_PLACE', '提交投注'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'GAME_BET_PLACE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CHAT_MESSAGE_REVIEW', '审核聊天消息'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CHAT_MESSAGE_REVIEW');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CHAT_MESSAGE_RECALL', '撤回聊天消息'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CHAT_MESSAGE_RECALL');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CHAT_USER_MUTE', '禁言聊天室用户'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CHAT_USER_MUTE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'CHAT_USER_KICK', '移出聊天室用户'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'CHAT_USER_KICK');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'GAME_ODDS_READ', '查看游戏赔率'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'GAME_ODDS_READ');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'GAME_ODDS_WRITE', '修改游戏赔率'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'GAME_ODDS_WRITE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ROBOT_READ', '查看机器人配置'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'ROBOT_READ');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ROBOT_WRITE', '修改机器人配置'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'ROBOT_WRITE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ROBOT_TEMPLATE_WRITE', '修改机器人模板'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'ROBOT_TEMPLATE_WRITE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'USER_MANAGE', '管理用户'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'USER_MANAGE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ROLE_MANAGE', '管理角色'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'ROLE_MANAGE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'PERMISSION_MANAGE', '管理权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'PERMISSION_MANAGE');

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'AUDIT_READ', '查看审计日志'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'AUDIT_READ');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN (
    'CHAT_ROOM_READ', 'CHAT_MESSAGE_SEND', 'GAME_CURRENT_READ', 'GAME_BET_PLACE')
WHERE r.role_code IN ('USER', 'MODERATOR')
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN (
    'CHAT_MESSAGE_REVIEW', 'CHAT_MESSAGE_RECALL', 'CHAT_USER_MUTE', 'CHAT_USER_KICK')
WHERE r.role_code = 'MODERATOR'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN (
    'GAME_CURRENT_READ', 'GAME_ODDS_READ', 'GAME_ODDS_WRITE',
    'ROBOT_READ', 'ROBOT_WRITE', 'ROBOT_TEMPLATE_WRITE')
WHERE r.role_code = 'OPERATOR'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON 1 = 1
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );
