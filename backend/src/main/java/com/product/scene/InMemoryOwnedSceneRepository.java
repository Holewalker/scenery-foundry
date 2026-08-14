package com.product.scene;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryOwnedSceneRepository implements OwnedSceneRepository {
    private final Map<UUID, Project> projects = new HashMap<>();
    @Override public void save(Project project) { projects.put(project.id(), project); }
    @Override public Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId) {
        return Optional.ofNullable(projects.get(projectId)).filter(project -> project.ownerId().equals(ownerId));
    }
}
