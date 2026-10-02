ALTER TABLE agent_group ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE agent_group ADD COLUMN deleted_by BIGINT NULL;
ALTER TABLE agent_group ADD CONSTRAINT fk_agent_group_deleter FOREIGN KEY (deleted_by) REFERENCES sys_user(id);

ALTER TABLE agent ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE agent ADD COLUMN deleted_by BIGINT NULL;
ALTER TABLE agent ADD CONSTRAINT fk_agent_deleter FOREIGN KEY (deleted_by) REFERENCES sys_user(id);

CREATE INDEX idx_agent_group_live ON agent_group (deleted_at, status, id);
CREATE INDEX idx_agent_live ON agent (deleted_at, group_id, status, id);