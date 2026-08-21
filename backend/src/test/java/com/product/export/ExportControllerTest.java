package com.product.export;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.identity.AuthenticatedUser;
import com.product.storage.StorageResolver;

@WebMvcTest(ExportController.class)
class ExportControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcCaptureProjectionService captures;
    @MockitoBean JdbcExportRepository exports;
    @MockitoBean JdbcCombinedExportRepository combinedExports;
    @MockitoBean StorageResolver storageResolver;

    @Test
    void capturesOnlyForTheAuthenticatedOwner() throws Exception {
        UUID owner = UUID.randomUUID(); UUID printGroupId = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(captures.capture(owner, printGroupId)).thenReturn(export);

        mvc.perform(post("/api/print-groups/{printGroupId}/combined-exports", printGroupId).with(authentication(user(owner))).with(csrf()))
            .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/combined-exports/" + export));
    }

    @Test
    void hidesAForeignOrNonexistentPrintGroupAsNotFoundOnCapture() throws Exception {
        UUID owner = UUID.randomUUID(); UUID printGroupId = UUID.randomUUID();
        when(captures.capture(owner, printGroupId)).thenThrow(new com.product.scene.OwnedResourceNotFoundException());

        mvc.perform(post("/api/print-groups/{printGroupId}/combined-exports", printGroupId).with(authentication(user(owner))).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void returnsStoredCanonicalBytesOnlyForTheOwner() throws Exception {
        UUID owner = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(exports.findSnapshot(owner, export)).thenReturn(Optional.of(new ExportSnapshot(new byte[] {1, 2}, "a".repeat(64), "scenery-foundry.snapshot-jcs/v1")));

        mvc.perform(get("/api/combined-exports/{export}/snapshot", export).with(authentication(user(owner))))
            .andExpect(status().isOk()).andExpect(header().string("X-Snapshot-SHA256", "a".repeat(64)));
    }

    @Test
    void hidesMissingOrForeignExportsWithNotFound() throws Exception {
        UUID owner = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(exports.findSnapshot(owner, export)).thenReturn(Optional.empty());

        mvc.perform(get("/api/combined-exports/{export}/snapshot", export).with(authentication(user(owner))))
            .andExpect(status().isNotFound());
    }

    @Test
    void reportsStatusIncludingDiagnosticsOnFailureAndPlainStatusWhileRunning() throws Exception {
        UUID owner = UUID.randomUUID(); UUID failedExport = UUID.randomUUID(); UUID runningExport = UUID.randomUUID();
        when(combinedExports.findStatus(owner, failedExport)).thenReturn(Optional.of(
            new CombinedExportStatus("FAILED", "COMBINED_UNION_FAILED", "union failed", "{\"pieceCount\":2}")));
        when(combinedExports.findStatus(owner, runningExport)).thenReturn(Optional.of(new CombinedExportStatus("RUNNING", null, null, null)));

        mvc.perform(get("/api/combined-exports/{export}/status", failedExport).with(authentication(user(owner))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"FAILED\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"pieceCount\":2")));
        mvc.perform(get("/api/combined-exports/{export}/status", runningExport).with(authentication(user(owner))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"RUNNING\"")));
    }

    @Test
    void hidesMissingOrForeignJobStatusWithNotFound() throws Exception {
        UUID owner = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(combinedExports.findStatus(owner, export)).thenReturn(Optional.empty());

        mvc.perform(get("/api/combined-exports/{export}/status", export).with(authentication(user(owner))))
            .andExpect(status().isNotFound());
    }

    @Test
    void downloadsTheArtifactOnlyWhenTheRepositoryReportsAValidatedCompletedJob() throws Exception {
        UUID owner = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(combinedExports.findArtifact(owner, export)).thenReturn(Optional.of(new CombinedExportArtifact("exports/" + export + "/combined.stl", "b".repeat(64))));
        when(storageResolver.readBytes("exports/" + export + "/combined.stl")).thenReturn(new byte[] {9, 9});

        mvc.perform(get("/api/combined-exports/{export}/artifact", export).with(authentication(user(owner))))
            .andExpect(status().isOk()).andExpect(header().string("X-Artifact-SHA256", "b".repeat(64)));
    }

    @Test
    void neverServesTheArtifactForARunningOrFailedOrUnprojectedJob() throws Exception {
        UUID owner = UUID.randomUUID(); UUID export = UUID.randomUUID();
        when(combinedExports.findArtifact(owner, export)).thenReturn(Optional.empty());

        mvc.perform(get("/api/combined-exports/{export}/artifact", export).with(authentication(user(owner))))
            .andExpect(status().isNotFound());
    }

    private UsernamePasswordAuthenticationToken user(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }
}
