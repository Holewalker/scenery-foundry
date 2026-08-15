package com.product.scene;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnedSceneRepository {
    void save(Project project);
    Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId);
    List<PreparedAsset> findAssets(UUID projectId);
    Optional<PreparedAsset> findAsset(UUID projectId, UUID assetId);
    List<SceneObject> findSceneObjects(UUID projectId);
    void replaceScene(UUID projectId, List<SceneObject> objects);
}
