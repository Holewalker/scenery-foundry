package com.product.scene;

import com.product.identity.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ProjectController {
    private final OwnedSceneService service;
    public ProjectController(OwnedSceneService service) { this.service = service; }

    @GetMapping("/api/projects")
    List<ProjectResponse> listProjects(Authentication authentication) {
        return service.listProjects(AuthenticatedUser.from(authentication).userId()).stream().map(ProjectResponse::from).toList();
    }

    @PostMapping("/api/projects")
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse createProject(@RequestBody CreateProjectRequest request, Authentication authentication) {
        var project = service.createProject(AuthenticatedUser.from(authentication).userId(), request.name());
        return ProjectResponse.from(project);
    }

    @GetMapping("/api/projects/{projectId}")
    ProjectResponse findProject(@PathVariable UUID projectId, Authentication authentication) {
        var project = service.findProject(AuthenticatedUser.from(authentication).userId(), projectId);
        return ProjectResponse.from(project);
    }

    @GetMapping("/api/projects/{projectId}/scene")
    SceneDtos.SceneDto getScene(@PathVariable UUID projectId, Authentication authentication) {
        return service.loadScene(AuthenticatedUser.from(authentication).userId(), projectId);
    }

    @PutMapping("/api/projects/{projectId}/scene")
    SceneDtos.SceneDto putScene(@PathVariable UUID projectId, @RequestBody SceneDtos.SceneDto scene, Authentication authentication) {
        var userId = AuthenticatedUser.from(authentication).userId();
        return service.replaceScene(userId, projectId, scene);
    }
}
