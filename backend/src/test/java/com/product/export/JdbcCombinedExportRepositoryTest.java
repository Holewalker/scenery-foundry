package com.product.export;

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
class JdbcCombinedExportRepositoryTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

    @Autowired JdbcClient jdbc;
    @Autowired JdbcCombinedExportRepository repository;

    @Test
    void findsStatusIncludingDiagnosticsOnlyForTheOwningUser() {
        var owner = insertUser();
        var foreign = insertUser();
        var exportId = UUID.randomUUID();
        insertJob(owner, exportId, "FAILED", null, null, "COMBINED_UNION_FAILED", "union failed", "{\"pieceCount\":2}", false);

        var status = repository.findStatus(owner, exportId).orElseThrow();
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.errorCode()).isEqualTo("COMBINED_UNION_FAILED");
        // jsonb::text round-trips through Postgres's own canonical formatting (a space after ':'), not the
        // literal bytes inserted — assert on the parsed value's presence, not exact byte-for-byte spacing.
        assertThat(status.diagnostics()).contains("\"pieceCount\"").contains("2");

        assertThat(repository.findStatus(foreign, exportId)).isEmpty();
        assertThat(repository.findStatus(owner, UUID.randomUUID())).isEmpty();
    }

    @Test
    void findsArtifactOnlyForACompletedAndProjectedJob() {
        var owner = insertUser();
        var completedProjected = UUID.randomUUID();
        insertJob(owner, completedProjected, "COMPLETED", "exports/" + completedProjected + "/combined.stl", "a".repeat(64), null, null, null, true);

        var artifact = repository.findArtifact(owner, completedProjected).orElseThrow();
        assertThat(artifact.storageKey()).isEqualTo("exports/" + completedProjected + "/combined.stl");
        assertThat(artifact.sha256()).isEqualTo("a".repeat(64));
    }

    @Test
    void neverServesTheArtifactForARunningOrFailedOrUnprojectedJob() {
        var owner = insertUser();
        var running = UUID.randomUUID();
        insertJob(owner, running, "RUNNING", null, null, null, null, null, false);
        var failed = UUID.randomUUID();
        insertJob(owner, failed, "FAILED", null, null, "COMBINED_UNION_FAILED", "union failed", null, true);
        var unprojected = UUID.randomUUID();
        insertJob(owner, unprojected, "COMPLETED", "exports/" + unprojected + "/combined.stl", "b".repeat(64), null, null, null, false);

        assertThat(repository.findArtifact(owner, running)).isEmpty();
        assertThat(repository.findArtifact(owner, failed)).isEmpty();
        assertThat(repository.findArtifact(owner, unprojected)).isEmpty();
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private void insertJob(UUID owner, UUID exportId, String status, String outputKey, String outputSha,
            String errorCode, String errorMessage, String diagnostics, boolean projected) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,output_storage_key,output_sha256,"
                + "error_code,error_message,diagnostics,payload,idempotency_key,projected_at) values "
                + "(:id,:owner,'COMBINED_EXPORT',:subject,:status,:outputKey,:outputSha,:errorCode,:errorMessage,"
                + ":diagnostics::jsonb,'{}'::jsonb,:key,case when :projected::boolean then clock_timestamp() else null end)")
            .param("id", id).param("owner", owner).param("subject", exportId).param("status", status)
            .param("outputKey", outputKey).param("outputSha", outputSha).param("errorCode", errorCode)
            .param("errorMessage", errorMessage).param("diagnostics", diagnostics == null ? "{}" : diagnostics)
            .param("key", "idem-" + id).param("projected", projected).update();
    }
}
