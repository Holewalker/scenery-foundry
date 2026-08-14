package com.product.identity;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public final class DatabaseUserDetailsService implements UserDetailsService {
    private final JdbcClient jdbc;

    public DatabaseUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return jdbc.sql("select id, email, password_hash from users where email = :email")
            .param("email", email)
            .query((resultSet, row) -> new DatabaseUser(
                new AuthenticatedUser(resultSet.getObject("id", UUID.class), resultSet.getString("email")),
                resultSet.getString("password_hash")))
            .optional()
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
