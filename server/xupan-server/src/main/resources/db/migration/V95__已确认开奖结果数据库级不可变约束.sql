CREATE TRIGGER trg_game_issue_confirmed_result_immutable_update
BEFORE UPDATE ON game_issue
FOR EACH ROW
BEGIN
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

CREATE TRIGGER trg_game_issue_confirmed_result_immutable_delete
BEFORE DELETE ON game_issue
FOR EACH ROW
BEGIN
    IF OLD.draw_confirmed_at IS NOT NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Confirmed draw result cannot be deleted';
    END IF;
END;
