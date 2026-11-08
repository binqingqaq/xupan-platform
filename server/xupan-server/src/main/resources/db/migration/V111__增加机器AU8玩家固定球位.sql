ALTER TABLE agent
    ADD COLUMN au8_ball_mode VARCHAR(16) NOT NULL DEFAULT 'PLAYER_SELECT',
    ADD COLUMN au8_fixed_ball_number TINYINT NULL,
    ADD CONSTRAINT ck_agent_au8_ball_mode CHECK (
        (au8_ball_mode = 'PLAYER_SELECT' AND au8_fixed_ball_number IS NULL)
        OR (au8_ball_mode = 'FIXED_BALL' AND au8_fixed_ball_number BETWEEN 1 AND 7)
    );
