package com.product.scene;

import java.util.UUID;

public record SceneObject(SceneObjectId id, UUID projectId, UUID assetId, SceneTransform transform) {
    public SceneObject {
        if (id == null || projectId == null || assetId == null || transform == null) throw new InvalidSceneException("scene object is incomplete");
    }
}
