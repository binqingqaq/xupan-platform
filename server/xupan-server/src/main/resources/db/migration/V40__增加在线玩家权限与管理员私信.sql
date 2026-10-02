CREATE TABLE player_admin_notice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipient_user_id BIGINT NOT NULL,
    sender_user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_player_admin_notice_idempotency UNIQUE (sender_user_id, idempotency_key),
    CONSTRAINT fk_player_admin_notice_recipient FOREIGN KEY (recipient_user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_player_admin_notice_sender FOREIGN KEY (sender_user_id) REFERENCES sys_user(id)
);

CREATE INDEX idx_player_admin_notice_unread
    ON player_admin_notice (recipient_user_id, read_at, id);

CREATE INDEX idx_player_admin_notice_sender
    ON player_admin_notice (sender_user_id, created_at, id);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ONLINE_PLAYER_READ', '查看在线玩家'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'ONLINE_PLAYER_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ONLINE_PLAYER_DISCONNECT', '强制在线玩家下线'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'ONLINE_PLAYER_DISCONNECT'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'ONLINE_PLAYER_MESSAGE', '向在线玩家发送信息'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'ONLINE_PLAYER_MESSAGE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN (
    'ONLINE_PLAYER_READ', 'ONLINE_PLAYER_DISCONNECT', 'ONLINE_PLAYER_MESSAGE'
)
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
