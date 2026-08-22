package com.product.scene;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.product.storage.StorageAccessException;
import com.product.storage.StorageResolver;

@Service
public class OwnedSceneService {
    private static final int MAX_SCENE_OBJECTS = 250;
    private static final double TRANSLATION_EPSILON_MM = 1e-6;
    private final OwnedSceneRepository repository;
    private final StorageResolver storageResolver;
    private final boolean requireVersion;

    /** Convenience for callers that don't care about ADR-0007's transitional flag; behaves as
     * {@code app.scene.require-version=false} (the shipped default for this release). */
    public OwnedSceneService(OwnedSceneRepository repository, StorageResolver storageResolver) {
        this(repository, storageResolver, new SceneProperties(false));
    }

    @Autowired
    public OwnedSceneService(OwnedSceneRepository repository, StorageResolver storageResolver, SceneProperties sceneProperties) {
        this.repository = repository;
        this.storageResolver = storageResolver;
        this.requireVersion = sceneProperties.requireVersion();
    }
    public void createProject(Project project) { repository.save(project); }
    public Project findProject(UUID ownerId, UUID projectId) {
        OwnerScope.requireOwner(ownerId);
        return repository.findProjectByOwner(ownerId, projectId).orElseThrow(OwnedResourceNotFoundException::new);
    }

    public List<PreparedAsset> listAssets(UUID ownerId, UUID projectId) {
        findProject(ownerId, projectId);
        return repository.findAssets(projectId);
    }

    public byte[] readOriginalStl(UUID ownerId, UUID projectId, UUID assetId) {
        findProject(ownerId, projectId);
        var asset = repository.findAsset(projectId, assetId).orElseThrow(OwnedResourceNotFoundException::new);
        return readAssetBytes(asset.storageKey());
    }

    public SceneDtos.SceneDto loadScene(UUID ownerId, UUID projectId) {
        findProject(ownerId, projectId);
        return new SceneDtos.SceneDto(repository.findSceneVersion(projectId),
            repository.findSceneObjects(projectId).stream().map(OwnedSceneService::toDto).toList());
    }

    public SceneDtos.SceneDto replaceScene(UUID ownerId, UUID projectId, SceneDtos.SceneDto scene) {
        findProject(ownerId, projectId); // ownership (404) strictly before the version check (409): ADR-0003
        var objects = scene.objects();
        if (objects == null) throw new InvalidSceneException("scene must include an objects list");
        if (objects.size() > MAX_SCENE_OBJECTS) throw new InvalidSceneException("scene exceeds the maximum object count");
        if (objects.stream().map(SceneDtos.SceneObjectDto::id).distinct().count() != objects.size())
            throw new InvalidSceneException("scene object ids must be unique");
        var readyAssetIds = repository.findReadyAssetIds(ownerId);
        var domainObjects = objects.stream().map(dto -> toDomain(projectId, readyAssetIds, dto)).toList();
        long expectedVersion = resolveExpectedVersion(projectId, scene.version());
        var newVersion = repository.replaceScene(projectId, expectedVersion, domainObjects)
            .orElseThrow(SceneVersionConflictException::new);
        return new SceneDtos.SceneDto(newVersion, domainObjects.stream().map(OwnedSceneService::toDto).toList());
    }

    /** ADR-0007 transitional compatibility: a client that omits {@code version} is either rejected (once
     * {@code require-version} flips on in PR5) or treated as an unchecked write that always matches the
     * version it reads right now — the same "no concurrency check" behavior this feature replaces, kept
     * only for a pre-upgrade client during the release window. */
    private long resolveExpectedVersion(UUID projectId, Long clientVersion) {
        if (clientVersion != null) return clientVersion;
        if (requireVersion) throw new InvalidSceneException("scene version is required");
        return repository.findSceneVersion(projectId);
    }

    private static SceneObject toDomain(UUID projectId, Set<UUID> assetIds, SceneDtos.SceneObjectDto dto) {
        if (dto.matrixContractVersion() != 1 || !assetIds.contains(dto.assetId()))
            throw new InvalidSceneException("scene object references an invalid asset or contract version");
        requireMatchingTranslation(dto.translationMm(), dto.matrixWorldColumnMajor());
        return new SceneObject(SceneObjectId.of(dto.id()), projectId, dto.assetId(),
            SceneTransform.of(dto.matrixWorldColumnMajor(), dto.quaternionXyzw(), dto.scale()), dto.printGroupId(), dto.levelId());
    }

    /** matrixWorldColumnMajor is the canonical transform; translationMm is a client convenience field that
     * must agree with it, so mismatched input is rejected rather than one representation silently winning. */
    private static void requireMatchingTranslation(double[] translationMm, double[] matrix) {
        if (translationMm == null || translationMm.length != 3 || matrix == null || matrix.length != 16) return;
        if (Math.abs(translationMm[0] - matrix[12]) > TRANSLATION_EPSILON_MM
            || Math.abs(translationMm[1] - matrix[13]) > TRANSLATION_EPSILON_MM
            || Math.abs(translationMm[2] - matrix[14]) > TRANSLATION_EPSILON_MM) {
            throw new InvalidSceneException("translationMm must match matrixWorldColumnMajor translation");
        }
    }

    private static SceneDtos.SceneObjectDto toDto(SceneObject object) {
        var transform = object.transform();
        double[] matrix = transform.matrixWorldColumnMajor();
        return new SceneDtos.SceneObjectDto(object.id().value(), object.assetId(), 1,
            new double[] {matrix[12], matrix[13], matrix[14]}, transform.quaternionXyzw(), transform.scale(), matrix,
            object.printGroupId(), object.levelId());
    }

    private byte[] readAssetBytes(String storageKey) {
        try {
            return storageResolver.readBytes(storageKey);
        } catch (StorageAccessException exception) {
            throw new OwnedResourceNotFoundException();
        }
    }
}
