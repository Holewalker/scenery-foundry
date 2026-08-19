package com.product.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.identity.AuthenticatedUser;
import com.product.scene.AssetGeometryStatus;
import com.product.scene.AssetProcessingStatus;
import com.product.storage.StorageResolver;

@WebMvcTest(AssetController.class)
class AssetControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AssetIntakeService intakeService;
    @MockitoBean JdbcAssetRepository catalogRepository;
    @MockitoBean StorageResolver storageResolver;

    @Test
    void rejectsUnauthenticatedUploadAttempts() throws Exception {
        var file = new MockMultipartFile("file", "cube.stl", "application/octet-stream", "solid cube".getBytes());

        mvc.perform(multipart("/api/assets").file(file).with(csrf())).andExpect(status().isUnauthorized());

        verifyNoInteractions(intakeService);
    }

    @Test
    void acceptsAnAuthenticatedUploadAndReturnsTheAssetAndJobIds() throws Exception {
        var owner = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        when(intakeService.intake(eq(owner), any())).thenReturn(new AssetIntakeResult(assetId, AssetProcessingStatus.UPLOADED, jobId));
        var file = new MockMultipartFile("file", "cube.stl", "application/octet-stream", "solid cube".getBytes());

        mvc.perform(multipart("/api/assets").file(file).with(authentication(authFor(owner))).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.assetId").value(assetId.toString()))
            .andExpect(jsonPath("$.processingStatus").value("UPLOADED"))
            .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void mapsIntakeExceptionsToStableHttpErrorContracts() throws Exception {
        assertMapsToError(new AssetTooLargeException(), "cube.stl", 413, "FILE_TOO_LARGE");
        assertMapsToError(new UnsupportedAssetMediaTypeException(), "malware.exe", 415, "UNSUPPORTED_MEDIA_TYPE");
        assertMapsToError(new IdempotencyConflictException(), "cube.stl", 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void rejectsUnauthenticatedReadsOfTheCatalogSingleAssetOriginalAndPreview() throws Exception {
        var assetId = UUID.randomUUID();

        mvc.perform(get("/api/assets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assets/{id}", assetId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assets/{id}/original", assetId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assets/{id}/preview", assetId)).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalogRepository, storageResolver);
    }

    @Test
    void listReturnsAssetResponseDtosWithNoLeakedOwnerIdOrStorageKeys() throws Exception {
        var owner = UUID.randomUUID();
        var entry = readyEntry(UUID.randomUUID(), owner);
        when(catalogRepository.findCatalogForOwner(owner)).thenReturn(List.of(entry));

        mvc.perform(get("/api/assets").with(authentication(authFor(owner))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(entry.id().toString()))
            .andExpect(jsonPath("$[0].processingStatus").value("READY"))
            .andExpect(jsonPath("$[0].previewAvailable").value(true))
            .andExpect(jsonPath("$[0].ownerId").doesNotExist())
            .andExpect(jsonPath("$[0].originalStorageKey").doesNotExist())
            .andExpect(jsonPath("$[0].previewStorageKey").doesNotExist());
    }

    @Test
    void getReturnsAnAssetResponseDtoWithNoLeakedOwnerIdOrStorageKeysForTheAuthenticatedOwner() throws Exception {
        var owner = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        when(catalogRepository.findByOwnerAndId(owner, assetId)).thenReturn(Optional.of(readyEntry(assetId, owner)));

        mvc.perform(get("/api/assets/{id}", assetId).with(authentication(authFor(owner))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(assetId.toString()))
            .andExpect(jsonPath("$.previewAvailable").value(true))
            .andExpect(jsonPath("$.ownerId").doesNotExist())
            .andExpect(jsonPath("$.originalStorageKey").doesNotExist())
            .andExpect(jsonPath("$.previewStorageKey").doesNotExist());
    }

    @Test
    void getReturnsNotFoundForAForeignOrMissingAsset() throws Exception {
        var owner = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        when(catalogRepository.findByOwnerAndId(owner, assetId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/assets/{id}", assetId).with(authentication(authFor(owner))))
            .andExpect(status().isNotFound());
    }

    @Test
    void originalStreamsTheStoredStlBytesForTheOwningUserAsChunkedWithNoContentLengthAndClosesTheStream() throws Exception {
        var owner = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var entry = readyEntry(assetId, owner);
        when(catalogRepository.findByOwnerAndId(owner, assetId)).thenReturn(Optional.of(entry));
        var tracked = new CloseTrackingInputStream("solid cube".getBytes());
        when(storageResolver.openInputStream(entry.originalStorageKey())).thenReturn(tracked);

        mvc.perform(get("/api/assets/{id}/original", assetId).with(authentication(authFor(owner))))
            .andExpect(status().isOk())
            .andExpect(content().bytes("solid cube".getBytes()))
            .andExpect(header().doesNotExist("Content-Length"));

        assertThat(tracked.closed()).isTrue();
    }

    @Test
    void previewStreamsTheGlbBytesOnlyWhenTheAssetIsReadyAsChunkedWithNoContentLength() throws Exception {
        var owner = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var entry = readyEntry(assetId, owner);
        when(catalogRepository.findByOwnerAndId(owner, assetId)).thenReturn(Optional.of(entry));
        var tracked = new CloseTrackingInputStream("glb bytes".getBytes());
        when(storageResolver.openInputStream(entry.previewStorageKey())).thenReturn(tracked);

        mvc.perform(get("/api/assets/{id}/preview", assetId).with(authentication(authFor(owner))))
            .andExpect(status().isOk())
            .andExpect(content().bytes("glb bytes".getBytes()))
            .andExpect(header().doesNotExist("Content-Length"));

        assertThat(tracked.closed()).isTrue();
    }

    @Test
    void originalReturnsNotFoundForAForeignAssetBeforeEverOpeningAStream() throws Exception {
        var owner = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        when(catalogRepository.findByOwnerAndId(owner, assetId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/assets/{id}/original", assetId).with(authentication(authFor(owner))))
            .andExpect(status().isNotFound());
        verifyNoInteractions(storageResolver);
    }

    @Test
    void previewReturnsNotFoundWhenTheAssetIsNotReadyAndNeverReadsStorage() throws Exception {
        var owner = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var entry = new AssetCatalogEntry(assetId, owner, AssetProcessingStatus.PROCESSING, AssetGeometryStatus.UNKNOWN,
            "assets/x/original.stl", "a".repeat(64), null, null, null);
        when(catalogRepository.findByOwnerAndId(owner, assetId)).thenReturn(Optional.of(entry));

        mvc.perform(get("/api/assets/{id}/preview", assetId).with(authentication(authFor(owner))))
            .andExpect(status().isNotFound());
        verifyNoInteractions(storageResolver);
    }

    private static AssetCatalogEntry readyEntry(UUID assetId, UUID owner) {
        return new AssetCatalogEntry(assetId, owner, AssetProcessingStatus.READY, AssetGeometryStatus.VALID_VOLUME,
            "assets/" + assetId + "/original.stl", "a".repeat(64), "assets/" + assetId + "/preview.glb", 12L, null);
    }

    private void assertMapsToError(RuntimeException thrown, String filename, int httpStatus, String code) throws Exception {
        var owner = UUID.randomUUID();
        when(intakeService.intake(eq(owner), any())).thenThrow(thrown);
        var file = new MockMultipartFile("file", filename, "application/octet-stream", "bytes".getBytes());

        mvc.perform(multipart("/api/assets").file(file).with(authentication(authFor(owner))).with(csrf()))
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.code").value(code));
    }

    /** Proves the response converter closes the stream it was handed, rather than the controller. */
    private static final class CloseTrackingInputStream extends InputStream {
        private final InputStream delegate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        CloseTrackingInputStream(byte[] content) { this.delegate = new ByteArrayInputStream(content); }

        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException { return delegate.read(buffer, offset, length); }
        @Override public void close() throws IOException { closed.set(true); delegate.close(); }

        boolean closed() { return closed.get(); }
    }

    private static UsernamePasswordAuthenticationToken authFor(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }
}
