package com.xupan.server.game.service;

import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.web.PlaceBetRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemoGameServiceTest {

    @Test
    void fixedMachineBallOverridesRequestedBallForAu8() {
        PlaceBetRequest requested = request(2, "AU8");

        List<PlaceBetRequest> effective = DemoGameService.applyMachineAu8BallMode("AU8", fixedAt(4),
                List.of(requested));

        assertThat(effective).singleElement().satisfies(bet -> {
            assertThat(bet.ballNumber()).isEqualTo(4);
            assertThat(bet.playType()).isEqualTo(PlayType.FAN);
            assertThat(bet.parameters()).containsExactly(3);
            assertThat(bet.stake()).isEqualByComparingTo("10.00");
            assertThat(bet.idempotencyKey()).isEqualTo("BALL-MODE-1");
            assertThat(bet.gameCode()).isEqualTo("AU8");
        });
    }

    @Test
    void playerSelectedBallRemainsUnchangedInDefaultMode() {
        PlaceBetRequest requested = request(6, "AU8");

        List<PlaceBetRequest> effective = DemoGameService.applyMachineAu8BallMode("AU8",
                MachineBettingConfig.DEFAULT, List.of(requested));

        assertThat(effective).containsExactly(requested);
    }

    @Test
    void fixedMachineBallDoesNotAffectOtherGames() {
        PlaceBetRequest requested = request(2, "OTHER");

        List<PlaceBetRequest> effective = DemoGameService.applyMachineAu8BallMode("OTHER", fixedAt(4),
                List.of(requested));

        assertThat(effective).containsExactly(requested);
    }

    private static MachineBettingConfig fixedAt(int ballNumber) {
        return new MachineBettingConfig(BigDecimal.ONE, BigDecimal.TEN, 0, 0, true, "SINGLE",
                BigDecimal.ZERO, BigDecimal.ZERO, 17, 1, MachineBettingConfig.BettingLimits.UNLIMITED,
                MachineBettingConfig.AU8_FIXED_BALL, ballNumber);
    }

    private static PlaceBetRequest request(int ballNumber, String gameCode) {
        return new PlaceBetRequest(ballNumber, PlayType.FAN, List.of(3), new BigDecimal("10.00"),
                "BALL-MODE-1", gameCode);
    }
}
