package com.product.geometryjob;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
