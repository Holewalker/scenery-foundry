package com.product.asset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.identity.AuthenticatedUser;
import com.product.scene.AssetProcessingStatus;

@WebMvcTest(AssetController.class)
class AssetControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AssetIntakeService intakeService;

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

    private void assertMapsToError(RuntimeException thrown, String filename, int httpStatus, String code) throws Exception {
        var owner = UUID.randomUUID();
        when(intakeService.intake(eq(owner), any())).thenThrow(thrown);
        var file = new MockMultipartFile("file", filename, "application/octet-stream", "bytes".getBytes());

        mvc.perform(multipart("/api/assets").file(file).with(authentication(authFor(owner))).with(csrf()))
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.code").value(code));
    }

    private static UsernamePasswordAuthenticationToken authFor(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }
}
