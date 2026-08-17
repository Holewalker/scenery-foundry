package com.product.geometryjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.product.scene.OwnedResourceNotFoundException;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JobServiceTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired JobRepository repository;
    @Autowired JobService service;

    @Test
    void heartbeatAndFinalizeSucceedForALiveClaimButAStaleTokenAffectsZeroRowsAndLeavesTheJobUnchanged() {
        var owner = insertUser();
        insertJob(owner);
        var claim = repository.claim("ASSET_PROCESSING", "worker-1", Duration.ofSeconds(120)).orElseThrow();
        var staleToken = UUID.randomUUID();

        assertThat(service.heartbeat(claim.id(), staleToken, Duration.ofSeconds(120))).isFalse();
        assertThat(service.heartbeat(claim.id(), claim.claimToken(), Duration.ofSeconds(120))).isTrue();

        assertThat(service.finalizeCompleted(claim.id(), staleToken, "assets/x/preview.glb", "sha", "{}")).isFalse();
        assertThat(status(claim.id())).isEqualTo("RUNNING");

        assertThat(service.finalizeCompleted(claim.id(), claim.claimToken(), "assets/x/preview.glb", "sha",
            "{\"geometryStatus\":\"VALID_VOLUME\"}")).isTrue();
        assertThat(status(claim.id())).isEqualTo("COMPLETED");
    }

    @Test
    void finalizeFailedRecordsAnErrorCodeOnlyForALiveClaim() {
        var owner = insertUser();
        insertJob(owner);
        var claim = repository.claim("ASSET_PROCESSING", "worker-1", Duration.ofSeconds(120)).orElseThrow();

        assertThat(service.finalizeFailed(claim.id(), UUID.randomUUID(), "MANIFOLD_STATUS_UNKNOWN", "boom", null)).isFalse();
        assertThat(service.finalizeFailed(claim.id(), claim.claimToken(), "MANIFOLD_STATUS_UNKNOWN", "boom", null)).isTrue();

        assertThat(status(claim.id())).isEqualTo("FAILED");
        assertThat(jdbc.sql("select error_code from geometry_jobs where id=:id").param("id", claim.id()).query(String.class).single())
            .isEqualTo("MANIFOLD_STATUS_UNKNOWN");
    }

    @Test
    void hidesForeignOwnersJobStatusButReturnsTheOwnersOwnJobStatus() {
        var ownerA = insertUser();
        var ownerB = insertUser();
        var jobId = insertJob(ownerA);

        assertThatThrownBy(() -> service.getStatus(ownerB, jobId)).isInstanceOf(OwnedResourceNotFoundException.class);
        assertThat(service.getStatus(ownerA, jobId).status()).isEqualTo("PENDING");
    }

    private String status(UUID jobId) {
        return jdbc.sql("select status from geometry_jobs where id=:id").param("id", jobId).query(String.class).single();
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private UUID insertJob(UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,'ASSET_PROCESSING',:owner,'PENDING','{}'::jsonb,:key)")
            .param("id", id).param("owner", owner).param("key", "idem-" + id).update();
        return id;
    }
}
