ALTER TABLE center_agent
    ADD COLUMN agent_group_id BIGINT NULL,
    ADD COLUMN password_hash VARCHAR(100) NULL,
    ADD COLUMN expires_at TIMESTAMP NULL,
    ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMP NULL;

ALTER TABLE center_agent
    ADD CONSTRAINT fk_center_agent_group FOREIGN KEY (agent_group_id) REFERENCES agent_group (id),
    ADD CONSTRAINT ck_center_agent_failed_login_count CHECK (failed_login_count >= 0);

CREATE UNIQUE INDEX uk_center_agent_group ON center_agent (agent_group_id);
CREATE INDEX idx_center_agent_expiry ON center_agent (status, expires_at, id);

CREATE TABLE center_machine_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    center_agent_id BIGINT NOT NULL,
    machine_agent_id BIGINT NOT NULL,
    authenticated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    revoke_reason VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_center_machine_session_machine UNIQUE (machine_agent_id),
    CONSTRAINT fk_center_machine_session_center FOREIGN KEY (center_agent_id) REFERENCES center_agent (id),
    CONSTRAINT fk_center_machine_session_machine FOREIGN KEY (machine_agent_id) REFERENCES agent (id),
    CONSTRAINT ck_center_machine_session_revocation CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    )
);

CREATE INDEX idx_center_machine_session_account ON center_machine_session
    (center_agent_id, revoked_at, expires_at, machine_agent_id);
