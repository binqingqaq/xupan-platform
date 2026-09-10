CREATE TABLE auth_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    access_token_hash CHAR(64) NOT NULL,
    access_expires_at TIMESTAMP NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    refresh_expires_at TIMESTAMP NOT NULL,
    device_label VARCHAR(128) NULL,
    ip_digest CHAR(64) NULL,
    user_agent_digest CHAR(64) NULL,
    last_seen_at TIMESTAMP NULL,
    revoked_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_session_id UNIQUE (session_id),
    CONSTRAINT uk_auth_session_access_hash UNIQUE (access_token_hash),
    CONSTRAINT uk_auth_session_refresh_hash UNIQUE (refresh_token_hash),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

CREATE TABLE auth_ws_ticket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_hash CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    room_code VARCHAR(64) NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_ws_ticket_hash UNIQUE (ticket_hash),
    CONSTRAINT fk_auth_ws_ticket_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_auth_ws_ticket_session FOREIGN KEY (session_id) REFERENCES auth_session(session_id)
);

CREATE TABLE sys_login_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username_snapshot VARCHAR(64) NOT NULL,
    user_id BIGINT NULL,
    result VARCHAR(24) NOT NULL,
    failure_code VARCHAR(64) NULL,
    ip_digest CHAR(64) NULL,
    user_agent_digest CHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sys_login_log_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_sys_login_log_result CHECK (result IN ('SUCCESS', 'FAILURE', 'LOGOUT', 'REVOKED'))
);

CREATE TABLE sys_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_user_id BIGINT NULL,
    permission_code VARCHAR(128) NULL,
    http_method VARCHAR(16) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    resource_id VARCHAR(128) NULL,
    result VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    request_summary VARCHAR(2000) NULL,
    ip_digest CHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sys_operation_log_user FOREIGN KEY (operator_user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_sys_operation_log_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_auth_session_user ON auth_session (user_id, revoked_at, refresh_expires_at);
CREATE INDEX idx_auth_session_access_expiry ON auth_session (access_expires_at, revoked_at);
CREATE INDEX idx_auth_ws_ticket_expiry ON auth_ws_ticket (expires_at, used_at);
CREATE INDEX idx_sys_login_log_user ON sys_login_log (user_id, id);
CREATE INDEX idx_sys_login_log_created ON sys_login_log (created_at, id);
CREATE INDEX idx_sys_operation_log_operator ON sys_operation_log (operator_user_id, id);
CREATE INDEX idx_sys_operation_log_created ON sys_operation_log (created_at, id);
