package com.product.scene;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OwnedSceneRepository {
    void save(Project project);
    Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId);
    List<PreparedAsset> findAssets(UUID projectId);
    Optional<PreparedAsset> findAsset(UUID projectId, UUID assetId);
    List<SceneObject> findSceneObjects(UUID projectId);
    /** @return the new {@code scene_version}, or empty when another writer already advanced it past
     * {@code expectedVersion} (ADR-0007) — conflict is data at this port boundary, not an exception. */
    Optional<Long> replaceScene(UUID projectId, long expectedVersion, List<SceneObject> objects);
    long findSceneVersion(UUID projectId);
    /** Owner's asset ids with processing_status=READY, independent of geometry_status (scene-object eligibility). */
    Set<UUID> findReadyAssetIds(UUID ownerId);
}
