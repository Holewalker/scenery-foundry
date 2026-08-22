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

    /** Codex PR #52 finding 1: version and objects come from {@link OwnedSceneRepository#findScene} — one
     * atomic read — so a client that saves back exactly what it just GET-ed can never see an immediate
     * false {@link SceneVersionConflictException} caused by the GET itself pairing a stale version with
     * objects a concurrent writer had already committed. */
    public SceneDtos.SceneDto loadScene(UUID ownerId, UUID projectId) {
        findProject(ownerId, projectId);
        var snapshot = repository.findScene(projectId);
        return new SceneDtos.SceneDto(snapshot.version(), snapshot.objects().stream().map(OwnedSceneService::toDto).toList());
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
        long newVersion = writeScene(projectId, scene.version(), domainObjects);
        return new SceneDtos.SceneDto(newVersion, domainObjects.stream().map(OwnedSceneService::toDto).toList());
    }

    /** ADR-0007 transitional compatibility: a client that supplies {@code version} gets the checked,
     * conflict-detecting write. A client that omits it is either rejected (once {@code require-version}
     * flips on in PR5) or gets a genuinely unchecked, last-writer-wins write (Codex PR #52 finding 2): the
     * repository advances {@code scene_version} unconditionally, never by comparing against a value read in
     * an earlier separate step — so two concurrent omitted-version writers never race into a false 409. */
    private long writeScene(UUID projectId, Long clientVersion, List<SceneObject> domainObjects) {
        if (clientVersion != null)
            return repository.replaceScene(projectId, clientVersion, domainObjects).orElseThrow(SceneVersionConflictException::new);
        if (requireVersion) throw new InvalidSceneException("scene version is required");
        return repository.replaceSceneUnchecked(projectId, domainObjects);
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
