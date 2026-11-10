package com.xupan.server.game.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BallResultTest {

    @Test
    void mapsNumberToFanAndAttributes() {
        assertThat(BallResult.fromNumber(1)).isEqualTo(new BallResult(1, 1, "ODD", "SMALL"));
        assertThat(BallResult.fromNumber(4)).isEqualTo(new BallResult(4, 4, "EVEN", "SMALL"));
        assertThat(BallResult.fromNumber(18)).isEqualTo(new BallResult(18, 2, "EVEN", "BIG"));
        assertThat(BallResult.fromNumber(20)).isEqualTo(new BallResult(20, 4, "EVEN", "BIG"));
    }

    @Test
    void rejectsNumbersOutsideTheGameRange() {
        assertThatThrownBy(() -> BallResult.fromNumber(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BallResult.fromNumber(21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesTheVersionedAu8FortyNumberRules() {
        assertThat(BallResult.fromNumber(20, "AU8", 2))
                .isEqualTo(new BallResult(20, 4, "EVEN", "SMALL"));
        assertThat(BallResult.fromNumber(21, "AU8", 2))
                .isEqualTo(new BallResult(21, 1, "ODD", "BIG"));
        assertThat(BallResult.fromNumber(40, "AU8", 2))
                .isEqualTo(new BallResult(40, 4, "EVEN", "BIG"));
        assertThat(BallResult.fromNumber(20, "AU8", 1).size()).isEqualTo("BIG");
        assertThatThrownBy(() -> BallResult.fromNumber(41, "AU8", 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
