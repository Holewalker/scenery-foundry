package com.product.geometryjob;

import java.util.UUID;

/** Owner-scoped job status read model; never resolvable for a caller that does not own the job. */
public record JobStatus(UUID id, UUID ownerId, String status, String errorCode) { }
