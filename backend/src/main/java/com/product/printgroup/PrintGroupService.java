package com.product.printgroup;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.product.scene.OwnedResourceNotFoundException;

@Service
public class PrintGroupService {
    private final PrintGroupRepository repository;
    public PrintGroupService(PrintGroupRepository repository) { this.repository = repository; }

    public PrintGroup create(UUID ownerId, UUID projectId, String name) {
        requireProject(ownerId, projectId);
        var group = new PrintGroup(UUID.randomUUID(), projectId, ownerId, name);
        repository.save(group);
        return group;
    }

    public List<PrintGroup> list(UUID ownerId, UUID projectId) {
        requireProject(ownerId, projectId);
        return repository.findByProject(projectId);
    }

    public PrintGroup find(UUID ownerId, UUID groupId) {
        return repository.findByOwnerAndId(ownerId, groupId).orElseThrow(OwnedResourceNotFoundException::new);
    }

    public void delete(UUID ownerId, UUID groupId) {
        find(ownerId, groupId);
        repository.deleteByOwnerAndId(ownerId, groupId);
    }

    private void requireProject(UUID ownerId, UUID projectId) {
        if (!repository.projectExistsForOwner(ownerId, projectId)) throw new OwnedResourceNotFoundException();
    }
}
