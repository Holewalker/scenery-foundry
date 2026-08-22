package com.product.level;

import java.util.UUID;

public final class LevelDtos {
    private LevelDtos() { }

    public record CreateLevelDto(String name) { }

    public record LevelDto(UUID id, UUID projectId, String name) { }
}
