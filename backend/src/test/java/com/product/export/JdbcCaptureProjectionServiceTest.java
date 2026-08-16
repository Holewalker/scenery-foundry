package com.product.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class JdbcCaptureProjectionServiceTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired JdbcCaptureProjectionService service;
    @Autowired JdbcExportRepository exports;

    @Test
    void rejectsEmptyAndOversizedProjectsWithoutPersistingAnExport() {
        Fixture empty = fixture();
        assertThatThrownBy(() -> service.capture(empty.owner(), empty.project())).isInstanceOf(InvalidCaptureException.class);
        assertThat(exportCount(empty.project())).isZero();

        Fixture oversized = fixture();
        for (long id = 1; id <= 251; id++) scene(oversized, id, "READY", "VALID_VOLUME");
        assertThatThrownBy(() -> service.capture(oversized.owner(), oversized.project())).isInstanceOf(InvalidCaptureException.class);
        assertThat(exportCount(oversized.project())).isZero();
    }

    @Test
    void rejectsIneligibleAssetsBeforePersistingAnExport() {
        Fixture fixture = fixture();
        scene(fixture, 1, "UPLOADED", "UNKNOWN");

        assertThatThrownBy(() -> service.capture(fixture.owner(), fixture.project())).isInstanceOf(InvalidCaptureException.class);
        assertThat(exportCount(fixture.project())).isZero();
    }

    @Test
    void capturesAnOrderedEligibleProjection() {
        Fixture fixture = fixture();
        scene(fixture, 9, "READY", "VALID_VOLUME");
        scene(fixture, 2, "READY", "VALID_VOLUME");

        UUID exportId = service.capture(fixture.owner(), fixture.project());

        assertThat(exports.findSnapshot(fixture.owner(), exportId)).isPresent();
        assertThat(exportCount(fixture.project())).isEqualTo(1);
    }

    private Fixture fixture() {
        UUID owner = UUID.randomUUID(); UUID project = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')").param("id", owner).param("email", owner + "@example.com").update();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", project).param("owner", owner).update();
        return new Fixture(owner, project);
    }

    private void scene(Fixture fixture, long id, String processing, String geometry) {
        UUID asset = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:owner,:processing,:geometry,'asset','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')")
            .param("id", asset).param("owner", fixture.owner()).param("processing", processing).param("geometry", geometry).update();
        jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major) values (:id,:project,:owner,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}')")
            .param("id", id).param("project", fixture.project()).param("owner", fixture.owner()).param("asset", asset).update();
    }

    private long exportCount(UUID project) { return jdbc.sql("select count(*) from combined_exports where project_id=:project").param("project", project).query(Long.class).single(); }
    private record Fixture(UUID owner, UUID project) { }
}

