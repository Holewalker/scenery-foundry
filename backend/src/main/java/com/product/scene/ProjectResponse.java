package com.product.scene;

import java.util.UUID;

/** Non-leaking API shape for {@code /api/projects*}: no {@code ownerId} (always the caller). */
public record ProjectResponse(UUID id, String name) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.id(), project.name());
    }
}
