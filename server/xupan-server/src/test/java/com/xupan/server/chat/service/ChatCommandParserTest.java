package com.xupan.server.chat.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCommandParserTest {

    @Test
    void parsesConfirmedCommandsAndAmounts() {
        assertThat(ChatCommandParser.parse("查")).get().extracting(ChatCommandParser.Command::type)
                .isEqualTo(ChatCommandParser.Type.BALANCE);
        assertThat(ChatCommandParser.parse("玩法")).get().extracting(ChatCommandParser.Command::type)
                .isEqualTo(ChatCommandParser.Type.RULES);
        assertThat(ChatCommandParser.parse("说明")).get().extracting(ChatCommandParser.Command::type)
                .isEqualTo(ChatCommandParser.Type.RULES);
        assertThat(ChatCommandParser.parse("取消")).get().extracting(ChatCommandParser.Command::type)
                .isEqualTo(ChatCommandParser.Type.CANCEL);
        assertThat(ChatCommandParser.parse("流水")).get().extracting(ChatCommandParser.Command::type)
                .isEqualTo(ChatCommandParser.Type.DAILY_SUMMARY);
        assertThat(ChatCommandParser.parse("上100.50")).get().satisfies(command -> {
            assertThat(command.type()).isEqualTo(ChatCommandParser.Type.TOP_UP);
            assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("100.50"));
        });
        assertThat(ChatCommandParser.parse("下100")).get().satisfies(command -> {
            assertThat(command.type()).isEqualTo(ChatCommandParser.Type.DOWN);
            assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("100"));
        });
    }

    @Test
    void leavesBetsAndOtherMessagesForTheirNormalHandlers() {
        assertThat(ChatCommandParser.parse("1番100")).isEmpty();
        assertThat(ChatCommandParser.parse("你好")).isEmpty();
        assertThat(ChatCommandParser.parse("上0")).isEmpty();
        assertThat(ChatCommandParser.parse("下100.123")).isEmpty();
    }
}
