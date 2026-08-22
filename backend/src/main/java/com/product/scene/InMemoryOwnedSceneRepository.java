package com.product.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class InMemoryOwnedSceneRepository implements OwnedSceneRepository {
    private final Map<UUID, Project> projects = new HashMap<>();
    private final Map<UUID, List<PreparedAsset>> assets = new HashMap<>();
    private final Map<UUID, List<SceneObject>> scenes = new HashMap<>();
    private final Map<UUID, Long> sceneVersions = new HashMap<>();
    /** assetId -> owning owner, so {@link #findReadyAssetIds} never leaks a ready asset across owners. */
    private final Map<UUID, UUID> readyAssetOwners = new HashMap<>();

    @Override public void save(Project project) { projects.put(project.id(), project); }
    @Override public Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId) {
        return Optional.ofNullable(projects.get(projectId)).filter(project -> project.ownerId().equals(ownerId));
    }
    public void saveAsset(PreparedAsset asset) {
        assets.computeIfAbsent(asset.projectId(), key -> new ArrayList<>()).add(asset);
        readyAssetOwners.put(asset.id(), ownerOfProject(asset.projectId()));
    }
    /** Marks READY without PreparedAsset's VALID_VOLUME invariant — models DB eligibility regardless of geometry_status. */
    public void markAssetReady(UUID ownerId, UUID assetId) { readyAssetOwners.put(assetId, ownerId); }
    @Override public List<PreparedAsset> findAssets(UUID projectId) {
        return assets.getOrDefault(projectId, List.of()).stream().sorted(Comparator.comparing(PreparedAsset::id)).toList();
    }
    @Override public Optional<PreparedAsset> findAsset(UUID projectId, UUID assetId) {
        return findAssets(projectId).stream().filter(asset -> asset.id().equals(assetId)).findFirst();
    }
    @Override public List<SceneObject> findSceneObjects(UUID projectId) {
        return scenes.getOrDefault(projectId, List.of()).stream().sorted(Comparator.comparing(object -> object.id().value())).toList();
    }
    @Override public Optional<Long> replaceScene(UUID projectId, long expectedVersion, List<SceneObject> objects) {
        long current = sceneVersions.getOrDefault(projectId, 0L);
        if (current != expectedVersion) return Optional.empty();
        long next = current + 1;
        sceneVersions.put(projectId, next);
        scenes.put(projectId, List.copyOf(objects));
        return Optional.of(next);
    }
    @Override public long findSceneVersion(UUID projectId) { return sceneVersions.getOrDefault(projectId, 0L); }
    @Override public Set<UUID> findReadyAssetIds(UUID ownerId) {
        return readyAssetOwners.entrySet().stream().filter(entry -> entry.getValue().equals(ownerId))
            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private UUID ownerOfProject(UUID projectId) {
        var project = projects.get(projectId);
        if (project == null) throw new IllegalStateException("saveAsset requires the project to already be created: " + projectId);
        return project.ownerId();
    }
}
