package com.product.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CsrfController.class)
@Import(SecurityConfiguration.class)
class CsrfControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesSessionBackedCsrfTokenWithoutCachingIt() throws Exception {
        mockMvc.perform(get("/api/csrf"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty());
    }
}
