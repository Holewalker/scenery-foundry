package com.product.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
class JdbcExportRepositoryTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired JdbcExportRepository repository;

    @Test
    void locksOwnersProjectPersistsCanonicalSnapshotAndHidesItFromOtherOwners() {
        Fixture fixture = fixture();
        SnapshotV1 snapshot = fixture.snapshot();
        SnapshotV1Writer.CanonicalSnapshot canonical = new SnapshotV1Writer().canonicalize(snapshot);

        repository.capture(fixture.owner(), snapshot, canonical);

        ExportSnapshot stored = repository.findSnapshot(fixture.owner(), snapshot.exportId()).orElseThrow();
        assertThat(stored.canonicalBytes()).isEqualTo(canonical.canonicalBytes());
        assertThat(stored.sha256()).isEqualTo(canonical.sha256());
        assertThat(repository.findSnapshot(UUID.randomUUID(), snapshot.exportId())).isEmpty();
    }

    @Test
    void rollsBackExportWhenSnapshotInsertFails() {
        Fixture fixture = fixture();
        SnapshotV1 snapshot = fixture.snapshot();

        assertThatThrownBy(() -> repository.capture(fixture.owner(), snapshot,
            new SnapshotV1Writer.CanonicalSnapshot("not-the-contract", new byte[] {1}, "a".repeat(64))))
            .isInstanceOf(Exception.class);
        assertThat(jdbc.sql("select count(*) from combined_exports where id=:id").param("id", snapshot.exportId()).query(Long.class).single())
            .isZero();
    }

    @Test
    void rejectsUpdatesAndDeletesOfImmutableSnapshots() {
        Fixture fixture = fixture();
        SnapshotV1 snapshot = fixture.snapshot();
        repository.capture(fixture.owner(), snapshot, new SnapshotV1Writer().canonicalize(snapshot));

        assertThatThrownBy(() -> jdbc.sql("delete from export_snapshots where export_id=:id").param("id", snapshot.exportId()).update())
            .isInstanceOf(Exception.class);
    }

    private Fixture fixture() {
        UUID owner = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID export = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')").param("id", owner).param("email", owner + "@example.com").update();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", project).param("owner", owner).update();
        return new Fixture(owner, new SnapshotV1(export, project, owner, Instant.parse("2026-08-14T07:01:02.123456Z"), List.of()));
    }

    private record Fixture(UUID owner, SnapshotV1 snapshot) { }
}

