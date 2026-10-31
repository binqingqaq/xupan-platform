ALTER TABLE game_issue
    ADD CONSTRAINT ck_game_issue_seven_ball_shape CHECK (
        ball_count = 8 OR number_8 IS NULL
    );

ALTER TABLE game_issue DROP CHECK ck_game_issue_confirmed_result_complete;

ALTER TABLE game_issue
    ADD CONSTRAINT ck_game_issue_confirmed_result_complete CHECK (
        draw_confirmed_at IS NULL OR (
            draw_result_version > 0
            AND number_1 BETWEEN 1 AND 20 AND number_2 BETWEEN 1 AND 20
            AND number_3 BETWEEN 1 AND 20 AND number_4 BETWEEN 1 AND 20
            AND number_5 BETWEEN 1 AND 20 AND number_6 BETWEEN 1 AND 20
            AND number_7 BETWEEN 1 AND 20
            AND (
                (ball_count = 7 AND number_8 IS NULL)
                OR (ball_count = 8 AND number_8 BETWEEN 1 AND 20)
            )
        )
    );

DROP TRIGGER IF EXISTS trg_game_issue_confirmed_result_immutable_update;

CREATE TRIGGER trg_game_issue_confirmed_result_immutable_update
BEFORE UPDATE ON game_issue
FOR EACH ROW
BEGIN
    IF NOT (NEW.ball_count <=> OLD.ball_count) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Issue ball count is immutable';
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

CREATE TRIGGER trg_game_definition_no_au8_ball_rule_revert
BEFORE UPDATE ON game_definition
FOR EACH ROW
BEGIN
    DECLARE has_seven_ball_issue INT DEFAULT 0;

    IF NEW.game_code = 'AU8' AND OLD.next_issue_ball_count = 7 AND NEW.next_issue_ball_count = 8 THEN
        SELECT COUNT(*) INTO has_seven_ball_issue
          FROM game_issue
         WHERE game_code = NEW.game_code AND ball_count = 7;

        IF has_seven_ball_issue > 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'AU8 ball count cannot revert to eight after seven-ball activation';
        END IF;
    END IF;
END;
