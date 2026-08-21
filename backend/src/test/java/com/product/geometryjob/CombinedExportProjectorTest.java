package com.product.geometryjob;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CombinedExportProjectorTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    static Path storageRoot;

    @Autowired JdbcClient jdbc;
    @Autowired CombinedExportProjector projector;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storageRoot = Files.createTempDirectory("combined-export-projector-test");
        registry.add("SCENE_DATA_ROOT", storageRoot::toString);
    }

    @Test
    void stampsProjectedAtOnACompletedJobWhenTheArtifactChecksumMatches() {
        var owner = insertUser();
        var key = "exports/" + UUID.randomUUID() + "/combined.stl";
        var sha256 = writeArtifact(key, "combined-stl-bytes");
        var jobId = insertTerminalJob(owner, "COMPLETED", key, sha256, null);

        var projected = projector.project();

        assertThat(projected).isEqualTo(1);
        assertThat(status(jobId)).isEqualTo("COMPLETED");
        assertThat(errorCode(jobId)).isNull();
        assertThat(isProjected(jobId)).isTrue();
    }

    @Test
    void flipsToFailedArtifactMissingWhenTheChecksumMismatchesOrTheArtifactIsUnreadable() {
        var owner = insertUser();
        var mismatchKey = "exports/" + UUID.randomUUID() + "/combined.stl";
        writeArtifact(mismatchKey, "combined-stl-bytes");
        var mismatchJobId = insertTerminalJob(owner, "COMPLETED", mismatchKey, "0".repeat(64), null);
        var missingJobId = insertTerminalJob(owner, "COMPLETED", "exports/" + UUID.randomUUID() + "/never-published.stl", "1".repeat(64), null);

        projector.project();

        for (UUID jobId : List.of(mismatchJobId, missingJobId)) {
            assertThat(status(jobId)).isEqualTo("FAILED");
            assertThat(errorCode(jobId)).isEqualTo("ARTIFACT_MISSING");
            assertThat(isProjected(jobId)).isTrue();
        }
    }

    @Test
    void stampsProjectedAtOnAFailedJobWithoutArtifactVerification() {
        var owner = insertUser();
        var jobId = insertTerminalJob(owner, "FAILED", null, null, "COMBINED_UNION_FAILED");

        projector.project();

        assertThat(status(jobId)).isEqualTo("FAILED");
        assertThat(errorCode(jobId)).isEqualTo("COMBINED_UNION_FAILED");
        assertThat(isProjected(jobId)).isTrue();
    }

    @Test
    void neverReprojectsAJobThatWasAlreadyProjected() {
        var owner = insertUser();
        var key = "exports/" + UUID.randomUUID() + "/combined.stl";
        var sha256 = writeArtifact(key, "combined-stl-bytes");
        var jobId = insertTerminalJob(owner, "COMPLETED", key, sha256, null);
        jdbc.sql("update geometry_jobs set projected_at = clock_timestamp() where id=:id").param("id", jobId).update();

        var projected = projector.project();

        assertThat(projected).isZero();
        assertThat(status(jobId)).isEqualTo("COMPLETED");
        assertThat(errorCode(jobId)).isNull();
    }

    /** CodeRabbit finding on PR5 (#46): project() selects candidate ids outside any transaction, so an
     * overlapping invocation could select an id another invocation already finished with. Calling
     * projectOne() directly on an already-projected row (bypassing project()'s own WHERE filter) proves the
     * inner FOR UPDATE + projected_at IS NULL re-check is what actually guards this, not just the outer
     * selection query. Uses a mismatched sha256 so a bug here would be visible as a flip to FAILED. */
    @Test
    void projectOneSkipsARowThatWasProjectedBetweenSelectionAndItsOwnTransaction() {
        var owner = insertUser();
        var key = "exports/" + UUID.randomUUID() + "/combined.stl";
        writeArtifact(key, "combined-stl-bytes");
        var jobId = insertTerminalJob(owner, "COMPLETED", key, "0".repeat(64), null); // sha mismatch: would fail verification if re-checked
        jdbc.sql("update geometry_jobs set projected_at = clock_timestamp() where id=:id").param("id", jobId).update();

        projector.projectOne(jobId);

        assertThat(status(jobId)).isEqualTo("COMPLETED");
        assertThat(errorCode(jobId)).isNull();
    }

    private String writeArtifact(String key, String contents) {
        try {
            var path = storageRoot.resolve(key);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
            return sha256Hex(contents.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String status(UUID jobId) {
        return jdbc.sql("select status from geometry_jobs where id=:id").param("id", jobId).query(String.class).single();
    }

    private String errorCode(UUID jobId) {
        return jdbc.sql("select error_code from geometry_jobs where id=:id").param("id", jobId).query(String.class).optional().orElse(null);
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

    private UUID insertTerminalJob(UUID owner, String status, String outputKey, String outputSha, String errorCode) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,completed_at,output_storage_key,output_sha256,"
                + "error_code,payload,idempotency_key) values "
                + "(:id,:owner,'COMBINED_EXPORT',:subject,:status,clock_timestamp(),:outputKey,:outputSha,"
                + ":errorCode,'{}'::jsonb,:key)")
            .param("id", id).param("owner", owner).param("subject", UUID.randomUUID()).param("status", status)
            .param("outputKey", outputKey).param("outputSha", outputSha)
            .param("errorCode", errorCode).param("key", "idem-" + id).update();
        return id;
    }
}
