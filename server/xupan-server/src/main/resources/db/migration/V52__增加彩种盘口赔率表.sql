CREATE TABLE game_route_odds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_id BIGINT NOT NULL,
    route_code VARCHAR(1) NOT NULL,
    special_odds DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    big_small_odds DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_route_odds UNIQUE (game_id, route_code),
    CONSTRAINT fk_game_route_odds_game FOREIGN KEY (game_id) REFERENCES game_definition(id),
    CONSTRAINT ck_game_route_odds_route CHECK (route_code IN ('A', 'B', 'C', 'D')),
    CONSTRAINT ck_game_route_odds_special CHECK (special_odds >= 0),
    CONSTRAINT ck_game_route_odds_big_small CHECK (big_small_odds >= 0)
);

CREATE INDEX idx_game_route_odds_game ON game_route_odds (game_id, route_code);

INSERT INTO game_route_odds (game_id, route_code, special_odds, big_small_odds)
SELECT 1, 'A', 0.00, 0.00 WHERE NOT EXISTS (SELECT 1 FROM game_route_odds WHERE game_id = 1 AND route_code = 'A');
INSERT INTO game_route_odds (game_id, route_code, special_odds, big_small_odds)
SELECT 1, 'B', 0.00, 0.00 WHERE NOT EXISTS (SELECT 1 FROM game_route_odds WHERE game_id = 1 AND route_code = 'B');
INSERT INTO game_route_odds (game_id, route_code, special_odds, big_small_odds)
SELECT 1, 'C', 0.00, 0.00 WHERE NOT EXISTS (SELECT 1 FROM game_route_odds WHERE game_id = 1 AND route_code = 'C');
INSERT INTO game_route_odds (game_id, route_code, special_odds, big_small_odds)
SELECT 1, 'D', 0.00, 0.00 WHERE NOT EXISTS (SELECT 1 FROM game_route_odds WHERE game_id = 1 AND route_code = 'D');