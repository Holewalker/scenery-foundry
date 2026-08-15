package com.product.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OwnedSceneServiceTest {
    private final UUID ownerA = UUID.randomUUID();
    private final UUID ownerB = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @Test
    void hidesOtherOwnersProjectsAndKeepsOwnersScenesAvailable() {
        var service = new OwnedSceneService(new InMemoryOwnedSceneRepository());
        service.createProject(new Project(projectId, ownerA));

        assertThat(service.findProject(ownerA, projectId)).isEqualTo(new Project(projectId, ownerA));
        assertThatThrownBy(() -> service.findProject(ownerB, projectId))
            .isInstanceOf(OwnedResourceNotFoundException.class);
    }

    @Test
    void acceptsSafeSceneObjectIdBoundariesAndRejectsValuesOutsideThem() {
        assertThat(SceneObjectId.of(1).value()).isEqualTo(1);
        assertThat(SceneObjectId.of(9_007_199_254_740_991L).value()).isEqualTo(9_007_199_254_740_991L);

        assertThatThrownBy(() -> SceneObjectId.of(0)).isInstanceOf(InvalidSceneException.class);
        assertThatThrownBy(() -> SceneObjectId.of(9_007_199_254_740_992L)).isInstanceOf(InvalidSceneException.class);
    }

    @Test
    void acceptsOnlyReadyValidAssetsWithStorageKeysAndChecksums() {
        var valid = new PreparedAsset(assetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "assets/a.stl", "a".repeat(64));

        assertThat(valid).isNotNull();
        assertThatThrownBy(() -> new PreparedAsset(assetId, projectId, AssetProcessingStatus.PENDING,
            AssetGeometryStatus.VALID_VOLUME, "assets/a.stl", "a".repeat(64)))
            .isInstanceOf(InvalidSceneException.class);
        assertThatThrownBy(() -> new PreparedAsset(assetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "", "not-a-checksum"))
            .isInstanceOf(InvalidSceneException.class);
    }

    @Test
    void rejectsSceneObjectWhenTranslationMmDoesNotMatchMatrixTranslation() {
        var repository = new InMemoryOwnedSceneRepository();
        var service = new OwnedSceneService(repository);
        service.createProject(new Project(projectId, ownerA));
        repository.saveAsset(new PreparedAsset(assetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "assets/a.stl", "a".repeat(64)));

        var mismatched = new SceneDtos.SceneObjectDto(1, assetId, 1,
            new double[] {999, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());

        assertThatThrownBy(() -> service.replaceScene(ownerA, projectId, new SceneDtos.SceneDto(List.of(mismatched))))
            .isInstanceOf(InvalidSceneException.class);

        var consistent = new SceneDtos.SceneObjectDto(1, assetId, 1,
            new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(ownerA, projectId, new SceneDtos.SceneDto(List.of(consistent)));
        assertThat(service.loadScene(ownerA, projectId).objects()).hasSize(1);
    }

    @Test
    void rejectsNullSceneObjectsInsteadOfThrowingNullPointerException() {
        var service = new OwnedSceneService(new InMemoryOwnedSceneRepository());
        service.createProject(new Project(projectId, ownerA));

        assertThatThrownBy(() -> service.replaceScene(ownerA, projectId, new SceneDtos.SceneDto(null)))
            .isInstanceOf(InvalidSceneException.class);
    }

    @Test
    void rejectsNonAffineNonUnitQuaternionAndNonPositiveScaleTransforms() {
        assertThatThrownBy(() -> SceneTransform.of(identityWith(15, 2), new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}))
            .isInstanceOf(InvalidSceneException.class);
        assertThatThrownBy(() -> SceneTransform.of(identity(), new double[] {0, 0, 0, 2}, new double[] {1, 1, 1}))
            .isInstanceOf(InvalidSceneException.class);
        assertThatThrownBy(() -> SceneTransform.of(identity(), new double[] {0, 0, 0, 1}, new double[] {1, 0, 1}))
            .isInstanceOf(InvalidSceneException.class);
    }

    private static double[] identity() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] identityWith(int index, double value) {
        var matrix = identity();
        matrix[index] = value;
        return matrix;
    }
}
