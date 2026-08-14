package com.product.scene;

import com.product.identity.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ProjectController {
    private final OwnedSceneService service;
    public ProjectController(OwnedSceneService service) { this.service = service; }
    @GetMapping("/api/projects/{projectId}")
    Map<String, String> findProject(@PathVariable UUID projectId, Authentication authentication) {
        var project = service.findProject(AuthenticatedUser.from(authentication).userId(), projectId);
        return Map.of("id", project.id().toString());
    }

    @GetMapping("/api/projects/{projectId}/assets")
    List<SceneDtos.AssetSummaryDto> listAssets(@PathVariable UUID projectId, Authentication authentication) {
        return service.listAssets(AuthenticatedUser.from(authentication).userId(), projectId).stream()
            .map(asset -> new SceneDtos.AssetSummaryDto(asset.id())).toList();
    }

    @GetMapping(value = "/api/projects/{projectId}/assets/{assetId}/original", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> assetOriginal(@PathVariable UUID projectId, @PathVariable UUID assetId, Authentication authentication) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(service.readOriginalStl(AuthenticatedUser.from(authentication).userId(), projectId, assetId));
    }

    @GetMapping("/api/projects/{projectId}/scene")
    SceneDtos.SceneDto getScene(@PathVariable UUID projectId, Authentication authentication) {
        return service.loadScene(AuthenticatedUser.from(authentication).userId(), projectId);
    }

    @PutMapping("/api/projects/{projectId}/scene")
    SceneDtos.SceneDto putScene(@PathVariable UUID projectId, @RequestBody SceneDtos.SceneDto scene, Authentication authentication) {
        var userId = AuthenticatedUser.from(authentication).userId();
        service.replaceScene(userId, projectId, scene);
        return service.loadScene(userId, projectId);
    }
}
