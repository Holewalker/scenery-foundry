package com.product.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class InMemoryOwnedSceneRepositoryTest {
    @Test
    void findSceneObjectsUsesTheSameIdOrderingAsJdbcRepository() {
        var repository = new InMemoryOwnedSceneRepository();
        var owner = UUID.randomUUID();
        var project = UUID.randomUUID();
        repository.save(new Project(project, owner, "Test"));
        repository.replaceSceneUnchecked(project, java.util.List.of(object(20, project), object(3, project), object(11, project)));

        assertThat(repository.findSceneObjects(project)).extracting(scene -> scene.id().value()).containsExactly(3L, 11L, 20L);
    }

    private static SceneObject object(long id, UUID project) {
        return new SceneObject(SceneObjectId.of(id), project, UUID.randomUUID(),
            SceneTransform.of(new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
                new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}));
    }
}
