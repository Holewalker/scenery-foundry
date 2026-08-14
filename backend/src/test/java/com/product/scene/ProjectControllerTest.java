package com.product.scene;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
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
        var auth = new UsernamePasswordAuthenticationToken(user, null, java.util.List.of());
        mvc.perform(get("/api/projects/{id}", project).with(authentication(auth))).andExpect(status().isNotFound());
    }
}
