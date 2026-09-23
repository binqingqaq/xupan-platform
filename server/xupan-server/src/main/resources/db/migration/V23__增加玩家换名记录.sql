CREATE TABLE player_name_change_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    old_display_name VARCHAR(128) NOT NULL,
    new_display_name VARCHAR(128) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_player_name_change_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_player_name_change_name CHECK (old_display_name <> new_display_name)
);

CREATE INDEX idx_player_name_change_user_time
    ON player_name_change_record (user_id, changed_at);
