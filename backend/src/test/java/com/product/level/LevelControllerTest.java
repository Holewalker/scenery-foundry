package com.product.level;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.identity.AuthenticatedUser;
import com.product.scene.OwnedResourceNotFoundException;

/** Creation/list/delete happy-path is covered at {@link JdbcLevelRepositoryTest}; this class covers only
 * the controller-level cross-user 404 contract (task 3.5) — list, read, and delete. */
@WebMvcTest(LevelController.class)
class LevelControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LevelService service;

    @Test
    void returnsNotFoundForAForeignOwnerOnListingReadingAndDeleting() throws Exception {
        UUID foreign = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID level = UUID.randomUUID();
        when(service.list(foreign, project)).thenThrow(new OwnedResourceNotFoundException());
        when(service.find(foreign, level)).thenThrow(new OwnedResourceNotFoundException());
        org.mockito.Mockito.doThrow(new OwnedResourceNotFoundException()).when(service).delete(foreign, level);

        mvc.perform(get("/api/projects/{project}/levels", project).with(authentication(user(foreign))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/levels/{id}", level).with(authentication(user(foreign))))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/levels/{id}", level).with(authentication(user(foreign))).with(csrf()))
            .andExpect(status().isNotFound());
    }

    private UsernamePasswordAuthenticationToken user(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }
}
