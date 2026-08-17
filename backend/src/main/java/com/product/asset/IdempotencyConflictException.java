package com.product.asset;

/** Raised when a stored job's idempotency key matches the upload's content hash, but the asset it
 * refers to was stored under a different hash — a data-integrity conflict, not an ordinary repeat upload. */
public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() { super("Uploaded content conflicts with a previously stored idempotency key"); }
}
