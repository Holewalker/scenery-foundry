package com.product.geometryjob;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A job claimed via {@code FOR UPDATE SKIP LOCKED} (ADR-0005); {@code claimToken} fences later heartbeat/finalize calls. */
public record ClaimedJob(UUID id, UUID ownerId, UUID subjectId, String payload, UUID claimToken,
                          int attemptCount, OffsetDateTime leaseExpiresAt) { }
