package com.product.level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Structural twin of {@code com.product.printgroup.PrintGroupRepository}. Levels have zero export semantics. */
public interface LevelRepository {
    void save(Level level);
    List<Level> findByProject(UUID projectId);
    Optional<Level> findByOwnerAndId(UUID ownerId, UUID id);
    void deleteByOwnerAndId(UUID ownerId, UUID id);
    boolean projectExistsForOwner(UUID ownerId, UUID projectId);
}
