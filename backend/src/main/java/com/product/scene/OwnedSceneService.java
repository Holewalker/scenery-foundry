package com.product.scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class OwnedSceneService {
    private static final int MAX_SCENE_OBJECTS = 250;
    private static final String DATA_ROOT = System.getenv().getOrDefault("SCENE_DATA_ROOT", "/data");
    private final OwnedSceneRepository repository;
    public OwnedSceneService(OwnedSceneRepository repository) { this.repository = repository; }
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
        return new SceneDtos.SceneDto(repository.findSceneObjects(projectId).stream().map(OwnedSceneService::toDto).toList());
    }

    public void replaceScene(UUID ownerId, UUID projectId, SceneDtos.SceneDto scene) {
        findProject(ownerId, projectId);
        var objects = scene.objects();
        if (objects.size() > MAX_SCENE_OBJECTS) throw new InvalidSceneException("scene exceeds the maximum object count");
        if (objects.stream().map(SceneDtos.SceneObjectDto::id).distinct().count() != objects.size())
            throw new InvalidSceneException("scene object ids must be unique");
        var assetIds = repository.findAssets(projectId).stream().map(PreparedAsset::id).collect(Collectors.toSet());
        repository.replaceScene(projectId, objects.stream().map(dto -> toDomain(projectId, assetIds, dto)).toList());
    }

    private static SceneObject toDomain(UUID projectId, Set<UUID> assetIds, SceneDtos.SceneObjectDto dto) {
        if (dto.matrixContractVersion() != 1 || !assetIds.contains(dto.assetId()))
            throw new InvalidSceneException("scene object references an invalid asset or contract version");
        return new SceneObject(SceneObjectId.of(dto.id()), projectId, dto.assetId(),
            SceneTransform.of(dto.matrixWorldColumnMajor(), dto.quaternionXyzw(), dto.scale()));
    }

    private static SceneDtos.SceneObjectDto toDto(SceneObject object) {
        var transform = object.transform();
        double[] matrix = transform.matrixWorldColumnMajor();
        return new SceneDtos.SceneObjectDto(object.id().value(), object.assetId(), 1,
            new double[] {matrix[12], matrix[13], matrix[14]}, transform.quaternionXyzw(), transform.scale(), matrix);
    }

    private static byte[] readAssetBytes(String storageKey) {
        var root = Path.of(DATA_ROOT).toAbsolutePath().normalize();
        var resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) throw new OwnedResourceNotFoundException();
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException exception) {
            throw new OwnedResourceNotFoundException();
        }
    }
}
