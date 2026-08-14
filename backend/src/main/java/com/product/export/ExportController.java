package com.product.export;

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

@RestController
public final class ExportController {
    private final JdbcCaptureProjectionService captures;
    private final JdbcExportRepository exports;

    public ExportController(JdbcCaptureProjectionService captures, JdbcExportRepository exports) {
        this.captures = captures;
        this.exports = exports;
    }

    @PostMapping("/api/projects/{projectId}/combined-exports")
    ResponseEntity<Map<String, String>> capture(@PathVariable UUID projectId, Authentication authentication) {
        UUID exportId = captures.capture(AuthenticatedUser.from(authentication).userId(), projectId);
        return ResponseEntity.created(URI.create("/api/combined-exports/" + exportId)).body(Map.of("id", exportId.toString()));
    }

    @GetMapping(value = "/api/combined-exports/{exportId}/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> snapshot(@PathVariable UUID exportId, Authentication authentication) {
        return exports.findSnapshot(AuthenticatedUser.from(authentication).userId(), exportId)
            .map(value -> ResponseEntity.ok().header("X-Snapshot-SHA256", value.sha256())
                .header("X-Canonicalizer-Contract", value.canonicalizerContract()).body(value.canonicalBytes()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
