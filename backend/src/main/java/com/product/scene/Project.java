package com.product.scene;

import java.util.UUID;

public record Project(UUID id, UUID ownerId) {
    public Project {
        if (id == null || ownerId == null) throw new InvalidSceneException("project identity is required");
    }
}
