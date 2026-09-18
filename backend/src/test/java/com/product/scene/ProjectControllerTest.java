package com.product.scene;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import com.product.identity.AuthenticatedUser;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OwnedSceneService service;

    @Test
    void returnsNotFoundForAnAuthenticatedForeignOwner() throws Exception {
        var owner = UUID.randomUUID();
        var foreign = UUID.randomUUID();
        var project = UUID.randomUUID();
        when(service.findProject(foreign, project)).thenThrow(new OwnedResourceNotFoundException());
        var user = new AuthenticatedUser(foreign, "foreign@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        mvc.perform(get("/api/projects/{id}", project).with(authentication(auth))).andExpect(status().isNotFound());
    }

    @Test
    void getReturnsTheProjectIdAndNameButNeverTheOwnerId() throws Exception {
        var owner = UUID.randomUUID();
        var project = UUID.randomUUID();
        when(service.findProject(owner, project)).thenReturn(new Project(project, owner, "My Scene"));
        var auth = authFor(owner);

        mvc.perform(get("/api/projects/{id}", project).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(project.toString()))
            .andExpect(jsonPath("$.name").value("My Scene"))
            .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void listProjectsReturnsAnEmptyListWhenTheOwnerHasNone() throws Exception {
        var owner = UUID.randomUUID();
        when(service.listProjects(owner)).thenReturn(List.of());

        mvc.perform(get("/api/projects").with(authentication(authFor(owner))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listProjectsReturnsOnlyTheAuthenticatedOwnersProjects() throws Exception {
        var owner = UUID.randomUUID();
        var projectA = UUID.randomUUID();
        var projectB = UUID.randomUUID();
        when(service.listProjects(owner)).thenReturn(List.of(new Project(projectA, owner, "First"), new Project(projectB, owner, "Second")));

        mvc.perform(get("/api/projects").with(authentication(authFor(owner))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(projectA.toString()))
            .andExpect(jsonPath("$[0].name").value("First"))
            .andExpect(jsonPath("$[0].ownerId").doesNotExist())
            .andExpect(jsonPath("$[1].id").value(projectB.toString()))
            .andExpect(jsonPath("$[1].name").value("Second"));
    }

    @Test
    void listProjectsRejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    void createProjectReturnsCreatedWithAServerGeneratedIdAndTheOwnerFromAuthenticationNotTheBody() throws Exception {
        var owner = UUID.randomUUID();
        var foreignOwnerClaimedInBody = UUID.randomUUID();
        var generated = UUID.randomUUID();
        when(service.createProject(owner, "My Scene")).thenReturn(new Project(generated, owner, "My Scene"));

        mvc.perform(post("/api/projects").with(authentication(authFor(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"My Scene\",\"ownerId\":\"" + foreignOwnerClaimedInBody + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(generated.toString()))
            .andExpect(jsonPath("$.name").value("My Scene"))
            .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void createProjectRejectsABlankName() throws Exception {
        var owner = UUID.randomUUID();
        when(service.createProject(owner, "   ")).thenThrow(new InvalidSceneException("project name is required"));

        mvc.perform(post("/api/projects").with(authentication(authFor(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createProjectRejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(post("/api/projects").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"My Scene\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void createProjectRejectsRequestsWithoutACsrfToken() throws Exception {
        var owner = UUID.randomUUID();

        mvc.perform(post("/api/projects").with(authentication(authFor(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"My Scene\"}"))
            .andExpect(status().isForbidden());
    }

    private static UsernamePasswordAuthenticationToken authFor(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }

    @Test
    void returnsNotFoundForAForeignOwnerOnScene() throws Exception {
        var foreign = UUID.randomUUID();
        var project = UUID.randomUUID();
        when(service.loadScene(foreign, project)).thenThrow(new OwnedResourceNotFoundException());
        var user = new AuthenticatedUser(foreign, "foreign@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        mvc.perform(get("/api/projects/{id}/scene", project).with(authentication(auth))).andExpect(status().isNotFound());
    }

    @Test
    void noLongerExposesTheLegacyProjectScopedAssetRoutes() throws Exception {
        var project = UUID.randomUUID();
        var asset = UUID.randomUUID();
        var user = new AuthenticatedUser(UUID.randomUUID(), "owner@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        mvc.perform(get("/api/projects/{id}/assets", project).with(authentication(auth))).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/{id}/assets/{assetId}/original", project, asset).with(authentication(auth))).andExpect(status().isNotFound());
    }

    @Test
    void rejectsSceneReplacementWithoutACsrfToken() throws Exception {
        var project = UUID.randomUUID();
        var user = new AuthenticatedUser(UUID.randomUUID(), "owner@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        mvc.perform(put("/api/projects/{id}/scene", project).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content("{\"objects\":[]}"))
            .andExpect(status().isForbidden());
    }

    /** Task 3.8: ApiExceptionHandler maps a composite-FK violation to 422, not a generic 500. */
    @Test
    void mapsACrossProjectReferenceViolationToUnprocessableEntity() throws Exception {
        var project = UUID.randomUUID();
        var user = new AuthenticatedUser(UUID.randomUUID(), "owner@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "ERROR: insert or update on table \"scene_objects\" violates foreign key constraint "
                    + "\"scene_objects_print_group_project_fkey\""))
            .when(service).replaceScene(org.mockito.ArgumentMatchers.eq(user.userId()), org.mockito.ArgumentMatchers.eq(project), org.mockito.ArgumentMatchers.any());

        mvc.perform(put("/api/projects/{id}/scene", project).with(authentication(auth)).with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"objects\":[]}"))
            .andExpect(status().isUnprocessableEntity());
    }

    /**
     * CodeRabbit/Codex finding on PR3 (#44): the 422 mapping must be scoped to the two scene-membership FK
     * constraints only. An unrelated integrity violation (e.g. a unique idempotency-key race) must NOT be
     * misreported as {@code INVALID_REFERENCE} — it falls through to the default error handling instead.
     */
    @Test
    void doesNotMapAnUnrelatedIntegrityViolationToUnprocessableEntity() {
        var project = UUID.randomUUID();
        var user = new AuthenticatedUser(UUID.randomUUID(), "owner@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"geometry_jobs_owner_job_type_idempotency_key_key\""))
            .when(service).replaceScene(org.mockito.ArgumentMatchers.eq(user.userId()), org.mockito.ArgumentMatchers.eq(project), org.mockito.ArgumentMatchers.any());

        // Not mapped by ApiExceptionHandler: no resolver produces a response, so the exception itself
        // propagates out of the servlet dispatch instead of resolving to 422 (proving the scoping works).
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                mvc.perform(put("/api/projects/{id}/scene", project).with(authentication(auth)).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"objects\":[]}")))
            .hasRootCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
