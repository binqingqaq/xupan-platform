package com.xupan.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SpaControllerTest {

    private final MockMvc mockMvc = standaloneSetup(new SpaController()).build();

    @Test
    void displayRoutesServeTheLocalReplicaDocument() throws Exception {
        mockMvc.perform(get("/display/mobile"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/display/mobile-replica/index.html"));
        mockMvc.perform(get("/display"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/display/mobile-replica/index.html"));
    }

    @Test
    void playerRoutesStillServeTheVueApplication() throws Exception {
        mockMvc.perform(get("/room"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void hby019RoutesServeTheVueApplication() throws Exception {
        mockMvc.perform(get("/hby019-demo/console"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/hby019-demo/login"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/hby019-demo/showdetails"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void machineLoginLinksServeTheVueApplicationForDirectAccessAndRefresh() throws Exception {
        mockMvc.perform(get("/console/login/ahy005"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
