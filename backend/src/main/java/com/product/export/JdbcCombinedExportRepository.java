package com.product.export;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Owner-scoped read of a COMBINED_EXPORT job's client-visible state, joined on {@code geometry_jobs.subject_id}
 * (ADR-0002: the worker never writes {@code combined_exports}/{@code export_snapshots}, so status/artifact
 * visibility is entirely a {@code geometry_jobs} read).
 */
@Repository
public class JdbcCombinedExportRepository {
    private final JdbcClient jdbc;

    public JdbcCombinedExportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<CombinedExportStatus> findStatus(UUID ownerId, UUID exportId) {
        return jdbc.sql("select status, error_code, error_message, diagnostics::text as diagnostics from geometry_jobs "
                + "where subject_id = :export and owner_id = :owner and job_type = 'COMBINED_EXPORT'")
            .param("export", exportId).param("owner", ownerId)
            .query((row, index) -> new CombinedExportStatus(row.getString("status"), row.getString("error_code"),
                row.getString("error_message"), row.getString("diagnostics")))
            .optional();
    }

    /** Never returns a row for anything but a projector-verified, terminally COMPLETED job (PRD: "never mark
     * a Combined Export valid without final validation") — {@code projected_at IS NOT NULL} is load-bearing. */
    public Optional<CombinedExportArtifact> findArtifact(UUID ownerId, UUID exportId) {
        return jdbc.sql("select output_storage_key, output_sha256 from geometry_jobs "
                + "where subject_id = :export and owner_id = :owner and job_type = 'COMBINED_EXPORT' "
                + "and status = 'COMPLETED' and projected_at is not null")
            .param("export", exportId).param("owner", ownerId)
            .query((row, index) -> new CombinedExportArtifact(row.getString("output_storage_key"), row.getString("output_sha256")))
            .optional();
    }
}
