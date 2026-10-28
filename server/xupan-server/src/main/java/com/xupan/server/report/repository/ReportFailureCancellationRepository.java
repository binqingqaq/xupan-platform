package com.xupan.server.report.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Persists an explicit report-failure cancellation while retaining both order facts. */
@Repository
public class ReportFailureCancellationRepository {

    private final JdbcTemplate jdbc;

    public ReportFailureCancellationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Marks a still-open bet canceled; its wallet refund is committed in the caller's transaction. */
    public boolean cancelPendingBet(long betId, Instant canceledAt) {
        return jdbc.update("""
                UPDATE game_bet
                   SET settlement_status = 'CANCELED', net_profit = 0,
                       explanation = '中心上报明确失败，已按失败退单开关退还本金', settled_at = ?
                 WHERE id = ? AND settlement_status = 'PENDING' AND settled_at IS NULL
                """, Timestamp.from(canceledAt), betId) == 1;
    }

    /** Cancels the local bet after an operator explicitly chooses the refund disposition. */
    public boolean cancelPendingBetForCenterDisposition(long betId, Instant canceledAt) {
        return jdbc.update("""
                UPDATE game_bet
                   SET settlement_status = 'CANCELED', net_profit = 0,
                       explanation = '中心订单人工退款并取消本地注单', settled_at = ?
                 WHERE id = ? AND settlement_status = 'PENDING' AND settled_at IS NULL
                """, Timestamp.from(canceledAt), betId) == 1;
    }

    /** Keeps the rejection response fields but advances the refunded attempt to its terminal state. */
    public int markAttemptRefunded(long attemptId, Instant refundedAt) {
        return jdbc.update("""
                UPDATE report_upstream_order
                   SET status = 'REFUNDED', resolved_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'REJECTED'
                """, Timestamp.from(refundedAt), attemptId);
    }
}
