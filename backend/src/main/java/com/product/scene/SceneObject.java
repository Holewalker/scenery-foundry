package com.product.scene;

import java.util.UUID;

public record SceneObject(SceneObjectId id, UUID projectId, UUID assetId, SceneTransform transform, UUID printGroupId, UUID levelId) {
    public SceneObject {
        if (id == null || projectId == null || assetId == null || transform == null) throw new InvalidSceneException("scene object is incomplete");
    }

    /** Convenience constructor for callers that don't assign a print group or level (both nullable, D6). */
    public SceneObject(SceneObjectId id, UUID projectId, UUID assetId, SceneTransform transform) {
        this(id, projectId, assetId, transform, null, null);
    }
}
