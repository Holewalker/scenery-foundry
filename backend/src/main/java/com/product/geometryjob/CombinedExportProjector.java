package com.product.geometryjob;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.product.storage.StorageAccessException;
import com.product.storage.StorageResolver;

/**
 * Promotes terminal {@code COMBINED_EXPORT} {@code geometry_jobs} outcomes: stamps {@code projected_at} once a
 * row reaches a validated terminal state. Sibling of {@link AssetProjector} (Phase 4 design: "two sibling
 * projectors, not one dispatching projector") — its selection query scopes to {@code job_type = 'COMBINED_EXPORT'}
 * so the two never cross-contaminate.
 *
 * <p>Unlike {@link AssetProjector}, a {@code COMPLETED} row here is never trusted at face value: ADR-0002 makes
 * PostgreSQL+Spring the authority that confirms the published artifact — "{@code COMPLETED} solo se confirma
 * después de verificar el checksum y enlazar la storage key ganadora." Before stamping {@code projected_at} on a
 * {@code COMPLETED} row, this projector re-reads the published artifact and compares its sha256 to
 * {@code output_sha256}; a mismatch or unreadable artifact flips the row to {@code FAILED}/{@code ARTIFACT_MISSING}
 * instead (still stamped, so it is never reprocessed). A {@code FAILED} row needs no artifact check.
 *
 * <p>{@link #projectOne(UUID)} uses an explicit {@link TransactionTemplate} boundary rather than
 * {@code @Transactional} for the same self-invocation-bypasses-the-proxy reason documented on
 * {@link AssetProjector}. The boundary is per job, not per batch.
 */
@Component
public class CombinedExportProjector {
    private static final int BATCH_SIZE = 20;
    private final JdbcClient jdbc;
    private final StorageResolver storageResolver;
    private final TransactionTemplate transactionTemplate;

    public CombinedExportProjector(JdbcClient jdbc, StorageResolver storageResolver, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.storageResolver = storageResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int project() {
        var jobIds = jdbc.sql("SELECT id FROM geometry_jobs WHERE status IN ('COMPLETED','FAILED') AND projected_at IS NULL "
                + "AND job_type = 'COMBINED_EXPORT' ORDER BY completed_at ASC LIMIT :batch")
            .param("batch", BATCH_SIZE).query(UUID.class).list();
        jobIds.forEach(this::projectOne);
        return jobIds.size();
    }

    void projectOne(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            // Lock the row and re-check projected_at IS NULL inside the transaction (CodeRabbit finding on
            // PR5, #46): project() selects candidate ids outside any transaction, so two overlapping
            // invocations could otherwise both select the same job — after the first stamps projected_at,
            // the second would still re-verify the artifact and, on a transient read failure, could flip an
            // already-validated COMPLETED row to FAILED. FOR UPDATE + a missing row (already projected by a
            // concurrent invocation) means this one has nothing left to do.
            var pendingJob = jdbc.sql("SELECT status, output_storage_key, output_sha256 FROM geometry_jobs "
                    + "WHERE id = :id AND projected_at IS NULL FOR UPDATE")
                .param("id", jobId)
                .query((row, index) -> new TerminalJob(row.getString("status"), row.getString("output_storage_key"), row.getString("output_sha256")))
                .optional();
            if (pendingJob.isEmpty()) return;
            var job = pendingJob.get();
            if ("COMPLETED".equals(job.status()) && !artifactVerified(job)) {
                jdbc.sql("UPDATE geometry_jobs SET status = 'FAILED', error_code = 'ARTIFACT_MISSING', "
                        + "projected_at = clock_timestamp() WHERE id = :jobId").param("jobId", jobId).update();
                return;
            }
            jdbc.sql("UPDATE geometry_jobs SET projected_at = clock_timestamp() WHERE id = :jobId").param("jobId", jobId).update();
        });
    }

    private boolean artifactVerified(TerminalJob job) {
        if (job.outputStorageKey() == null || job.outputSha256() == null) return false;
        // Streamed, not buffered whole (Codex finding on PR5, #46): a valid export near the 5,000,000-triangle
        // ADR-0006 ceiling is roughly 250 MB, and this runs unattended on every scheduler tick.
        try (InputStream in = storageResolver.openInputStream(job.outputStorageKey())) {
            return job.outputSha256().equals(sha256Hex(in));
        } catch (StorageAccessException | IOException missing) {
            return false;
        }
    }

    private static String sha256Hex(InputStream in) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var digestStream = new DigestInputStream(in, digest)) {
                digestStream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private record TerminalJob(String status, String outputStorageKey, String outputSha256) { }
}
