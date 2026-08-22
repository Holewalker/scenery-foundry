package com.product.scene;

/** Raised when a scene PUT's expected {@code scene_version} no longer matches the value stored on
 * {@code projects} (ADR-0007): another writer already advanced it since the client last loaded the scene.
 * Mapped to 409 {@code SCENE_VERSION_CONFLICT} by {@link com.product.common.ApiExceptionHandler}, mirroring
 * {@code IdempotencyConflictException}'s 409 mapping rather than carrying its own {@code @ResponseStatus}. */
public final class SceneVersionConflictException extends RuntimeException {
    public SceneVersionConflictException() { super("Scene was modified by another writer since it was last loaded"); }
}
