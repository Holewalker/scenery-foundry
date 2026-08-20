package com.product.scene;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
