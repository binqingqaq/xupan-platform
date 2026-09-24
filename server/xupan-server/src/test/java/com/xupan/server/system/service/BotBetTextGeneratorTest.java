package com.xupan.server.system.service;

import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.service.BetTextParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BotBetTextGeneratorTest {

    @Test
    void generatesParseableIntegerTextForEveryPlayType() {
        for (PlayType playType : PlayType.values()) {
            for (int index = 0; index < 40; index++) {
                BigDecimal amount = BigDecimal.valueOf(120L + index);
                String text = BotBetTextGenerator.randomBet(playType, amount);

                BetTextParser.ParseResult parsed = BetTextParser.parse(text);
                assertThat(parsed.accepted()).as("生成文本应可解析：%s", text).isTrue();
                assertThat(parsed.bets()).hasSize(1);
                assertThat(parsed.bets().get(0).playType()).as(text).isEqualTo(playType);
                assertThat(parsed.bets().get(0).stake()).as(text).isEqualByComparingTo(amount);
                assertThat(text).as("托下注不应出现小数：%s", text).doesNotContain(".");
            }
        }
    }

    @Test
    void keepsParametersInsideTheConfirmedRanges() {
        for (int index = 0; index < 40; index++) {
            String special = BotBetTextGenerator.randomBet(PlayType.SPECIAL, new BigDecimal("100"));
            int number = Integer.parseInt(special.substring(0, 2));
            assertThat(number).isBetween(1, 20);

            String strict = BotBetTextGenerator.randomBet(PlayType.STRICT, new BigDecimal("100"));
            assertThat(strict.charAt(0)).isNotEqualTo(strict.charAt(2));

            String angle = BotBetTextGenerator.randomBet(PlayType.ANGLE, new BigDecimal("100"));
            assertThat(angle.charAt(0)).isNotEqualTo(angle.charAt(1));
        }
    }
}
