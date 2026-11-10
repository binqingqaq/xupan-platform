ALTER TABLE game_issue
    ADD COLUMN draw_rule_version TINYINT UNSIGNED NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_game_issue_draw_rule_version CHECK (draw_rule_version IN (1, 2));

ALTER TABLE game_issue DROP CHECK ck_game_issue_confirmed_result_complete;

ALTER TABLE game_issue
    ADD CONSTRAINT ck_game_issue_confirmed_result_complete CHECK (
        draw_confirmed_at IS NULL OR (
            draw_result_version > 0
            AND (
                (game_code = 'AU8' AND draw_rule_version = 2 AND ball_count = 7
                    AND number_1 BETWEEN 1 AND 40 AND number_2 BETWEEN 1 AND 40
                    AND number_3 BETWEEN 1 AND 40 AND number_4 BETWEEN 1 AND 40
                    AND number_5 BETWEEN 1 AND 40 AND number_6 BETWEEN 1 AND 40
                    AND number_7 BETWEEN 1 AND 40 AND number_8 IS NULL
                    AND number_1 <> number_2 AND number_1 <> number_3 AND number_1 <> number_4
                    AND number_1 <> number_5 AND number_1 <> number_6 AND number_1 <> number_7
                    AND number_2 <> number_3 AND number_2 <> number_4 AND number_2 <> number_5
                    AND number_2 <> number_6 AND number_2 <> number_7
                    AND number_3 <> number_4 AND number_3 <> number_5 AND number_3 <> number_6
                    AND number_3 <> number_7 AND number_4 <> number_5 AND number_4 <> number_6
                    AND number_4 <> number_7 AND number_5 <> number_6 AND number_5 <> number_7
                    AND number_6 <> number_7)
                OR (NOT (game_code = 'AU8' AND draw_rule_version = 2)
                    AND number_1 BETWEEN 1 AND 20 AND number_2 BETWEEN 1 AND 20
                    AND number_3 BETWEEN 1 AND 20 AND number_4 BETWEEN 1 AND 20
                    AND number_5 BETWEEN 1 AND 20 AND number_6 BETWEEN 1 AND 20
                    AND number_7 BETWEEN 1 AND 20
                    AND ((ball_count = 7 AND number_8 IS NULL)
                      OR (ball_count = 8 AND number_8 BETWEEN 1 AND 20)))
            )
        )
    );

DROP TRIGGER IF EXISTS trg_game_issue_confirmed_result_immutable_update;

