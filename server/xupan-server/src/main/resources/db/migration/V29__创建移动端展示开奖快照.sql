CREATE TABLE mobile_display_lottery_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_code VARCHAR(32) NOT NULL,
    source_lot_code INT NOT NULL,
    issue_no VARCHAR(64) NOT NULL,
    next_issue_no VARCHAR(64),
    lot_name VARCHAR(128) NOT NULL,
    next_draw_at TIMESTAMP NULL,
    card_json TEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    fetched_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_mobile_display_lottery_issue
        UNIQUE (source_code, source_lot_code, issue_no)
);

CREATE INDEX idx_mobile_display_lottery_latest
    ON mobile_display_lottery_snapshot (source_code, source_lot_code, fetched_at DESC);
