package com.product.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.product.common.web.SecurityConfiguration;

@WebMvcTest({AuthController.class, IdentityController.class})
@Import(SecurityConfiguration.class)
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void rejectsUnauthenticatedRequestsWith401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsLoginWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"owner@example.com\",\"password\":\"secret\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void savesTrustedPrincipalInRotatedSessionAfterCsrfProtectedLogin() throws Exception {
        var user = new AuthenticatedUser(UUID.fromString("00000000-0000-0000-0000-000000000001"), "owner@example.com");
        given(authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(user.email(), "secret")))
            .willReturn(new UsernamePasswordAuthenticationToken(user, null, AuthorityUtils.NO_AUTHORITIES));
        var session = new MockHttpSession();
        String initialSessionId = session.getId();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"owner@example.com\",\"password\":\"secret\"}"))
            .andExpect(status().isNoContent())
            .andReturn();

        var authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(authenticatedSession.getId()).isNotEqualTo(initialSessionId);
        mockMvc.perform(get("/api/auth/me").session(authenticatedSession))
            .andExpect(status().isOk());
    }

    @Test
    void invalidatesSessionOnCsrfProtectedLogout() throws Exception {
        var user = new AuthenticatedUser(UUID.fromString("00000000-0000-0000-0000-000000000002"), "owner@example.com");
        given(authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(user.email(), "secret")))
            .willReturn(new UsernamePasswordAuthenticationToken(user, null, AuthorityUtils.NO_AUTHORITIES));
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                .contentType("application/json").content("{\"email\":\"owner@example.com\",\"password\":\"secret\"}"))
            .andExpect(status().isNoContent()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }
}
