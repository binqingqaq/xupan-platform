CREATE TABLE macau_four_minute_draw (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    issue_number BIGINT NOT NULL,
    scheduled_at TIMESTAMP(6) NOT NULL,
    drawn_at TIMESTAMP(6) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_macau_four_minute_draw_issue UNIQUE (issue_number),
    CONSTRAINT uk_macau_four_minute_draw_schedule UNIQUE (scheduled_at),
    CONSTRAINT ck_macau_four_minute_draw_issue CHECK (issue_number >= 100),
    CONSTRAINT ck_macau_four_minute_draw_status CHECK (status = 'PENDING' OR status = 'DRAWN'),
    CONSTRAINT ck_macau_four_minute_draw_timestamp CHECK (
        (status = 'PENDING' AND drawn_at IS NULL)
        OR (status = 'DRAWN' AND drawn_at IS NOT NULL)
    ),
    INDEX idx_macau_four_minute_draw_due (status, scheduled_at),
    INDEX idx_macau_four_minute_draw_history (status, issue_number)
);

CREATE TABLE macau_four_minute_draw_ball (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    draw_id BIGINT NOT NULL,
    ball_position TINYINT NOT NULL,
    number_value TINYINT NULL,
    CONSTRAINT uk_macau_four_minute_draw_ball_position UNIQUE (draw_id, ball_position),
    CONSTRAINT uk_macau_four_minute_draw_ball_number UNIQUE (draw_id, number_value),
    CONSTRAINT fk_macau_four_minute_draw_ball_draw FOREIGN KEY (draw_id)
        REFERENCES macau_four_minute_draw (id) ON DELETE CASCADE,
    CONSTRAINT ck_macau_four_minute_draw_ball_position CHECK (ball_position BETWEEN 1 AND 7),
    CONSTRAINT ck_macau_four_minute_draw_ball_number CHECK (
        number_value IS NULL OR number_value BETWEEN 1 AND 40
    ),
    INDEX idx_macau_four_minute_draw_ball_draw (draw_id)
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'MACAU_FOUR_MINUTE_DRAW_READ', '查看澳门四分彩开奖'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'MACAU_FOUR_MINUTE_DRAW_READ'
);

INSERT INTO sys_permission (permission_code, display_name)
SELECT 'MACAU_FOUR_MINUTE_PRESET_MANAGE', '编辑澳门四分彩开奖预设'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'MACAU_FOUR_MINUTE_PRESET_MANAGE'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN (
    'MACAU_FOUR_MINUTE_DRAW_READ', 'MACAU_FOUR_MINUTE_PRESET_MANAGE'
)
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
