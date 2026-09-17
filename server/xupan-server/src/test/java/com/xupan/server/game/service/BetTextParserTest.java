package com.xupan.server.game.service;

import com.xupan.server.game.domain.PlayType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BetTextParserTest {

    @Test
    void parsesAllConfirmedFormatsAndAlwaysTargetsBallOne() {
        assertBet("1番100", PlayType.FAN, List.of(1), "100.00");
        assertBet("12角100", PlayType.ANGLE, List.of(1, 2), "100.00");
        assertBet("124/100", PlayType.CAR, List.of(1, 2, 4), "100.00");
        assertBet("1严2/100", PlayType.STRICT, List.of(1, 2), "100.00");
        assertBet("1念2/100", PlayType.STRICT, List.of(1, 2), "100.00");
        assertBet("1加23/100", PlayType.ADD, List.of(1, 2, 3), "100.00");
        assertBet("1正100", PlayType.POSITIVE, List.of(1), "100.00");
        assertBet("3通12/100", PlayType.TONG, List.of(3, 1, 2), "100.00");
        assertBet("12无3/100", PlayType.NONE, List.of(1, 2, 3), "100.00");
        assertBet("单100", PlayType.ODD_EVEN, List.of(1), "100.00");
        assertBet("双100", PlayType.ODD_EVEN, List.of(2), "100.00");
        assertBet("大100", PlayType.BIG_SMALL, List.of(1), "100.00");
        assertBet("小100", PlayType.BIG_SMALL, List.of(2), "100.00");
        assertBet("01特100", PlayType.SPECIAL, List.of(1), "100.00");
        assertBet("02/03/04特100", PlayType.SPECIAL, List.of(2, 3, 4), "100.00");
    }

    @Test
    void acceptsProjectMoneyPrecisionAndOptionalSeparatorsWhereConfirmed() {
        assertBet(" 1番0.01 ", PlayType.FAN, List.of(1), "0.01");
        assertBet("12角/100.5", PlayType.ANGLE, List.of(1, 2), "100.50");
        assertBet("1番/100.00", PlayType.FAN, List.of(1), "100.00");
    }

    @Test
    void marksUnconfirmedVariantsAsUnsupported() {
        assertStatus("1无2", BetTextParser.Status.UNSUPPORTED);
        assertStatus("1无2/100", BetTextParser.Status.UNSUPPORTED);
        assertStatus("3车151", BetTextParser.Status.UNSUPPORTED);
        assertStatus("1车100", BetTextParser.Status.UNSUPPORTED);
    }

    @Test
    void rejectsOutOfRangeDuplicateMissingAndIllegalAmounts() {
        assertStatus("5番100", BetTextParser.Status.INVALID);
        assertStatus("12角100", BetTextParser.Status.ACCEPTED);
        assertStatus("11角100", BetTextParser.Status.INVALID);
        assertStatus("1严1/100", BetTextParser.Status.INVALID);
        assertStatus("1加25/100", BetTextParser.Status.INVALID);
        assertStatus("3通13/100", BetTextParser.Status.INVALID);
        assertStatus("12无2/100", BetTextParser.Status.INVALID);
        assertStatus("01/21特100", BetTextParser.Status.INVALID);
        assertStatus("01/01特100", BetTextParser.Status.INVALID);
        assertStatus("1番", BetTextParser.Status.INVALID);
        assertStatus("1番0", BetTextParser.Status.INVALID);
        assertStatus("1番100.123", BetTextParser.Status.INVALID);
        assertStatus("1番1000000000000", BetTextParser.Status.INVALID);
        assertStatus("not-a-bet", BetTextParser.Status.INVALID);
    }

    private void assertBet(String text, PlayType playType, List<Integer> parameters, String stake) {
        BetTextParser.ParseResult result = BetTextParser.parse(text);

        assertThat(result.status()).isEqualTo(BetTextParser.Status.ACCEPTED);
        assertThat(result.bet()).isNotNull();
        assertThat(result.bet().ballNumber()).isEqualTo(1);
        assertThat(result.bet().playType()).isEqualTo(playType);
        assertThat(result.bet().parameters()).containsExactlyElementsOf(parameters);
        assertThat(result.bet().stake()).isEqualByComparingTo(stake);
    }

    private void assertStatus(String text, BetTextParser.Status status) {
        assertThat(BetTextParser.parse(text).status()).isEqualTo(status);
    }
}
