package com.xupan.server.display;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MobileDisplayLotteryMapperTest {

    private final MobileDisplayLotteryMapper mapper = new MobileDisplayLotteryMapper(new ObjectMapper());

    @Test
    void mapsTheFifteenMobileHomepageCardsInReferenceOrder() throws Exception {
        String body;
        try (var stream = getClass().getResourceAsStream("/display/mobile-hot-lottery-sample.json")) {
            assertThat(stream).isNotNull();
            body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        MobileDisplayLotteryMapper.ParsedHome parsed = mapper.parse(body);

        assertThat(parsed.cards()).extracting(MobileDisplayHomeResponse.LotteryCard::name)
                .containsExactly(
                        "极速运动会", "快乐运动会", "香港彩", "幸运时时彩", "幸运飞艇",
                        "PC28", "台湾5分彩", "极速飞艇", "宾果六合彩", "快乐8六合彩",
                        "极速赛车", "极速时时彩", "SG飞艇", "SG时时彩", "SG快3");
        MobileDisplayHomeResponse.LotteryCard hongKong = parsed.cards().get(2);
        assertThat(hongKong.numberColors()).containsExactly(
                "red", "red", "red", "red", "green", "red", "blue");
        assertThat(hongKong.summary()).contains("猴 金", "总分：246");
        MobileDisplayHomeResponse.LotteryCard pc28 = parsed.cards().get(5);
        assertThat(pc28.numbers()).containsExactly("0", "5", "6", "11");
        assertThat(pc28.summary()).containsExactly("总和：11", "小", "双");
        MobileDisplayHomeResponse.LotteryCard fastRace = parsed.cards().get(10);
        assertThat(fastRace.numbers()).hasSize(10);
        assertThat(fastRace.summary()).contains("|", "冠亚和：11", "小", "单");
    }
}
