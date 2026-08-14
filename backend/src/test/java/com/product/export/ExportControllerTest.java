package com.product.export;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.identity.AuthenticatedUser;

@WebMvcTest(ExportController.class)
class ExportControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcCaptureProjectionService captures;
    @MockitoBean JdbcExportRepository exports;

    @Test
    void capturesOnlyForTheAuthenticatedOwner() throws Exception {
        UUID owner = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(captures.capture(owner, project)).thenReturn(export);

        mvc.perform(post("/api/projects/{project}/combined-exports", project).with(authentication(user(owner))).with(csrf()))
            .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/combined-exports/" + export));
    }

    @Test
    void returnsStoredCanonicalBytesOnlyForTheOwner() throws Exception {
        UUID owner = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(exports.findSnapshot(owner, export)).thenReturn(java.util.Optional.of(new ExportSnapshot(new byte[] {1, 2}, "a".repeat(64), "scenery-foundry.snapshot-jcs/v1")));

        mvc.perform(get("/api/combined-exports/{export}/snapshot", export).with(authentication(user(owner))))
            .andExpect(status().isOk()).andExpect(header().string("X-Snapshot-SHA256", "a".repeat(64)));
    }

    @Test
    void hidesMissingOrForeignExportsWithNotFound() throws Exception {
        UUID owner = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(exports.findSnapshot(owner, export)).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/combined-exports/{export}/snapshot", export).with(authentication(user(owner))))
            .andExpect(status().isNotFound());
    }

    private UsernamePasswordAuthenticationToken user(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }
}

