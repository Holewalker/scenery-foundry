package com.product.printgroup;

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
public final class PrintGroupController {
    private final PrintGroupService service;
    public PrintGroupController(PrintGroupService service) { this.service = service; }

    @PostMapping("/api/projects/{projectId}/print-groups")
    ResponseEntity<PrintGroupDtos.PrintGroupDto> create(@PathVariable UUID projectId,
            @RequestBody PrintGroupDtos.CreatePrintGroupDto request, Authentication authentication) {
        var group = service.create(AuthenticatedUser.from(authentication).userId(), projectId, request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(group));
    }

    @GetMapping("/api/projects/{projectId}/print-groups")
    List<PrintGroupDtos.PrintGroupDto> list(@PathVariable UUID projectId, Authentication authentication) {
        return service.list(AuthenticatedUser.from(authentication).userId(), projectId).stream().map(PrintGroupController::toDto).toList();
    }

    @GetMapping("/api/print-groups/{id}")
    PrintGroupDtos.PrintGroupDto find(@PathVariable UUID id, Authentication authentication) {
        return toDto(service.find(AuthenticatedUser.from(authentication).userId(), id));
    }

    @DeleteMapping("/api/print-groups/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(AuthenticatedUser.from(authentication).userId(), id);
        return ResponseEntity.noContent().build();
    }

    private static PrintGroupDtos.PrintGroupDto toDto(PrintGroup group) {
        return new PrintGroupDtos.PrintGroupDto(group.id(), group.projectId(), group.name());
    }
}
