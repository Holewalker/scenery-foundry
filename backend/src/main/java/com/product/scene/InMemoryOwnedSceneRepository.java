package com.product.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryOwnedSceneRepository implements OwnedSceneRepository {
    private final Map<UUID, Project> projects = new HashMap<>();
    private final Map<UUID, List<PreparedAsset>> assets = new HashMap<>();
    private final Map<UUID, List<SceneObject>> scenes = new HashMap<>();

    @Override public void save(Project project) { projects.put(project.id(), project); }
    @Override public Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId) {
        return Optional.ofNullable(projects.get(projectId)).filter(project -> project.ownerId().equals(ownerId));
    }
    public void saveAsset(PreparedAsset asset) { assets.computeIfAbsent(asset.projectId(), key -> new ArrayList<>()).add(asset); }
    @Override public List<PreparedAsset> findAssets(UUID projectId) {
        return assets.getOrDefault(projectId, List.of()).stream().sorted(Comparator.comparing(PreparedAsset::id)).toList();
    }
    @Override public Optional<PreparedAsset> findAsset(UUID projectId, UUID assetId) {
        return findAssets(projectId).stream().filter(asset -> asset.id().equals(assetId)).findFirst();
    }
    @Override public List<SceneObject> findSceneObjects(UUID projectId) {
        return scenes.getOrDefault(projectId, List.of()).stream().sorted(Comparator.comparing(object -> object.id().value())).toList();
    }
    @Override public void replaceScene(UUID projectId, List<SceneObject> objects) { scenes.put(projectId, List.copyOf(objects)); }
}
