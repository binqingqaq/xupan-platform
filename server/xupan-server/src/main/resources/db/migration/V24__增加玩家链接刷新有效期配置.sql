CREATE TABLE player_link_expiration_config (
    user_id BIGINT PRIMARY KEY,
    days INT NOT NULL DEFAULT 7,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_player_link_expiration_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_player_link_expiration_days CHECK (days BETWEEN 1 AND 3650)
);
