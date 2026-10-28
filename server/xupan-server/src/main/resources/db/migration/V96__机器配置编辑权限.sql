ALTER TABLE agent
    ADD COLUMN config_edit_enabled BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT '代理是否可修改本机赔率返水限额配置';
