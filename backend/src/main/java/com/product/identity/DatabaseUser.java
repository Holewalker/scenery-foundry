package com.product.identity;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

final class DatabaseUser implements UserDetails {
    private final AuthenticatedUser user;
    private final String passwordHash;

    DatabaseUser(AuthenticatedUser user, String passwordHash) {
        this.user = user;
        this.passwordHash = passwordHash;
    }

    AuthenticatedUser user() { return user; }
    @Override public String getUsername() { return user.email(); }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
}
