package com.xupan.server.game.service;

import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MachineBetLimitServiceTest {

    @Test
    void keepsDifferentFanSelectionsInSeparateQuotaGroups() {
        GameDataRepository repository = mock(GameDataRepository.class);
        prepareUsage(repository, List.of(
                new GameDataRepository.QuotaUsage("FAN", "FAN:1:1", null, new BigDecimal("90.00"))));
        MachineBetLimitService service = new MachineBetLimitService(repository);

        service.validateBatch(11, "AU8", "3000001", config("200.00", "100.00"), List.of(
                new MachineBetLimitService.Instruction(PlayType.FAN, List.of(2),
                        new BigDecimal("20.00"), new BigDecimal("20.00"))));
    }

    @Test
    void zeroTotalOrPlayLimitIsNotTreatedAsUnlimited() {
        GameDataRepository repository = mock(GameDataRepository.class);
        prepareUsage(repository, List.of());
        MachineBetLimitService service = new MachineBetLimitService(repository);

        assertThatThrownBy(() -> service.validateBatch(11, "AU8", "3000001",
                config("0.00", "100.00"), instruction()))
                .isInstanceOf(BetLimitExceededException.class)
                .hasMessageContaining("单场总限额未配置");
        assertThatThrownBy(() -> service.validateBatch(11, "AU8", "3000001",
                config("100.00", "0.00"), instruction()))
                .isInstanceOf(BetLimitExceededException.class)
                .hasMessageContaining("番限额未配置");
    }

    @Test
    void refusesToPlaceBetsWhenLegacyOrdersHaveNoQuotaSnapshot() {
        GameDataRepository repository = mock(GameDataRepository.class);
        when(repository.countUnclassifiedQuotaBets(11, "AU8", "3000001")).thenReturn(1L);
        MachineBetLimitService service = new MachineBetLimitService(repository);

        assertThatThrownBy(() -> service.validateBatch(11, "AU8", "3000001",
                config("100.00", "100.00"), instruction()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ISSUE_HAS_UNCLASSIFIED_BETS");
    }

    private static List<MachineBetLimitService.Instruction> instruction() {
        return List.of(new MachineBetLimitService.Instruction(PlayType.FAN, List.of(1),
                new BigDecimal("10.00"), new BigDecimal("10.00")));
    }

    private static MachineBettingConfig config(String total, String fan) {
        return new MachineBettingConfig(new BigDecimal("95"), new BigDecimal("18"), 0, 0,
                MachineBettingConfig.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO, 3, 1,
                new MachineBettingConfig.BettingLimits(new BigDecimal(total), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(fan),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static void prepareUsage(GameDataRepository repository, List<GameDataRepository.QuotaUsage> rows) {
        when(repository.countUnclassifiedQuotaBets(11, "AU8", "3000001")).thenReturn(0L);
        when(repository.findIssueStakeTotal(11, 3, "AU8", "3000001")).thenReturn(BigDecimal.ZERO);
        when(repository.findQuotaUsageByIssue(11, 3, "AU8", "3000001")).thenReturn(rows);
    }
}
