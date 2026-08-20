package com.product.level;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.product.scene.OwnedResourceNotFoundException;

@Service
public class LevelService {
    private final LevelRepository repository;
    public LevelService(LevelRepository repository) { this.repository = repository; }

    public Level create(UUID ownerId, UUID projectId, String name) {
        requireProject(ownerId, projectId);
        if (name == null || name.isBlank()) {
            throw new InvalidLevelException("name must not be blank");
        }
        var level = new Level(UUID.randomUUID(), projectId, ownerId, name);
        repository.save(level);
        return level;
    }

    public List<Level> list(UUID ownerId, UUID projectId) {
        requireProject(ownerId, projectId);
        return repository.findByProject(projectId);
    }

    public Level find(UUID ownerId, UUID levelId) {
        return repository.findByOwnerAndId(ownerId, levelId).orElseThrow(OwnedResourceNotFoundException::new);
    }

    public void delete(UUID ownerId, UUID levelId) {
        find(ownerId, levelId);
        repository.deleteByOwnerAndId(ownerId, levelId);
    }

    private void requireProject(UUID ownerId, UUID projectId) {
        if (!repository.projectExistsForOwner(ownerId, projectId)) throw new OwnedResourceNotFoundException();
    }
}
