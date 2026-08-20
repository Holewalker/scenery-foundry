package com.product.piecesexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.identity.AuthenticatedUser;
import com.product.scene.OwnedResourceNotFoundException;
import com.product.storage.StorageResolver;

@WebMvcTest(PiecesExportController.class)
class PiecesExportControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PiecesExportService service;
    @MockitoBean StorageResolver storageResolver;

    @Test
    void returnsNotFoundForAForeignOrNonexistentPrintGroup() throws Exception {
        UUID owner = UUID.randomUUID(); UUID group = UUID.randomUUID();
        when(service.prepare(owner, group)).thenThrow(new OwnedResourceNotFoundException());

        mvc.perform(get("/api/print-groups/{id}/pieces-export", group).with(authentication(user(owner))))
            .andExpect(status().isNotFound());
    }

    @Test
    void streamsManifestFirstThenOneDeflateEntryPerDistinctAssetWithNoContentLength() throws Exception {
        UUID owner = UUID.randomUUID(); UUID group = UUID.randomUUID();
        String fileNameA = "pieces/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.stl";
        String fileNameB = "pieces/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.stl";
        var plan = new PiecesExportPlan("{\"contract\":\"scenery-foundry.pieces-export/v1\"}".getBytes(StandardCharsets.UTF_8),
            List.of(new PiecesExportPlan.PieceFile(fileNameA, "assets/a/original.stl"),
                new PiecesExportPlan.PieceFile(fileNameB, "assets/b/original.stl")));
        when(service.prepare(owner, group)).thenReturn(plan);
        when(storageResolver.readBytes("assets/a/original.stl")).thenReturn("solid A".getBytes(StandardCharsets.UTF_8));
        when(storageResolver.readBytes("assets/b/original.stl")).thenReturn("solid B".getBytes(StandardCharsets.UTF_8));

        var asyncResult = mvc.perform(get("/api/print-groups/{id}/pieces-export", group).with(authentication(user(owner))))
            .andExpect(request().asyncStarted())
            .andReturn();
        var result = mvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Content-Length"))
            .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();

        List<String> entryNames = new ArrayList<>();
        List<Integer> entryMethods = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                entryMethods.add(entry.getMethod());
            }
        }

        assertThat(entryNames).containsExactly("manifest.json", fileNameA, fileNameB);
        assertThat(entryMethods.subList(1, entryMethods.size())).containsOnly(ZipEntry.DEFLATED);
    }

    private UsernamePasswordAuthenticationToken user(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }
}
