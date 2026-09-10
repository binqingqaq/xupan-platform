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
        jdbcTemplate.update("DELETE FROM demo_balance_ledger");
        jdbcTemplate.update("UPDATE demo_user_account SET balance = 1000.00 WHERE user_code = 'DEMO-USER'");
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

        mockMvc.perform(get("/api/demo/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(985.00));

        mockMvc.perform(post("/api/demo/game/admin/draw")
                        .contentType("application/json")
                        .content("{\"numbers\":[1,2,3,4,5,6,7,18]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balls[7].number").value(18))
                .andExpect(jsonPath("$.bets[0].settlementStatus").value("WIN"))
                .andExpect(jsonPath("$.bets[0].netProfit").value(42.75));

        mockMvc.perform(get("/api/demo/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1042.75));

        mockMvc.perform(get("/api/demo/admin/accounts/DEMO-USER/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationType").value("SETTLEMENT_CREDIT"))
                .andExpect(jsonPath("$[1].operationType").value("BET_DEBIT"));

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

    @Test
    void adjustsDemoBalanceWithAnAuditableLedgerEntry() throws Exception {
        mockMvc.perform(post("/api/demo/admin/accounts/DEMO-USER/balance")
                        .contentType("application/json")
                        .content("{\"amount\":125.50,\"reason\":\"验收初始化\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1125.50));

        mockMvc.perform(get("/api/demo/admin/accounts/DEMO-USER/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationType").value("ADMIN_ADJUST"))
                .andExpect(jsonPath("$[0].amount").value(125.50))
                .andExpect(jsonPath("$[0].reason").value("验收初始化"));
    }
}
