ALTER TABLE game_issue
    ADD COLUMN draw_result_version INT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN draw_confirmed_at TIMESTAMP NULL,
    ADD COLUMN draw_confirmed_by BIGINT NULL;

ALTER TABLE game_issue
    ADD CONSTRAINT fk_game_issue_draw_confirmed_by
        FOREIGN KEY (draw_confirmed_by) REFERENCES sys_user(id) ON DELETE RESTRICT;

UPDATE game_issue
   SET draw_result_version = 1,
       draw_confirmed_at = COALESCE(opened_at, settled_at, updated_at, created_at)
 WHERE number_1 IS NOT NULL AND number_2 IS NOT NULL AND number_3 IS NOT NULL
   AND number_4 IS NOT NULL AND number_5 IS NOT NULL AND number_6 IS NOT NULL
   AND number_7 IS NOT NULL AND number_8 IS NOT NULL;

ALTER TABLE game_issue
    ADD CONSTRAINT ck_game_issue_confirmed_result_complete CHECK (
        draw_confirmed_at IS NULL OR (
            draw_result_version > 0
            AND number_1 BETWEEN 1 AND 20 AND number_2 BETWEEN 1 AND 20
            AND number_3 BETWEEN 1 AND 20 AND number_4 BETWEEN 1 AND 20
            AND number_5 BETWEEN 1 AND 20 AND number_6 BETWEEN 1 AND 20
            AND number_7 BETWEEN 1 AND 20 AND number_8 BETWEEN 1 AND 20
        )
    );
