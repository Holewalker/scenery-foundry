package com.product.piecesexport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.product.identity.AuthenticatedUser;
import com.product.storage.StorageResolver;

/**
 * Synchronous pieces-export download (D5): {@link PiecesExportService#prepare} performs every validation and
 * cap check BEFORE this method streams a single byte, so a rejected request never opens a ZIP entry. Chunked
 * ({@code StreamingResponseBody} emits no {@code Content-Length}), matching {@code AssetController#original}'s
 * convention for streamed binary downloads.
 */
@RestController
public final class PiecesExportController {
    private final PiecesExportService service;
    private final StorageResolver storageResolver;

    public PiecesExportController(PiecesExportService service, StorageResolver storageResolver) {
        this.service = service;
        this.storageResolver = storageResolver;
    }

    @GetMapping(value = "/api/print-groups/{id}/pieces-export", produces = "application/zip")
    ResponseEntity<StreamingResponseBody> export(@PathVariable UUID id, Authentication authentication) {
        var plan = service.prepare(AuthenticatedUser.from(authentication).userId(), id);
        StreamingResponseBody body = output -> writeZip(plan, output);
        return ResponseEntity.ok().body(body);
    }

    private void writeZip(PiecesExportPlan plan, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(plan.manifestJson());
            zip.closeEntry();
            for (PiecesExportPlan.PieceFile piece : plan.pieces()) {
                zip.putNextEntry(new ZipEntry(piece.fileName()));
                zip.write(storageResolver.readBytes(piece.originalStorageKey()));
                zip.closeEntry();
            }
        }
    }
}
