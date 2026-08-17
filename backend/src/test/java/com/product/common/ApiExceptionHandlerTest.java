package com.product.common;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.common.web.SecurityConfiguration;
import com.product.identity.AuthenticatedUser;

@WebMvcTest(ThrowingTestController.class)
@Import(SecurityConfiguration.class)
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void mapsMaxUploadSizeExceededTo413WithAStableFileTooLargeCode() throws Exception {
        var user = new AuthenticatedUser(UUID.randomUUID(), "owner@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        mockMvc.perform(post("/api/test/throw-max-upload-size").with(authentication(auth)).with(csrf()))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
