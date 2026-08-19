package com.product.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.product.storage.StorageResolver;

class OwnedSceneServiceTest {
    private static final StorageResolver UNUSED_STORAGE = new StorageResolver(Path.of(System.getProperty("java.io.tmpdir")));
    private final UUID ownerA = UUID.randomUUID();
    private final UUID ownerB = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @Test
    void hidesOtherOwnersProjectsAndKeepsOwnersScenesAvailable() {
        var service = new OwnedSceneService(new InMemoryOwnedSceneRepository(), UNUSED_STORAGE);
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
        assertThatThrownBy(() -> new PreparedAsset(assetId, projectId, AssetProcessingStatus.UPLOADED,
            AssetGeometryStatus.VALID_VOLUME, "assets/a.stl", "a".repeat(64)))
            .isInstanceOf(InvalidSceneException.class);
        assertThatThrownBy(() -> new PreparedAsset(assetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "", "not-a-checksum"))
            .isInstanceOf(InvalidSceneException.class);
    }

    @Test
    void rejectsSceneObjectWhenTranslationMmDoesNotMatchMatrixTranslation() {
        var repository = new InMemoryOwnedSceneRepository();
        var service = new OwnedSceneService(repository, UNUSED_STORAGE);
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
    void acceptsReadyAssetsRegardlessOfGeometryStatusAndRejectsNonReadyAssets() {
        var repository = new InMemoryOwnedSceneRepository();
        var service = new OwnedSceneService(repository, UNUSED_STORAGE);
        service.createProject(new Project(projectId, ownerA));
        repository.markAssetReady(ownerA, assetId);

        var invalidVolumeButReady = new SceneDtos.SceneObjectDto(1, assetId, 1,
            new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(ownerA, projectId, new SceneDtos.SceneDto(List.of(invalidVolumeButReady)));
        assertThat(service.loadScene(ownerA, projectId).objects()).hasSize(1);

        var notReadyAssetId = UUID.randomUUID();
        var notReady = new SceneDtos.SceneObjectDto(2, notReadyAssetId, 1,
            new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        assertThatThrownBy(() -> service.replaceScene(ownerA, projectId, new SceneDtos.SceneDto(List.of(notReady))))
            .isInstanceOf(InvalidSceneException.class);
        assertThat(service.loadScene(ownerA, projectId).objects()).hasSize(1);
    }

    @Test
    void hidesOtherOwnersReadyAssetsFromReplaceSceneEvenWhenTheDoubleSharesOneRepositoryInstance() {
        var repository = new InMemoryOwnedSceneRepository();
        var service = new OwnedSceneService(repository, UNUSED_STORAGE);
        var projectIdB = UUID.randomUUID();
        service.createProject(new Project(projectId, ownerA));
        service.createProject(new Project(projectIdB, ownerB));
        repository.saveAsset(new PreparedAsset(assetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "assets/a.stl", "a".repeat(64)));

        var foreignRef = new SceneDtos.SceneObjectDto(1, assetId, 1,
            new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());

        assertThatThrownBy(() -> service.replaceScene(ownerB, projectIdB, new SceneDtos.SceneDto(List.of(foreignRef))))
            .isInstanceOf(InvalidSceneException.class);
    }

    @Test
    void rejectsNullSceneObjectsInsteadOfThrowingNullPointerException() {
        var service = new OwnedSceneService(new InMemoryOwnedSceneRepository(), UNUSED_STORAGE);
        service.createProject(new Project(projectId, ownerA));

        assertThatThrownBy(() -> service.replaceScene(ownerA, projectId, new SceneDtos.SceneDto(null)))
            .isInstanceOf(InvalidSceneException.class);
    }

    @Test
    void readsStoredOriginalBytesAndHidesTraversalOrMissingAssetsAsNotFound(@TempDir Path storageRoot) throws IOException {
        var storage = new StorageResolver(storageRoot);
        var repository = new InMemoryOwnedSceneRepository();
        var service = new OwnedSceneService(repository, storage);
        service.createProject(new Project(projectId, ownerA));

        var storedBytes = "solid cube".getBytes(StandardCharsets.UTF_8);
        var storageKey = storage.allocateKey("assets/" + assetId, "cube.stl");
        var tempSource = Files.createTempFile(storageRoot, "upload", ".tmp");
        Files.write(tempSource, storedBytes);
        storage.publish(tempSource, storageKey);
        repository.saveAsset(new PreparedAsset(assetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, storageKey, "a".repeat(64)));

        assertThat(service.readOriginalStl(ownerA, projectId, assetId)).isEqualTo(storedBytes);

        var traversalAssetId = UUID.randomUUID();
        repository.saveAsset(new PreparedAsset(traversalAssetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "../outside.stl", "b".repeat(64)));
        assertThatThrownBy(() -> service.readOriginalStl(ownerA, projectId, traversalAssetId))
            .isInstanceOf(OwnedResourceNotFoundException.class);

        var missingAssetId = UUID.randomUUID();
        repository.saveAsset(new PreparedAsset(missingAssetId, projectId, AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "assets/does-not-exist.stl", "c".repeat(64)));
        assertThatThrownBy(() -> service.readOriginalStl(ownerA, projectId, missingAssetId))
            .isInstanceOf(OwnedResourceNotFoundException.class);
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
