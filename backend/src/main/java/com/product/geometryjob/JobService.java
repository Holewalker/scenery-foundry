package com.product.geometryjob;

import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.product.scene.OwnedResourceNotFoundException;

/** Conditional lifecycle transitions fenced by {@code id + status='RUNNING' + claim_token} (ADR-0005); a stale token affects 0 rows. */
@Service
public class JobService {
    private final JdbcClient jdbc;
    public JobService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean heartbeat(UUID jobId, UUID claimToken, Duration lease) {
        var rows = jdbc.sql("UPDATE geometry_jobs SET lease_expires_at = clock_timestamp() + make_interval(secs => :leaseSeconds) "
                + "WHERE id = :id AND status = 'RUNNING' AND claim_token = :token AND lease_expires_at > clock_timestamp()")
            .param("id", jobId).param("token", claimToken).param("leaseSeconds", lease.toSeconds()).update();
        return rows == 1;
    }

    public boolean finalizeCompleted(UUID jobId, UUID claimToken, String outputStorageKey, String outputSha256, String diagnosticsJson) {
        var rows = jdbc.sql("UPDATE geometry_jobs SET status = 'COMPLETED', completed_at = clock_timestamp(), "
                + "output_storage_key = :key, output_sha256 = :sha256, diagnostics = :diagnostics::jsonb "
                + "WHERE id = :id AND status = 'RUNNING' AND claim_token = :token AND lease_expires_at > clock_timestamp()")
            .param("id", jobId).param("token", claimToken).param("key", outputStorageKey)
            .param("sha256", outputSha256).param("diagnostics", diagnosticsJson == null ? "{}" : diagnosticsJson).update();
        return rows == 1;
    }

    public boolean finalizeFailed(UUID jobId, UUID claimToken, String errorCode, String errorMessage, String diagnosticsJson) {
        var rows = jdbc.sql("UPDATE geometry_jobs SET status = 'FAILED', completed_at = clock_timestamp(), "
                + "error_code = :errorCode, error_message = :errorMessage, diagnostics = :diagnostics::jsonb "
                + "WHERE id = :id AND status = 'RUNNING' AND claim_token = :token AND lease_expires_at > clock_timestamp()")
            .param("id", jobId).param("token", claimToken).param("errorCode", errorCode)
            .param("errorMessage", errorMessage).param("diagnostics", diagnosticsJson == null ? "{}" : diagnosticsJson).update();
        return rows == 1;
    }

    public JobStatus getStatus(UUID ownerId, UUID jobId) {
        return jdbc.sql("SELECT id, owner_id, status, error_code FROM geometry_jobs WHERE id = :id AND owner_id = :owner")
            .param("id", jobId).param("owner", ownerId)
            .query((row, index) -> new JobStatus(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getString("status"), row.getString("error_code")))
            .optional().orElseThrow(OwnedResourceNotFoundException::new);
    }
}
