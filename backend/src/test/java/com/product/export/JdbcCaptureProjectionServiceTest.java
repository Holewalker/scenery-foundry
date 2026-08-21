package com.product.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcCaptureProjectionServiceTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    static Path storageRoot;

    @Autowired JdbcClient jdbc;
    @Autowired JdbcCaptureProjectionService service;
    @Autowired JdbcExportRepository exports;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storageRoot = Files.createTempDirectory("capture-projection-test");
        registry.add("SCENE_DATA_ROOT", storageRoot::toString);
    }

    @Test
    void rejectsEmptyAndOversizedGroupsWithoutPersistingAnExportOrAJob() {
        Fixture empty = fixture();
        assertThatThrownBy(() -> service.capture(empty.owner(), empty.group())).isInstanceOf(InvalidCaptureException.class);
        assertThat(exportCount(empty.project())).isZero();
        assertThat(jobCount(empty.owner())).isZero();

        Fixture oversized = fixture();
        for (long id = 1; id <= 251; id++) scene(oversized, id, "READY", "VALID_VOLUME", oversized.group());
        assertThatThrownBy(() -> service.capture(oversized.owner(), oversized.group())).isInstanceOf(InvalidCaptureException.class);
        assertThat(exportCount(oversized.project())).isZero();
        assertThat(jobCount(oversized.owner())).isZero();
    }

    @Test
    void rejectsIneligibleAssetsBeforePersistingAnExportOrAJob() {
        Fixture fixture = fixture();
        scene(fixture, 1, "UPLOADED", "UNKNOWN", fixture.group());

        assertThatThrownBy(() -> service.capture(fixture.owner(), fixture.group())).isInstanceOf(InvalidCaptureException.class);
        assertThat(exportCount(fixture.project())).isZero();
        assertThat(jobCount(fixture.owner())).isZero();
    }

    @Test
    void capturesAnOrderedEligibleProjectionAndInsertsAPendingCombinedExportJob() {
        Fixture fixture = fixture();
        scene(fixture, 9, "READY", "VALID_VOLUME", fixture.group());
        scene(fixture, 2, "READY", "VALID_VOLUME", fixture.group());

        UUID exportId = service.capture(fixture.owner(), fixture.group());

        assertThat(exports.findSnapshot(fixture.owner(), exportId)).isPresent();
        assertThat(exportCount(fixture.project())).isEqualTo(1);
        JobRow job = job(fixture.owner());
        assertThat(job.jobType()).isEqualTo("COMBINED_EXPORT");
        assertThat(job.status()).isEqualTo("PENDING");
        assertThat(job.subjectId()).isEqualTo(exportId);
        assertThat(job.idempotencyKey()).isEqualTo(exportId.toString());
    }

    @Test
    void capturesOnlyPrintGroupScopedMembersOntoTheSnapshot() {
        Fixture fixture = fixture();
        scene(fixture, 9, "READY", "VALID_VOLUME", fixture.group());
        scene(fixture, 2, "READY", "VALID_VOLUME", fixture.group());
        scene(fixture, 7, "READY", "VALID_VOLUME", null); // unassigned: must not be captured

        UUID exportId = service.capture(fixture.owner(), fixture.group());

        String canonical = new String(exports.findSnapshot(fixture.owner(), exportId).orElseThrow().canonicalBytes());
        assertThat(canonical).contains("\"scene_object_id\":9").contains("\"scene_object_id\":2");
        assertThat(canonical).doesNotContain("\"scene_object_id\":7");
    }

    /**
     * Spec scenario "Capture fails atomically": forces the geometry_jobs insert to fail (a trigger scoped to
     * this fixture's owner id only, so no other test in this class is affected), then proves neither the
     * combined_exports/export_snapshots rows NOR the job row survive — the snapshot and the job share one
     * transaction, not two independently-committed writes.
     */
    @Test
    void neitherTheSnapshotNorTheJobPersistsWhenTheJobInsertFails() {
        Fixture fixture = fixture();
        scene(fixture, 5, "READY", "VALID_VOLUME", fixture.group());
        forceCombinedExportJobInsertFailureFor(fixture.owner());

        assertThatThrownBy(() -> service.capture(fixture.owner(), fixture.group())).isInstanceOf(DataAccessException.class);

        assertThat(exportCount(fixture.project())).isZero();
        assertThat(jobCount(fixture.owner())).isZero();
    }

    private void forceCombinedExportJobInsertFailureFor(UUID ownerId) {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_fail_combined_export_job_insert() RETURNS trigger AS $$
                BEGIN
                    IF NEW.owner_id = '%s'::uuid AND NEW.job_type = 'COMBINED_EXPORT' THEN
                        RAISE EXCEPTION 'forced failure for capture atomicity test';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(ownerId)).update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_combined_export_job_insert_trigger ON geometry_jobs").update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_combined_export_job_insert_trigger
                BEFORE INSERT ON geometry_jobs
                FOR EACH ROW EXECUTE FUNCTION test_fail_combined_export_job_insert()
                """).update();
    }

    private Fixture fixture() {
        UUID owner = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID group = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')").param("id", owner).param("email", owner + "@example.com").update();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", project).param("owner", owner).update();
        jdbc.sql("insert into print_groups(id,project_id,owner_id,name) values (:id,:project,:owner,'group')")
            .param("id", group).param("project", project).param("owner", owner).update();
        return new Fixture(owner, project, group);
    }

    private void scene(Fixture fixture, long id, String processing, String geometry, UUID printGroupId) {
        UUID asset = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:owner,:processing,:geometry,'asset','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')")
            .param("id", asset).param("owner", fixture.owner()).param("processing", processing).param("geometry", geometry).update();
        jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major,print_group_id) values (:id,:project,:owner,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}',:group)")
            .param("id", id).param("project", fixture.project()).param("owner", fixture.owner()).param("asset", asset).param("group", printGroupId).update();
    }

    private long exportCount(UUID project) { return jdbc.sql("select count(*) from combined_exports where project_id=:project").param("project", project).query(Long.class).single(); }

    private long jobCount(UUID owner) {
        return jdbc.sql("select count(*) from geometry_jobs where owner_id=:owner and job_type='COMBINED_EXPORT'")
            .param("owner", owner).query(Long.class).single();
    }

    private JobRow job(UUID owner) {
        return jdbc.sql("select job_type,status,subject_id,idempotency_key from geometry_jobs where owner_id=:owner and job_type='COMBINED_EXPORT'")
            .param("owner", owner)
            .query((row, index) -> new JobRow(row.getString("job_type"), row.getString("status"),
                row.getObject("subject_id", UUID.class), row.getString("idempotency_key")))
            .single();
    }

    private record Fixture(UUID owner, UUID project, UUID group) { }
    private record JobRow(String jobType, String status, UUID subjectId, String idempotencyKey) { }
}
