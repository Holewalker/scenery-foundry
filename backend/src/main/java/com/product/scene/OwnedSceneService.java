package com.product.scene;

import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class OwnedSceneService {
    private final OwnedSceneRepository repository;
    public OwnedSceneService(OwnedSceneRepository repository) { this.repository = repository; }
    public void createProject(Project project) { repository.save(project); }
    public Project findProject(UUID ownerId, UUID projectId) {
        OwnerScope.requireOwner(ownerId);
        return repository.findProjectByOwner(ownerId, projectId).orElseThrow(OwnedResourceNotFoundException::new);
    }
}
