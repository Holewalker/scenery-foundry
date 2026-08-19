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
class LeaseReconcilerTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired LeaseReconciler reconciler;

    @Test
    void requeuesAnExpiredLeaseWithAttemptsRemainingAndFailsItWhenAttemptsAreExhausted() {
        var owner = insertUser();
        var requeueable = insertExpiredRunningJob(owner, 1, 3);
        var exhausted = insertExpiredRunningJob(owner, 3, 3);

        var updated = reconciler.reconcile();

        assertThat(updated).isEqualTo(2);
        assertThat(status(requeueable)).isEqualTo("RETRY_WAIT");
        assertThat(claimToken(requeueable)).isNull();
        assertThat(status(exhausted)).isEqualTo("FAILED");
        assertThat(jdbc.sql("select error_code from geometry_jobs where id=:id").param("id", exhausted).query(String.class).single())
            .isEqualTo("LEASE_EXPIRED_ATTEMPTS_EXHAUSTED");
    }

    @Test
    void doesNotTouchARunningJobWhoseLeaseIsStillActive() {
        var owner = insertUser();
        var active = insertRunningJob(owner, 1, 3, 120);

        reconciler.reconcile();

        assertThat(status(active)).isEqualTo("RUNNING");
    }

    private String status(UUID jobId) {
        return jdbc.sql("select status from geometry_jobs where id=:id").param("id", jobId).query(String.class).single();
    }

    private UUID claimToken(UUID jobId) {
        return jdbc.sql("select claim_token from geometry_jobs where id=:id").param("id", jobId).query(UUID.class).optional().orElse(null);
    }

    private UUID insertExpiredRunningJob(UUID owner, int attemptCount, int maxAttempts) {
        return insertRunningJob(owner, attemptCount, maxAttempts, -30);
    }

    private UUID insertRunningJob(UUID owner, int attemptCount, int maxAttempts, int leaseOffsetSeconds) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,attempt_count,max_attempts,claim_token,"
                + "worker_id,lease_expires_at,payload,idempotency_key) values "
                + "(:id,:owner,'ASSET_PROCESSING',:owner,'RUNNING',:attempts,:maxAttempts,gen_random_uuid(),'worker-1',"
                + "clock_timestamp() + make_interval(secs => :offset),'{}'::jsonb,:key)")
            .param("id", id).param("owner", owner).param("attempts", attemptCount).param("maxAttempts", maxAttempts)
            .param("offset", leaseOffsetSeconds).param("key", "idem-" + id).update();
        return id;
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }
}
