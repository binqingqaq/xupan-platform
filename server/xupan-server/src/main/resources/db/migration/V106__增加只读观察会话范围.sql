ALTER TABLE auth_session
    DROP CHECK ck_auth_session_auth_mode;

ALTER TABLE auth_session
    ADD CONSTRAINT ck_auth_session_auth_mode
        CHECK (auth_mode IN ('PASSWORD', 'PLAYER_LINK', 'BOT_SERVICE', 'DISPLAY_OBSERVER'));

ALTER TABLE auth_session
    DROP CHECK ck_auth_session_scope;

ALTER TABLE auth_session
    ADD CONSTRAINT ck_auth_session_scope
        CHECK (scope IS NULL OR scope IN ('CHAT_ONLY', 'PLAYER_FULL')
            OR (scope LIKE 'DRO:%' AND CHAR_LENGTH(scope) = 20));

ALTER TABLE auth_session
    DROP CHECK ck_auth_session_entry;

ALTER TABLE auth_session
    ADD CONSTRAINT ck_auth_session_entry
        CHECK (entry IN ('PLATFORM', 'CONSOLE', 'DISPLAY_OBSERVER'));

ALTER TABLE auth_ws_ticket
    DROP CHECK ck_auth_ws_ticket_scope;

ALTER TABLE auth_ws_ticket
    ADD CONSTRAINT ck_auth_ws_ticket_scope
        CHECK (scope IS NULL OR scope IN ('CHAT_ONLY', 'PLAYER_FULL')
            OR (scope LIKE 'DRO:%' AND CHAR_LENGTH(scope) = 20));
