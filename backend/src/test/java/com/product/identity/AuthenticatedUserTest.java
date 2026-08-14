package com.product.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AuthenticatedUserTest {
    @Test
    void extractsOnlyAuthenticatedUserPrincipals() {
        var expected = new AuthenticatedUser(UUID.randomUUID(), "owner@example.com");
        var authentication = new UsernamePasswordAuthenticationToken(expected, null, java.util.List.of());

        assertThat(AuthenticatedUser.from(authentication)).isEqualTo(expected);
    }

    @Test
    void rejectsForeignPrincipalTypes() {
        var authentication = new UsernamePasswordAuthenticationToken("owner@example.com", null);

        assertThatThrownBy(() -> AuthenticatedUser.from(authentication))
            .isInstanceOf(IllegalStateException.class);
    }
}
