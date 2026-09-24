package com.xupan.server.game.service;

import com.xupan.server.game.domain.PlayType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rejects a single bet because it breaks the configured quota.
 *
 * <p>Kept outside {@link com.xupan.server.web.BusinessException} so callers can tell a per-item
 * quota rejection from batch-wide failures (closed phase, insufficient balance) and keep the rest
 * of a batch instead of letting the shared transaction roll back. Extending {@link
 * IllegalStateException} keeps the reason visible through the existing game web error mapping.
 */
public class BetLimitExceededException extends IllegalStateException {

    private final PlayType playType;
    private final List<Integer> parameters;
    private final BigDecimal stake;
    private final String reason;

    public BetLimitExceededException(PlayType playType, List<Integer> parameters, BigDecimal stake,
                                     String reason) {
        super(reason);
        this.playType = playType;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.stake = stake;
        this.reason = reason;
    }

    public PlayType playType() {
        return playType;
    }

    public List<Integer> parameters() {
        return parameters;
    }

    public BigDecimal stake() {
        return stake;
    }

    public String reason() {
        return reason;
    }
}
