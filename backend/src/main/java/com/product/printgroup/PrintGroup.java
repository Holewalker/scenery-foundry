package com.product.printgroup;

import java.util.UUID;

/** Name validity (non-blank, <=120 chars) is enforced by the DB CHECK constraint (V6), mapped to 422 by
 * {@code ApiExceptionHandler}'s DataIntegrityViolationException handler — no separate application-level check. */
public record PrintGroup(UUID id, UUID projectId, UUID ownerId, String name) { }
