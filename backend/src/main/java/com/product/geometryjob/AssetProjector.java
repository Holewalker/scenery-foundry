package com.product.geometryjob;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Promotes terminal {@code geometry_jobs} outcomes onto {@code assets} (ADR-0002 D3): the worker's DB grant is
 * SELECT/UPDATE on {@code geometry_jobs} only, so it can never mutate {@code assets} itself — only this Spring-owned
 * projector does. Expects a {@code diagnostics} payload with a top-level {@code geometryStatus} key (and optional
 * {@code triangleCount}); the PR5 worker report format must conform to this contract. {@link GeometryJobScheduler}
 * is the periodic trigger; kept separate so {@link #project()} stays directly, deterministically callable from tests.
 *
 * <p>{@link #projectOne(UUID)} wraps its two writes in an explicit {@link TransactionTemplate} boundary rather than
 * {@code @Transactional}: {@link #project()} invokes it via {@code this::projectOne}, a self-invocation that bypasses
 * the Spring AOP proxy and silently turns a proxy-based {@code @Transactional} into a no-op. The boundary is per job,
 * not per batch — one job's failure must not roll back jobs already committed earlier in the same {@link #project()} call.
 */
@Component
public class AssetProjector {
    private static final int BATCH_SIZE = 20;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    public AssetProjector(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int project() {
        var jobIds = jdbc.sql("SELECT id FROM geometry_jobs WHERE status IN ('COMPLETED','FAILED') AND projected_at IS NULL "
                + "AND job_type = 'ASSET_PROCESSING' ORDER BY completed_at ASC LIMIT :batch")
            .param("batch", BATCH_SIZE).query(UUID.class).list();
        jobIds.forEach(this::projectOne);
        return jobIds.size();
    }

    void projectOne(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.sql("""
                    UPDATE assets a
                    SET processing_status = CASE WHEN j.status = 'COMPLETED' THEN 'READY' ELSE 'FAILED' END,
                        geometry_status = CASE WHEN j.status = 'COMPLETED'
                            THEN COALESCE(j.diagnostics->>'geometryStatus', 'UNKNOWN') ELSE a.geometry_status END,
                        preview_storage_key = j.output_storage_key, preview_sha256 = j.output_sha256,
                        triangle_count = COALESCE((j.diagnostics->>'triangleCount')::bigint, a.triangle_count),
                        error_code = j.error_code, diagnostic_report = j.diagnostics
                    FROM geometry_jobs j WHERE a.id = j.subject_id AND j.id = :jobId
                    """).param("jobId", jobId).update();
            jdbc.sql("UPDATE geometry_jobs SET projected_at = clock_timestamp() WHERE id = :jobId").param("jobId", jobId).update();
        });
    }
}
