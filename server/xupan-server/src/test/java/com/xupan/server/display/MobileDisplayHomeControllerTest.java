package com.xupan.server.display;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "xupan.automation.enabled=false",
        "xupan.display.mobile.external.enabled=false"
})
class MobileDisplayHomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanBefore() {
        clean();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM mobile_display_lottery_snapshot");
    }

    @Test
    void servesTheLatestStoredSnapshotWithoutAuthentication() throws Exception {
        MobileDisplayHomeResponse.LotteryCard card = new MobileDisplayHomeResponse.LotteryCard(
                "hong-kong",
                10048,
                "香港彩",
                "2026103",
                Instant.parse("2026-09-26T13:30:00Z"),
                "DAY_HH_MM",
                List.of("35", "46", "45", "34", "43", "02", "41"),
                List.of("red", "red", "red", "red", "green", "red", "blue"),
                List.of("猴 金", "总分：246")
        );
        jdbc.update("""
                INSERT INTO mobile_display_lottery_snapshot
                    (source_code, source_lot_code, issue_no, lot_name, next_draw_at,
                     card_json, payload_hash, fetched_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                MobileDisplayLotterySnapshotRepository.SOURCE_CODE,
                card.lotCode(),
                card.issue(),
                card.name(),
                java.sql.Timestamp.from(card.nextDrawAt()),
                objectMapper.writeValueAsString(card),
                "test-hash");

        mockMvc.perform(get("/api/display/mobile/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.cards[0].key").value("hong-kong"))
                .andExpect(jsonPath("$.cards[0].numbers[0]").value("35"))
                .andExpect(jsonPath("$.cards[0].numberColors[4]").value("green"));
    }
}
