package com.xupan.server.game.service;

import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameBettingConfigRepository;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameBettingConfigServiceTest {

    @Mock
    private GameBettingConfigRepository configRepository;
    @Mock
    private GameDataRepository gameRepository;

    private GameBettingConfigService service;

    @BeforeEach
    void setUp() {
        service = new GameBettingConfigService(configRepository, gameRepository);
    }

    @Test
    void rejectsSingleBetBelowPlayerMinimumStake() {
        when(configRepository.find()).thenReturn(Optional.of(config()));

        var rejection = service.evaluate(PlayType.FAN, new BigDecimal("0.50"),
                service.loadUsage(1L, "3000000"));

        assertThat(rejection).isNotNull();
        assertThat(rejection.kind()).isEqualTo(GameBettingConfigService.LimitKind.MIN);
        assertThat(service.describeRejection(rejection)).isEqualTo("低于玩家最小注额1");
    }

    @Test
    void reportsTypeQuotaAndRemainingQuota() {
        when(configRepository.find()).thenReturn(Optional.of(configWith(100000, 100000)));
        var usage = service.loadUsage(1L, "3000000");
        service.accept(usage, PlayType.FAN, new BigDecimal("9800.00"));

        var typeRejection = service.evaluate(PlayType.FAN, new BigDecimal("300.00"), usage);

        assertThat(typeRejection.kind()).isEqualTo(GameBettingConfigService.LimitKind.TYPE);
        assertThat(service.describeRejection(typeRejection))
                .isEqualTo("超过番限额10000，剩余可下200");
    }

    @Test
    void reportsPlayerMaximumAndIssueTotalQuota() {
        when(configRepository.find()).thenReturn(Optional.of(config()));
        var maxUsage = service.loadUsage(1L, "3000000");
        service.accept(maxUsage, PlayType.FAN, new BigDecimal("9800.00"));
        service.accept(maxUsage, PlayType.ANGLE, new BigDecimal("1000.00"));

        var maxRejection = service.evaluate(PlayType.ANGLE, new BigDecimal("4200.00"), maxUsage);

        assertThat(maxRejection.kind()).isEqualTo(GameBettingConfigService.LimitKind.PLAYER_MAX);
        assertThat(service.describeRejection(maxRejection))
                .isEqualTo("超过玩家最高注额11000，剩余可下200");

        var issueUsage = service.loadUsage(2L, "3000000");
        service.accept(issueUsage, PlayType.CAR, new BigDecimal("4900.00"));
        when(configRepository.find()).thenReturn(Optional.of(configWith(100000, 5000)));

        var issueRejection = service.evaluate(PlayType.CAR, new BigDecimal("200.00"), issueUsage);

        assertThat(issueRejection.kind()).isEqualTo(GameBettingConfigService.LimitKind.ISSUE_TOTAL);
        assertThat(service.describeRejection(issueRejection))
                .isEqualTo("超过单场总限额5000，剩余可下100");
    }

    @Test
    void groupsExclusionPlayUnderTongLimit() {
        when(configRepository.find()).thenReturn(Optional.of(config()));
        var usage = service.loadUsage(1L, "3000000");
        service.accept(usage, PlayType.NONE, new BigDecimal("1990.00"));

        var rejection = service.evaluate(PlayType.NONE, new BigDecimal("20.00"), usage);

        assertThat(rejection.kind()).isEqualTo(GameBettingConfigService.LimitKind.TYPE);
        assertThat(service.describeRejection(rejection))
                .isEqualTo("超过通限额2000，剩余可下10");
    }

    @Test
    void acceptsBetWithinEveryQuota() {
        when(configRepository.find()).thenReturn(Optional.of(config()));

        assertThat(service.evaluate(PlayType.FAN, new BigDecimal("10.00"),
                service.loadUsage(1L, "3000000"))).isNull();
    }

    @Test
    void limitsMustBePositiveIntegersWithValidStakeRange() {
        assertThatThrownBy(() -> service.updateLimits(new GameBettingConfigService.LimitConfigRequest(
                200, 5000, 20000, 1000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 0, 1,
                20, 5000, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("BETTING_CONFIG_INVALID");

        assertThatThrownBy(() -> service.updateLimits(new GameBettingConfigService.LimitConfigRequest(
                200, 5000, 20000, 1000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 10, 20,
                20, 5000, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("BETTING_CONFIG_INVALID");
    }

    private static GameBettingConfigRepository.ConfigRecord config() {
        return new GameBettingConfigRepository.ConfigRecord(95, 1,
                200, 5000, 20000, 1000, 20000, 2000, 20000, 20000, 20000, 10000, 20000,
                11000, 1, 20, 5000, null);
    }

    private static GameBettingConfigRepository.ConfigRecord configWith(int playerMaxStake,
                                                                      int issueTotalLimit) {
        return new GameBettingConfigRepository.ConfigRecord(95, 1,
                200, issueTotalLimit, 20000, 1000, 20000, 2000, 20000, 20000, 20000, 10000, 20000,
                playerMaxStake, 1, 20, 5000, null);
    }
}
