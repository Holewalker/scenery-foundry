package com.product.geometryjob;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reclaims {@code RUNNING} jobs whose lease expired (worker crash/timeout). Spring-owned per ADR-0005 D4 — the
 * worker never self-recovers. This bean holds only the reconciliation logic; {@link GeometryJobScheduler} is the
 * (conditionally-enabled) periodic trigger, kept separate so {@link #reconcile()} stays directly, deterministically
 * callable from tests without a background timer racing against them.
 */
@Component
public class LeaseReconciler {
    private final JdbcClient jdbc;
    public LeaseReconciler(JdbcClient jdbc) { this.jdbc = jdbc; }

    public int reconcile() {
        return jdbc.sql("""
                UPDATE geometry_jobs
                SET status = CASE WHEN attempt_count >= max_attempts THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                    error_code = CASE WHEN attempt_count >= max_attempts THEN 'LEASE_EXPIRED_ATTEMPTS_EXHAUSTED' ELSE NULL END,
                    claim_token = NULL, worker_id = NULL, lease_expires_at = NULL,
                    available_at = clock_timestamp()
                        + LEAST(interval '15 minutes', interval '15 seconds' * power(2, attempt_count - 1)) * random()
                WHERE status = 'RUNNING' AND lease_expires_at <= clock_timestamp()
                """)
            .update();
    }
}
