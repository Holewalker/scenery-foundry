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
    void returnsNotFoundForAForeignOwnerAcrossCatalogStlAndScene() throws Exception {
        var foreign = UUID.randomUUID();
        var project = UUID.randomUUID();
        var asset = UUID.randomUUID();
        when(service.listAssets(foreign, project)).thenThrow(new OwnedResourceNotFoundException());
        when(service.readOriginalStl(foreign, project, asset)).thenThrow(new OwnedResourceNotFoundException());
        when(service.loadScene(foreign, project)).thenThrow(new OwnedResourceNotFoundException());
        var user = new AuthenticatedUser(foreign, "foreign@example.com");
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        mvc.perform(get("/api/projects/{id}/assets", project).with(authentication(auth))).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/{id}/assets/{assetId}/original", project, asset).with(authentication(auth))).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/{id}/scene", project).with(authentication(auth))).andExpect(status().isNotFound());
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
}
