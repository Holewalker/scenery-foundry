package com.product.geometryjob;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
class JobRepositoryTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired JobRepository repository;

    @Test
    void onlyOneConcurrentClaimerWinsTheEligibleJobViaSkipLocked() throws Exception {
        var owner = insertUser();
        insertJob(owner, "PENDING", 0, 0);

        Callable<Optional<ClaimedJob>> attempt = () -> repository.claim("ASSET_PROCESSING", "worker-" + Thread.currentThread().threadId(), Duration.ofSeconds(120));
        var executor = Executors.newFixedThreadPool(2);
        var futures = executor.invokeAll(List.of(attempt, attempt));
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        var winners = futures.stream().map(this::result).filter(Optional::isPresent).toList();

        assertThat(winners).hasSize(1);
        assertThat(jdbc.sql("select status,attempt_count from geometry_jobs where owner_id=:owner").param("owner", owner)
            .query((row, index) -> row.getString("status") + ":" + row.getInt("attempt_count")).single()).isEqualTo("RUNNING:1");
    }

    @Test
    void claimSkipsFutureAvailabilityAndOrdersEligibleJobsByPriorityThenFifo() {
        var owner = insertUser();
        var future = insertJob(owner, "PENDING", 0, 60);
        var lowPriority = insertJob(owner, "PENDING", 0, 0);
        var highPriority = insertJob(owner, "RETRY_WAIT", 5, 0);

        var first = repository.claim("ASSET_PROCESSING", "worker-1", Duration.ofSeconds(120));
        var second = repository.claim("ASSET_PROCESSING", "worker-1", Duration.ofSeconds(120));
        var third = repository.claim("ASSET_PROCESSING", "worker-1", Duration.ofSeconds(120));

        assertThat(first).isPresent();
        assertThat(first.get().id()).isEqualTo(highPriority);
        assertThat(second).isPresent();
        assertThat(second.get().id()).isEqualTo(lowPriority);
        assertThat(third).isEmpty();
        assertThat(jdbc.sql("select status from geometry_jobs where id=:id").param("id", future).query(String.class).single())
            .isEqualTo("PENDING");
    }

    private Optional<ClaimedJob> result(java.util.concurrent.Future<Optional<ClaimedJob>> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private UUID insertJob(UUID owner, String status, int priority, int availableInSeconds) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,priority,available_at,payload,idempotency_key) "
                + "values (:id,:owner,'ASSET_PROCESSING',:owner,:status,:priority,clock_timestamp() + make_interval(secs => :delay),'{}'::jsonb,:key)")
            .param("id", id).param("owner", owner).param("status", status).param("priority", priority)
            .param("delay", availableInSeconds).param("key", "idem-" + id).update();
        return id;
    }
}
