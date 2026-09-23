CREATE TABLE player_member_code_sequence (
    player_kind VARCHAR(16) NOT NULL PRIMARY KEY,
    next_value BIGINT NOT NULL,
    CONSTRAINT ck_player_member_code_sequence_kind CHECK (player_kind IN ('NORMAL', 'BOT')),
    CONSTRAINT ck_player_member_code_sequence_value CHECK (next_value >= 100)
);

INSERT INTO player_member_code_sequence (player_kind, next_value)
VALUES ('NORMAL', 1000), ('BOT', 100);
