package com.product.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IdentityMigrationIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void flywayCreatesConstrainedIdentityTable() {
        var constraints = jdbcClient.sql("select conname from pg_constraint where conname in ('users_pkey', 'users_email_key', 'users_password_hash_check')")
            .query(String.class).list();

        assertThat(constraints).containsExactlyInAnyOrder("users_pkey", "users_email_key", "users_password_hash_check");
    }
}
