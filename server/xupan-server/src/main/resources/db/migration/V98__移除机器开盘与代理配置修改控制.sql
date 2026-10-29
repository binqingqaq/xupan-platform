ALTER TABLE agent
    DROP COLUMN board_open,
    DROP COLUMN config_edit_enabled;

ALTER TABLE agent ALTER COLUMN robot_manage SET DEFAULT TRUE;
