package com.xupan.server.game.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrawRuleTest {

    @Test
    void acceptsSevenUniqueAu8NumbersFromOneToForty() {
        DrawRule.validateNumbers("AU8", 2, 7, List.of(1, 7, 14, 20, 21, 39, 40));
    }

    @Test
    void rejectsDuplicateOrOutOfRangeAu8Draws() {
        assertThatThrownBy(() -> DrawRule.validateNumbers("AU8", 2, 7,
                List.of(1, 7, 14, 20, 21, 39, 39))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrawRule.validateNumbers("AU8", 2, 7,
                List.of(1, 7, 14, 20, 21, 39, 41))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesLegacyRepeatedNumbersForOldIssuesAndOtherGames() {
        DrawRule.validateNumbers("AU8", 1, 7, List.of(2, 2, 2, 2, 2, 2, 2));
        DrawRule.validateNumbers("OTHER", 1, 8, List.of(2, 2, 2, 2, 2, 2, 2, 2));
    }
}
