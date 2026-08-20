package com.product.printgroup;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrintGroupRepository {
    void save(PrintGroup group);
    List<PrintGroup> findByProject(UUID projectId);
    Optional<PrintGroup> findByOwnerAndId(UUID ownerId, UUID id);
    void deleteByOwnerAndId(UUID ownerId, UUID id);
    boolean projectExistsForOwner(UUID ownerId, UUID projectId);
}
