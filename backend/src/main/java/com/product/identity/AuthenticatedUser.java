package com.product.identity;

import java.util.UUID;

import org.springframework.security.core.Authentication;

public record AuthenticatedUser(UUID userId, String email) {
    public static AuthenticatedUser from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("AuthenticatedUser principal is required");
        }
        return user;
    }
}
