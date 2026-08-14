package com.product.scene;

import java.util.Optional;
import java.util.UUID;

public interface OwnedSceneRepository {
    void save(Project project);
    Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId);
}
