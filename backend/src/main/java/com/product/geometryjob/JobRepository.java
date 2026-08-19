package com.product.geometryjob;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Claims the next eligible job via {@code FOR UPDATE SKIP LOCKED} (ADR-0005); concurrent claimers never see the same row. */
@Repository
public class JobRepository {
    private final JdbcClient jdbc;
    public JobRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<ClaimedJob> claim(String jobType, String workerId, Duration lease) {
        return jdbc.sql("""
                WITH c AS (
                  SELECT id FROM geometry_jobs
                  WHERE job_type = :jobType AND status IN ('PENDING','RETRY_WAIT') AND available_at <= clock_timestamp()
                  ORDER BY priority DESC, available_at ASC, created_at ASC, id ASC
                  FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE geometry_jobs j
                SET status = 'RUNNING', attempt_count = attempt_count + 1, claim_token = gen_random_uuid(),
                    worker_id = :workerId, lease_expires_at = clock_timestamp() + make_interval(secs => :leaseSeconds),
                    started_at = COALESCE(started_at, clock_timestamp())
                FROM c WHERE j.id = c.id
                RETURNING j.id, j.owner_id, j.subject_id, j.payload::text AS payload, j.claim_token, j.attempt_count, j.lease_expires_at
                """)
            .param("jobType", jobType).param("workerId", workerId).param("leaseSeconds", lease.toSeconds())
            .query((row, index) -> new ClaimedJob(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getObject("subject_id", UUID.class), row.getString("payload"), row.getObject("claim_token", UUID.class),
                row.getInt("attempt_count"), row.getObject("lease_expires_at", OffsetDateTime.class)))
            .optional();
    }
}
