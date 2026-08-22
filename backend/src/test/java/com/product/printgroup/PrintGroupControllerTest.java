package com.product.printgroup;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Creation/list/delete happy-path is covered at {@link JdbcPrintGroupRepositoryTest}; this class covers only
 * the controller-level cross-user 404 contract (task 3.5) — list, read, and delete. */
@WebMvcTest(PrintGroupController.class)
class PrintGroupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PrintGroupService service;

    @Test
    void returnsNotFoundForAForeignOwnerOnListingReadingAndDeleting() throws Exception {
        UUID foreign = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID group = UUID.randomUUID();
        when(service.list(foreign, project)).thenThrow(new OwnedResourceNotFoundException());
        when(service.find(foreign, group)).thenThrow(new OwnedResourceNotFoundException());
        org.mockito.Mockito.doThrow(new OwnedResourceNotFoundException()).when(service).delete(foreign, group);

        mvc.perform(get("/api/projects/{project}/print-groups", project).with(authentication(user(foreign))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/print-groups/{id}", group).with(authentication(user(foreign))))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/print-groups/{id}", group).with(authentication(user(foreign))).with(csrf()))
            .andExpect(status().isNotFound());
    }

    private UsernamePasswordAuthenticationToken user(UUID owner) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(owner, "owner@example.com"), null, List.of());
    }
}
