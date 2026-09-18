package com.product.scene;

import java.util.UUID;

public record Project(UUID id, UUID ownerId, String name) {
    public Project {
        if (id == null || ownerId == null) throw new InvalidSceneException("project identity is required");
    }

    /** Legacy two-argument form for callers that don't assign a name (nullable, additive V8 column). */
    public Project(UUID id, UUID ownerId) {
        this(id, ownerId, null);
    }
}
