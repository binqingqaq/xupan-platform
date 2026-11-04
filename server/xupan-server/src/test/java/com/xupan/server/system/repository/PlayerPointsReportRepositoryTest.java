package com.xupan.server.system.repository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerPointsReportRepositoryTest {

    @Test
    void preservesOddsAndRebateSnapshotPrecisionWhileMappingMoney() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(17L);
        when(rs.getLong("account_id")).thenReturn(23L);
        when(rs.getString("bet_code")).thenReturn("REPORT-PRECISION");
        when(rs.getString("issue_number")).thenReturn("3000001");
        when(rs.getInt("ball_number")).thenReturn(1);
        when(rs.getString("play_type")).thenReturn("FAN");
        when(rs.getString("parameters_text")).thenReturn("1");
        when(rs.getBigDecimal("stake")).thenReturn(new BigDecimal("20.00"));
        when(rs.getBigDecimal("odds_snapshot")).thenReturn(new BigDecimal("3.8515"));
        when(rs.getBigDecimal("rebate_rate_snapshot")).thenReturn(new BigDecimal("0.0015"));
        when(rs.getBigDecimal("special_rebate_rate_snapshot")).thenReturn(new BigDecimal("0.0025"));
        when(rs.getBigDecimal("selection_unit_stake")).thenReturn(null);
        when(rs.getString("settlement_status")).thenReturn("PENDING");
        when(rs.getBigDecimal("net_profit")).thenReturn(new BigDecimal("0.00"));
        when(rs.getString("explanation")).thenReturn(null);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf("2026-11-04 10:00:00"));
        when(rs.getTimestamp("settled_at")).thenReturn(null);

        PlayerPointsReportRepository.BetEvent event = PlayerPointsReportRepository.mapBetEvent(rs);

        assertThat(event.stake()).isEqualByComparingTo("20.00");
        assertThat(event.odds()).isEqualByComparingTo("3.8515");
        assertThat(event.rebateRate()).isEqualByComparingTo("0.0015");
        assertThat(event.specialRebateRate()).isEqualByComparingTo("0.0025");
    }
}
