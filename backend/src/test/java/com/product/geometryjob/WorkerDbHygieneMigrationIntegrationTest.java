package com.product.geometryjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs Flyway directly against a bare Postgres container (no Spring context) so each test can control exactly which
 * placeholder values are supplied and observe the worker role's cluster-wide state across multiple migrated
 * databases — behavior {@code @SpringBootTest} (which migrates once, using the app's own configured placeholder)
 * cannot exercise. Every test gets its OWN fresh container (non-static {@code @Container}) because the
 * {@code geometry_worker} role created by {@code V5} is cluster-wide, not per-database (see V5's own comment); a
 * shared container would let one test's role creation contaminate another test's "fresh-create" assumption.
 */
@Testcontainers(disabledWithoutDocker = true)
class WorkerDbHygieneMigrationIntegrationTest {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

    /**
     * D5's falsifiable premise (design.md): {@code now()} is STABLE, {@code clock_timestamp()} is VOLATILE. The
     * whole "keep now() as the timestamp default" decision rests on this being true on the real target engine, not
     * just cited from documentation.
     */
    @Test
    void nowIsStableAndClockTimestampIsVolatileOnThisPostgresEngine() throws SQLException {
        try (var conn = adminConnection(postgres.getDatabaseName());
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT proname, provolatile FROM pg_proc WHERE proname IN ('now','clock_timestamp')")) {
            Map<String, String> volatility = new HashMap<>();
            while (rs.next()) {
                volatility.put(rs.getString("proname"), rs.getString("provolatile"));
            }
            assertThat(volatility).containsEntry("now", "s");
            assertThat(volatility).containsEntry("clock_timestamp", "v");
        }
    }

    @Test
    void timestampColumnsDefaultToNowNotClockTimestamp() throws SQLException {
        var dbName = createFreshDatabase();
        migrate(dbName, "irrelevant-for-this-test");

        try (var conn = adminConnection(dbName)) {
            assertThat(columnDefaultExpression(conn, "assets", "created_at")).isEqualTo("now()");
            assertThat(columnDefaultExpression(conn, "geometry_jobs", "available_at")).isEqualTo("now()");
            assertThat(columnDefaultExpression(conn, "geometry_jobs", "created_at")).isEqualTo("now()");
        }
    }

    /**
     * Reproduces both branches of V5's role-provisioning DO-block in one cluster: a first database migrates into an
     * empty cluster (fresh-create path), then a second database migrates while {@code geometry_worker} already
     * exists from the first (pre-existing-role path) with a ROTATED password. Before the fix, the pre-existing
     * branch silently no-oped (IF NOT EXISTS ... CREATE ROLE) and the rotated password never took effect.
     */
    @Test
    void workerRolePasswordConvergesOnFreshCreateThenRotatedAlterPaths() throws SQLException {
        var firstDb = createFreshDatabase();
        migrate(firstDb, "password-v1-fresh");
        assertThat(canAuthenticate(firstDb, "geometry_worker", "password-v1-fresh")).isTrue();

        var secondDb = createFreshDatabase();
        migrate(secondDb, "password-v2-rotated");

        assertThat(canAuthenticate(secondDb, "geometry_worker", "password-v2-rotated")).isTrue();
        assertThat(canAuthenticate(secondDb, "geometry_worker", "password-v1-fresh")).isFalse();
    }

    @Test
    void missingWorkerDbPasswordFailsProvisioningWithNoDefaultApplied() throws SQLException {
        var dbName = createFreshDatabase();
        var flyway = Flyway.configure()
            .dataSource(jdbcUrlFor(dbName), postgres.getUsername(), postgres.getPassword())
            .load();

        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
            .hasMessageContaining("workerDbPassword");

        assertThat(roleExists("geometry_worker")).isFalse();
    }

    @Test
    void catalogQueryIndexExistsForOwnerCreatedAtDescAndId() throws SQLException {
        var dbName = createFreshDatabase();
        migrate(dbName, "irrelevant-for-this-test");

        try (var conn = adminConnection(dbName);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = 'assets'")) {
            var found = false;
            while (rs.next()) {
                var def = rs.getString("indexdef").toLowerCase();
                if (def.contains("owner_id") && def.contains("created_at desc") && def.contains("id")) {
                    found = true;
                }
            }
            assertThat(found).as("expected an index on assets(owner_id, created_at DESC, id)").isTrue();
        }
    }

    private void migrate(String dbName, String workerDbPassword) {
        Flyway.configure()
            .dataSource(jdbcUrlFor(dbName), postgres.getUsername(), postgres.getPassword())
            .placeholders(Map.of("workerDbPassword", workerDbPassword))
            .load().migrate();
    }

    private String createFreshDatabase() throws SQLException {
        var dbName = "wdh_" + UUID.randomUUID().toString().replace("-", "");
        try (var conn = adminConnection(postgres.getDatabaseName()); var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }
        return dbName;
    }

    private boolean roleExists(String role) throws SQLException {
        try (var conn = adminConnection(postgres.getDatabaseName());
             var stmt = conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            stmt.setString(1, role);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean canAuthenticate(String dbName, String role, String password) {
        try (var ignored = DriverManager.getConnection(jdbcUrlFor(dbName), role, password)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private String columnDefaultExpression(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                select pg_get_expr(d.adbin, d.adrelid) as default_expr
                from pg_attrdef d
                join pg_attribute a on a.attrelid = d.adrelid and a.attnum = d.adnum
                where d.adrelid = ?::regclass and a.attname = ?
                """)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString("default_expr");
            }
        }
    }

    private Connection adminConnection(String dbName) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(dbName), postgres.getUsername(), postgres.getPassword());
    }

    private String jdbcUrlFor(String dbName) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + dbName;
    }
}
