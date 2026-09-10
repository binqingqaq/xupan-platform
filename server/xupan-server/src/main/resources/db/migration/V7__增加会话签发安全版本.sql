ALTER TABLE auth_session
    ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE auth_session
    ADD CONSTRAINT ck_auth_session_security_version CHECK (security_version >= 0);

CREATE INDEX idx_auth_session_security_version
    ON auth_session (user_id, security_version, revoked_at);
