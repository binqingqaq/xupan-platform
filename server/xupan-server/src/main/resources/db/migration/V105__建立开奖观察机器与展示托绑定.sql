CREATE TABLE display_observer_binding (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    agent_id BIGINT NULL,
    viewer_user_id BIGINT NULL,
    agent_id_snapshot BIGINT NOT NULL,
    viewer_user_id_snapshot BIGINT NOT NULL,
    group_id_snapshot BIGINT NOT NULL,
    machine_code_snapshot VARCHAR(64) NOT NULL,
    machine_username_snapshot VARCHAR(64) NOT NULL,
    machine_display_name_snapshot VARCHAR(128) NOT NULL,
    group_display_name_snapshot VARCHAR(128) NOT NULL,
    viewer_member_code_snapshot VARCHAR(64) NOT NULL,
    viewer_display_name_snapshot VARCHAR(128) NOT NULL,
    public_id CHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_by BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by BIGINT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_display_observer_singleton CHECK (id = 1),
    CONSTRAINT ck_display_observer_version CHECK (version >= 1),
    CONSTRAINT uk_display_observer_public_id UNIQUE (public_id),
    CONSTRAINT fk_display_observer_agent FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE SET NULL,
    CONSTRAINT fk_display_observer_viewer FOREIGN KEY (viewer_user_id) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_display_observer_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_display_observer_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL
);

CREATE TABLE display_observer_binding_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation VARCHAR(16) NOT NULL,
    agent_id_snapshot BIGINT NOT NULL,
    viewer_user_id_snapshot BIGINT NOT NULL,
    operator_user_id BIGINT NULL,
    binding_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_display_observer_audit_operation CHECK (
        operation IN ('BIND', 'ENABLE', 'DISABLE', 'ROTATE', 'REBIND')),
    CONSTRAINT ck_display_observer_audit_version CHECK (binding_version >= 1),
    CONSTRAINT fk_display_observer_audit_operator FOREIGN KEY (operator_user_id)
        REFERENCES sys_user(id) ON DELETE SET NULL
);

CREATE INDEX idx_display_observer_audit_binding
    ON display_observer_binding_audit (agent_id_snapshot, id);
