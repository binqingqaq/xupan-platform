package com.xupan.server.game.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoGameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM game_bet");
        jdbcTemplate.update("DELETE FROM game_odds");
        jdbcTemplate.update("DELETE FROM game_issue");
    }

    @Test
    void completesBetDrawAndRejectsClosedOperations() throws Exception {
        mockMvc.perform(get("/api/demo/game/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueNumber").value("DEMO-0001"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(put("/api/demo/game/admin/odds/FAN")
                        .contentType("application/json")
                        .content("{\"odds\":3.850}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.odds").value(3.85));

        mockMvc.perform(post("/api/demo/game/bets")
                        .contentType("application/json")
                        .content("{\"ballNumber\":8,\"playType\":\"FAN\",\"parameters\":[2],\"stake\":15.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementStatus").value("PENDING"))
                .andExpect(jsonPath("$.odds").value(3.85));

        mockMvc.perform(post("/api/demo/game/admin/draw")
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balls[7].number").value(18))
                .andExpect(jsonPath("$.bets[0].settlementStatus").value("WIN"))
                .andExpect(jsonPath("$.bets[0].netProfit").value(42.75));

        mockMvc.perform(post("/api/demo/game/admin/draw")
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/bets")
                        .contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidBetAndDrawPayloads() throws Exception {
        mockMvc.perform(post("/api/demo/game/bets")
                        .contentType("application/json")
                        .content("{\"ballNumber\":9,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.00}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/bets")
                        .contentType("application/json")
                        .content("{\"ballNumber\":1,\"playType\":\"FAN\",\"parameters\":[1],\"stake\":10.001}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/demo/game/admin/draw")
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,21]}"))
                .andExpect(status().isBadRequest());
    }
}
