CREATE TRIGGER trg_game_bet_issue_ball_count_insert
BEFORE INSERT ON game_bet
FOR EACH ROW
BEGIN
    DECLARE issue_ball_count TINYINT UNSIGNED DEFAULT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET issue_ball_count = NULL;

    SELECT ball_count INTO issue_ball_count
      FROM game_issue
     WHERE game_code = NEW.game_code AND issue_number = NEW.issue_number
     LIMIT 1;

    IF issue_ball_count IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Bet issue does not exist';
    ELSEIF NEW.ball_number > issue_ball_count THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Bet ball number exceeds issue ball count';
    END IF;
END;

CREATE TRIGGER trg_game_bet_issue_ball_count_update
BEFORE UPDATE ON game_bet
FOR EACH ROW
BEGIN
    DECLARE issue_ball_count TINYINT UNSIGNED DEFAULT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET issue_ball_count = NULL;

    SELECT ball_count INTO issue_ball_count
      FROM game_issue
     WHERE game_code = NEW.game_code AND issue_number = NEW.issue_number
     LIMIT 1;

    IF issue_ball_count IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Bet issue does not exist';
    ELSEIF NEW.ball_number > issue_ball_count THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Bet ball number exceeds issue ball count';
    END IF;
END;

CREATE TRIGGER trg_center_order_issue_ball_count_insert
BEFORE INSERT ON center_order
FOR EACH ROW
BEGIN
    DECLARE issue_ball_count TINYINT UNSIGNED DEFAULT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET issue_ball_count = NULL;

    IF NEW.status = 'ACCEPTED' OR NEW.ball_number IS NOT NULL THEN
        IF NEW.ball_number IS NULL THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Accepted center order requires a ball number';
        END IF;
        SELECT ball_count INTO issue_ball_count
          FROM game_issue
         WHERE game_code = NEW.game_code AND issue_number = NEW.issue_number
         LIMIT 1;

        IF issue_ball_count IS NULL THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Center order issue does not exist';
        ELSEIF NEW.ball_number > issue_ball_count THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Center order ball number exceeds issue ball count';
        END IF;
    END IF;
END;

CREATE TRIGGER trg_center_order_issue_ball_count_update
BEFORE UPDATE ON center_order
FOR EACH ROW
BEGIN
    DECLARE issue_ball_count TINYINT UNSIGNED DEFAULT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET issue_ball_count = NULL;

    IF NEW.status = 'ACCEPTED' OR (
        NOT (NEW.game_code <=> OLD.game_code)
        OR NOT (NEW.issue_number <=> OLD.issue_number)
        OR NOT (NEW.ball_number <=> OLD.ball_number)
    ) THEN
        IF NEW.ball_number IS NULL THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Accepted center order requires a ball number';
        END IF;
        SELECT ball_count INTO issue_ball_count
          FROM game_issue
         WHERE game_code = NEW.game_code AND issue_number = NEW.issue_number
         LIMIT 1;

        IF issue_ball_count IS NULL THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Center order issue does not exist';
        ELSEIF NEW.ball_number > issue_ball_count THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Center order ball number exceeds issue ball count';
        END IF;
    END IF;
END;