CREATE TRIGGER trg_game_issue_confirmed_result_immutable_update
BEFORE UPDATE ON game_issue
FOR EACH ROW
BEGIN
    IF NOT (NEW.ball_count <=> OLD.ball_count)
       OR NOT (NEW.draw_rule_version <=> OLD.draw_rule_version) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Issue ball count and draw rule version are immutable';
    END IF;

    IF OLD.draw_confirmed_at IS NOT NULL AND (
        NOT (NEW.game_code <=> OLD.game_code)
        OR NOT (NEW.issue_number <=> OLD.issue_number)
        OR NOT (NEW.number_1 <=> OLD.number_1)
        OR NOT (NEW.number_2 <=> OLD.number_2)
        OR NOT (NEW.number_3 <=> OLD.number_3)
        OR NOT (NEW.number_4 <=> OLD.number_4)
        OR NOT (NEW.number_5 <=> OLD.number_5)
        OR NOT (NEW.number_6 <=> OLD.number_6)
        OR NOT (NEW.number_7 <=> OLD.number_7)
        OR NOT (NEW.number_8 <=> OLD.number_8)
        OR NOT (NEW.draw_result_version <=> OLD.draw_result_version)
        OR NOT (NEW.draw_confirmed_at <=> OLD.draw_confirmed_at)
        OR NOT (NEW.draw_confirmed_by <=> OLD.draw_confirmed_by)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Confirmed draw result is immutable';
    END IF;
END;

DROP TRIGGER IF EXISTS trg_game_issue_au8_unique_presets_insert;
CREATE TRIGGER trg_game_issue_au8_unique_presets_insert
BEFORE INSERT ON game_issue
FOR EACH ROW
BEGIN
    IF NEW.game_code = 'AU8' AND NEW.draw_rule_version <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'New AU8 issues must use draw rule version 2';
    END IF;

    IF NEW.game_code = 'AU8' AND NEW.draw_rule_version = 2 AND (
        (NEW.number_1 IS NOT NULL AND NEW.number_1 NOT BETWEEN 1 AND 40)
        OR (NEW.number_2 IS NOT NULL AND NEW.number_2 NOT BETWEEN 1 AND 40)
        OR (NEW.number_3 IS NOT NULL AND NEW.number_3 NOT BETWEEN 1 AND 40)
        OR (NEW.number_4 IS NOT NULL AND NEW.number_4 NOT BETWEEN 1 AND 40)
        OR (NEW.number_5 IS NOT NULL AND NEW.number_5 NOT BETWEEN 1 AND 40)
        OR (NEW.number_6 IS NOT NULL AND NEW.number_6 NOT BETWEEN 1 AND 40)
        OR (NEW.number_7 IS NOT NULL AND NEW.number_7 NOT BETWEEN 1 AND 40)
        OR (NEW.number_8 IS NOT NULL)
        OR (NEW.number_1 IS NOT NULL AND NEW.number_1 IN (NEW.number_2, NEW.number_3, NEW.number_4, NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_2 IS NOT NULL AND NEW.number_2 IN (NEW.number_3, NEW.number_4, NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_3 IS NOT NULL AND NEW.number_3 IN (NEW.number_4, NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_4 IS NOT NULL AND NEW.number_4 IN (NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_5 IS NOT NULL AND NEW.number_5 IN (NEW.number_6, NEW.number_7))
        OR (NEW.number_6 IS NOT NULL AND NEW.number_6 = NEW.number_7)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'AU8 draw numbers must be unique values from 1 to 40';
    END IF;
END;

DROP TRIGGER IF EXISTS trg_game_issue_au8_unique_presets_update;
CREATE TRIGGER trg_game_issue_au8_unique_presets_update
BEFORE UPDATE ON game_issue
FOR EACH ROW
BEGIN
    IF NEW.game_code = 'AU8' AND NEW.draw_rule_version = 2 AND (
        (NEW.number_1 IS NOT NULL AND NEW.number_1 NOT BETWEEN 1 AND 40)
        OR (NEW.number_2 IS NOT NULL AND NEW.number_2 NOT BETWEEN 1 AND 40)
        OR (NEW.number_3 IS NOT NULL AND NEW.number_3 NOT BETWEEN 1 AND 40)
        OR (NEW.number_4 IS NOT NULL AND NEW.number_4 NOT BETWEEN 1 AND 40)
        OR (NEW.number_5 IS NOT NULL AND NEW.number_5 NOT BETWEEN 1 AND 40)
        OR (NEW.number_6 IS NOT NULL AND NEW.number_6 NOT BETWEEN 1 AND 40)
        OR (NEW.number_7 IS NOT NULL AND NEW.number_7 NOT BETWEEN 1 AND 40)
        OR (NEW.number_8 IS NOT NULL)
        OR (NEW.number_1 IS NOT NULL AND NEW.number_1 IN (NEW.number_2, NEW.number_3, NEW.number_4, NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_2 IS NOT NULL AND NEW.number_2 IN (NEW.number_3, NEW.number_4, NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_3 IS NOT NULL AND NEW.number_3 IN (NEW.number_4, NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_4 IS NOT NULL AND NEW.number_4 IN (NEW.number_5, NEW.number_6, NEW.number_7))
        OR (NEW.number_5 IS NOT NULL AND NEW.number_5 IN (NEW.number_6, NEW.number_7))
        OR (NEW.number_6 IS NOT NULL AND NEW.number_6 = NEW.number_7)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'AU8 draw numbers must be unique values from 1 to 40';
    END IF;
END;

ALTER TABLE game_bet_selection DROP CHECK ck_game_bet_selection_number;
ALTER TABLE game_bet_selection
    ADD CONSTRAINT ck_game_bet_selection_number CHECK (number_value IS NULL OR number_value BETWEEN 1 AND 40);
