package com.product.level;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.product.identity.AuthenticatedUser;

@RestController
public final class LevelController {
    private final LevelService service;
    public LevelController(LevelService service) { this.service = service; }

    @PostMapping("/api/projects/{projectId}/levels")
    ResponseEntity<LevelDtos.LevelDto> create(@PathVariable UUID projectId,
            @RequestBody LevelDtos.CreateLevelDto request, Authentication authentication) {
        var level = service.create(AuthenticatedUser.from(authentication).userId(), projectId, request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(level));
    }

    @GetMapping("/api/projects/{projectId}/levels")
    List<LevelDtos.LevelDto> list(@PathVariable UUID projectId, Authentication authentication) {
        return service.list(AuthenticatedUser.from(authentication).userId(), projectId).stream().map(LevelController::toDto).toList();
    }

    @GetMapping("/api/levels/{id}")
    LevelDtos.LevelDto find(@PathVariable UUID id, Authentication authentication) {
        return toDto(service.find(AuthenticatedUser.from(authentication).userId(), id));
    }

    @DeleteMapping("/api/levels/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(AuthenticatedUser.from(authentication).userId(), id);
        return ResponseEntity.noContent().build();
    }

    private static LevelDtos.LevelDto toDto(Level level) {
        return new LevelDtos.LevelDto(level.id(), level.projectId(), level.name());
    }
}
