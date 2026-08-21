package com.product.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.product.identity.AuthenticatedUser;
import com.product.storage.StorageResolver;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

@RestController
public final class ExportController {
    private final JdbcCaptureProjectionService captures;
    private final JdbcExportRepository exports;
    private final JdbcCombinedExportRepository combinedExports;
    private final StorageResolver storageResolver;
    private final JsonFactory jsonFactory = new JsonFactory();

    public ExportController(JdbcCaptureProjectionService captures, JdbcExportRepository exports,
            JdbcCombinedExportRepository combinedExports, StorageResolver storageResolver) {
        this.captures = captures;
        this.exports = exports;
        this.combinedExports = combinedExports;
        this.storageResolver = storageResolver;
    }

    @PostMapping("/api/print-groups/{printGroupId}/combined-exports")
    ResponseEntity<Map<String, String>> capture(@PathVariable UUID printGroupId, Authentication authentication) {
        UUID exportId = captures.capture(AuthenticatedUser.from(authentication).userId(), printGroupId);
        return ResponseEntity.created(URI.create("/api/combined-exports/" + exportId)).body(Map.of("id", exportId.toString()));
    }

    @GetMapping(value = "/api/combined-exports/{exportId}/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> snapshot(@PathVariable UUID exportId, Authentication authentication) {
        return exports.findSnapshot(AuthenticatedUser.from(authentication).userId(), exportId)
            .map(value -> ResponseEntity.ok().header("X-Snapshot-SHA256", value.sha256())
                .header("X-Canonicalizer-Contract", value.canonicalizerContract()).body(value.canonicalBytes()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/api/combined-exports/{exportId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> status(@PathVariable UUID exportId, Authentication authentication) {
        return combinedExports.findStatus(AuthenticatedUser.from(authentication).userId(), exportId)
            .map(value -> ResponseEntity.ok(statusJson(value)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Never serves output for anything but a validated COMPLETED job (PRD: "never mark a Combined Export
     * valid without final validation") — {@link JdbcCombinedExportRepository#findArtifact} already enforces
     * {@code status='COMPLETED' AND projected_at IS NOT NULL}; a RUNNING/FAILED/un-projected job is 404 here,
     * never the raw job-status read.
     */
    @GetMapping("/api/combined-exports/{exportId}/artifact")
    ResponseEntity<byte[]> artifact(@PathVariable UUID exportId, Authentication authentication) {
        return combinedExports.findArtifact(AuthenticatedUser.from(authentication).userId(), exportId)
            .map(value -> ResponseEntity.ok().header("X-Artifact-SHA256", value.sha256())
                .body(storageResolver.readBytes(value.storageKey())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private byte[] statusJson(CombinedExportStatus value) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (JsonGenerator json = jsonFactory.createGenerator(output)) {
                json.writeStartObject();
                json.writeName("status");
                json.writeString(value.status());
                json.writeName("errorCode");
                if (value.errorCode() == null) json.writeNull(); else json.writeString(value.errorCode());
                json.writeName("errorMessage");
                if (value.errorMessage() == null) json.writeNull(); else json.writeString(value.errorMessage());
                json.writeName("diagnostics");
                json.writeRawValue(value.diagnostics() == null ? "{}" : value.diagnostics());
                json.writeEndObject();
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("combined-export status serialization failed", exception);
        }
    }
}
