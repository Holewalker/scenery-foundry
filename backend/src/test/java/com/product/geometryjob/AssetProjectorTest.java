package com.product.geometryjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AssetProjectorTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired AssetProjector projector;

    @Test
    void projectsACompletedJobOntoReadyWithGeometryStatusPreviewKeyAndTriangleCount() {
        var owner = insertUser();
        var assetId = insertAsset(owner);
        var jobId = insertTerminalJob(owner, assetId, "COMPLETED", "assets/" + assetId + "/preview.glb", "sha-1",
            "{\"geometryStatus\":\"VALID_VOLUME\",\"triangleCount\":120}", null);

        var projected = projector.project();

        assertThat(projected).isEqualTo(1);
        assertThat(processingStatus(assetId)).isEqualTo("READY");
        assertThat(geometryStatus(assetId)).isEqualTo("VALID_VOLUME");
        assertThat(jdbc.sql("select preview_storage_key,triangle_count from assets where id=:id").param("id", assetId)
            .query((row, index) -> row.getString("preview_storage_key") + ":" + row.getLong("triangle_count")).single())
            .isEqualTo("assets/" + assetId + "/preview.glb:120");
        assertThat(isProjected(jobId)).isTrue();
    }

    @Test
    void projectsAFailedJobOntoFailedWithoutTouchingGeometryStatusAndRecordsTheErrorCode() {
        var owner = insertUser();
        var assetId = insertAsset(owner);
        insertTerminalJob(owner, assetId, "FAILED", null, null, "{}", "MANIFOLD_STATUS_UNKNOWN");

        projector.project();

        assertThat(processingStatus(assetId)).isEqualTo("FAILED");
        assertThat(geometryStatus(assetId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("select error_code from assets where id=:id").param("id", assetId).query(String.class).single())
            .isEqualTo("MANIFOLD_STATUS_UNKNOWN");
    }

    @Test
    void neverReprojectsAJobThatWasAlreadyProjected() {
        var owner = insertUser();
        var assetId = insertAsset(owner);
        var jobId = insertTerminalJob(owner, assetId, "COMPLETED", "assets/" + assetId + "/preview.glb", "sha-1",
            "{\"geometryStatus\":\"VALID_VOLUME\"}", null);
        jdbc.sql("update geometry_jobs set projected_at = clock_timestamp() where id=:id").param("id", jobId).update();

        var projected = projector.project();

        assertThat(projected).isZero();
        assertThat(processingStatus(assetId)).isEqualTo("UPLOADED");
    }

    @Test
    void rollsBackTheAssetUpdateWhenTheSameJobsSecondUpdateFails() {
        createFailingProjectionTrigger();
        var owner = insertUser();
        var assetId = insertAsset(owner);
        insertTerminalJob(owner, assetId, "COMPLETED", "assets/" + assetId + "/preview.glb", "sha-1",
            "{\"geometryStatus\":\"VALID_VOLUME\"}", "FORCE_ROLLBACK_TEST");

        assertThatThrownBy(() -> projector.project()).isInstanceOf(DataAccessException.class);

        assertThat(processingStatus(assetId)).isEqualTo("UPLOADED");
        assertThat(geometryStatus(assetId)).isEqualTo("UNKNOWN");
    }

    /**
     * Regression for P1 (Print Preparation Phase 4): {@link AssetProjector} must scope its selection to
     * {@code job_type = 'ASSET_PROCESSING'} so a terminal {@code COMBINED_EXPORT} row is never selected or
     * stamped {@code projected_at} by this projector. The real {@code job_type} CHECK only admits
     * {@code ASSET_PROCESSING} until the V6 migration (PR2), so this test widens the constraint test-locally
     * to prove the filter independently of that migration.
     */
    @Test
    void projectsOnlyAssetProcessingJobsAndIgnoresCombinedExportJobs() {
        widenJobTypeCheckForCombinedExport();
        try {
            var owner = insertUser();
            var assetId = insertAsset(owner);
            var assetJobId = insertTerminalJob(owner, assetId, "COMPLETED", "assets/" + assetId + "/preview.glb",
                "sha-1", "{\"geometryStatus\":\"VALID_VOLUME\"}", null);
            var exportJobId = insertTerminalCombinedExportJob(owner);

            var projected = projector.project();

            assertThat(projected).isEqualTo(1);
            assertThat(isProjected(assetJobId)).isTrue();
            assertThat(isProjected(exportJobId)).isFalse();
        } finally {
            restoreJobTypeCheck();
        }
    }

    private void widenJobTypeCheckForCombinedExport() {
        jdbc.sql("ALTER TABLE geometry_jobs DROP CONSTRAINT geometry_jobs_job_type_check").update();
        jdbc.sql("ALTER TABLE geometry_jobs ADD CONSTRAINT geometry_jobs_job_type_check "
                + "CHECK (job_type IN ('ASSET_PROCESSING','COMBINED_EXPORT'))").update();
    }

    /**
     * Restores the original {@code ASSET_PROCESSING}-only CHECK so the widened constraint from
     * {@link #widenJobTypeCheckForCombinedExport()} never leaks into other test methods sharing this
     * container-backed database (CodeRabbit finding on PR1). Must delete this test's own
     * {@code COMBINED_EXPORT} row first, or re-adding the narrower CHECK is rejected as violated by
     * that row.
     */
    private void restoreJobTypeCheck() {
        jdbc.sql("DELETE FROM geometry_jobs WHERE job_type = 'COMBINED_EXPORT'").update();
        jdbc.sql("ALTER TABLE geometry_jobs DROP CONSTRAINT geometry_jobs_job_type_check").update();
        jdbc.sql("ALTER TABLE geometry_jobs ADD CONSTRAINT geometry_jobs_job_type_check "
                + "CHECK (job_type IN ('ASSET_PROCESSING'))").update();
    }

    private UUID insertTerminalCombinedExportJob(UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,completed_at,payload,idempotency_key) "
                + "values (:id,:owner,'COMBINED_EXPORT',:subject,'COMPLETED',clock_timestamp(),'{}'::jsonb,:key)")
            .param("id", id).param("owner", owner).param("subject", UUID.randomUUID()).param("key", "idem-" + id).update();
        return id;
    }

    /**
     * Test-only trigger that raises whenever {@code geometry_jobs.projected_at} is updated on a row whose
     * {@code error_code} is the sentinel {@code FORCE_ROLLBACK_TEST}. Fires only on the projector's SECOND
     * statement (the first statement never touches {@code geometry_jobs}), proving the two writes share one
     * atomic transaction rather than each committing independently.
     */
    private void createFailingProjectionTrigger() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_fail_second_projection_update() RETURNS trigger AS $$
                BEGIN
                    IF NEW.error_code = 'FORCE_ROLLBACK_TEST' THEN
                        RAISE EXCEPTION 'forced failure for atomicity test';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_second_projection_update_trigger ON geometry_jobs").update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_second_projection_update_trigger
                BEFORE UPDATE ON geometry_jobs
                FOR EACH ROW EXECUTE FUNCTION test_fail_second_projection_update()
                """).update();
    }

    private String processingStatus(UUID assetId) {
        return jdbc.sql("select processing_status from assets where id=:id").param("id", assetId).query(String.class).single();
    }

    private String geometryStatus(UUID assetId) {
        return jdbc.sql("select geometry_status from assets where id=:id").param("id", assetId).query(String.class).single();
    }

    private boolean isProjected(UUID jobId) {
        return jdbc.sql("select projected_at is not null from geometry_jobs where id=:id").param("id", jobId).query(Boolean.class).single();
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private UUID insertAsset(UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,'UPLOADED','UNKNOWN','assets/original.stl','" + "a".repeat(64) + "')")
            .param("id", id).param("owner", owner).update();
        return id;
    }

    private UUID insertTerminalJob(UUID owner, UUID assetId, String status, String outputKey, String outputSha, String diagnostics, String errorCode) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,completed_at,output_storage_key,output_sha256,"
                + "diagnostics,error_code,payload,idempotency_key) values "
                + "(:id,:owner,'ASSET_PROCESSING',:subject,:status,clock_timestamp(),:outputKey,:outputSha,:diagnostics::jsonb,"
                + ":errorCode,'{}'::jsonb,:key)")
            .param("id", id).param("owner", owner).param("subject", assetId).param("status", status)
            .param("outputKey", outputKey).param("outputSha", outputSha).param("diagnostics", diagnostics)
            .param("errorCode", errorCode).param("key", "idem-" + id).update();
        return id;
    }
}
