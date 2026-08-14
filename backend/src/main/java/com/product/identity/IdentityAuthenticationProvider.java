package com.product.identity;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

public final class IdentityAuthenticationProvider extends DaoAuthenticationProvider {
    public IdentityAuthenticationProvider(UserDetailsService users) {
        super(users);
    }

    @Override
    protected Authentication createSuccessAuthentication(Object principal, Authentication authentication, UserDetails user) {
        var databaseUser = (DatabaseUser) user;
        return new UsernamePasswordAuthenticationToken(databaseUser.user(), authentication.getCredentials(), user.getAuthorities());
    }
}
