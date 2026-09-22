ALTER TABLE sys_user
    ADD COLUMN auth_mode VARCHAR(24) NOT NULL DEFAULT 'PASSWORD';

ALTER TABLE sys_user
    ADD CONSTRAINT ck_sys_user_auth_mode
        CHECK (auth_mode IN ('PASSWORD', 'PLAYER_LINK', 'BOT_SERVICE'));

ALTER TABLE auth_session
    ADD COLUMN auth_mode VARCHAR(24) NOT NULL DEFAULT 'PASSWORD';

ALTER TABLE auth_session
    ADD COLUMN scope VARCHAR(32) NULL;

ALTER TABLE auth_session
    ADD CONSTRAINT ck_auth_session_auth_mode
        CHECK (auth_mode IN ('PASSWORD', 'PLAYER_LINK', 'BOT_SERVICE'));

ALTER TABLE auth_session
    ADD CONSTRAINT ck_auth_session_scope
        CHECK (scope IS NULL OR scope = 'CHAT_ONLY');

ALTER TABLE auth_ws_ticket
    ADD COLUMN scope VARCHAR(32) NULL;

ALTER TABLE auth_ws_ticket
    ADD CONSTRAINT ck_auth_ws_ticket_scope
        CHECK (scope IS NULL OR scope = 'CHAT_ONLY');

CREATE TABLE player_access_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    last_used_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_player_access_link_hash UNIQUE (token_hash),
    CONSTRAINT fk_player_access_link_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_player_access_link_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT ck_player_access_link_scope CHECK (scope = 'CHAT_ONLY')
);

UPDATE sys_user
   SET auth_mode = 'BOT_SERVICE'
 WHERE id IN (
       SELECT a.sys_user_id
         FROM demo_user_account a
        WHERE a.player_kind = 'BOT'
   );

CREATE INDEX idx_auth_session_scope
    ON auth_session (user_id, auth_mode, scope, revoked_at);
CREATE INDEX idx_auth_ws_ticket_scope
    ON auth_ws_ticket (scope, expires_at, used_at);
CREATE INDEX idx_player_access_link_user
    ON player_access_link (user_id, revoked_at, expires_at);
