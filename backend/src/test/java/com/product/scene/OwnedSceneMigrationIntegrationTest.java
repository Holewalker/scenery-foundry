package com.product.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

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
class OwnedSceneMigrationIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired OwnedSceneService service;
    @Autowired JdbcOwnedSceneRepository repository;

    @Test
    void roundTripsAnEmptyThenPopulatedSceneForItsOwner() {
        var owner = insertUser();
        var project = insertProject(owner);
        var asset = insertAsset(project);

        assertThat(service.loadScene(owner, project).objects()).isEmpty();

        var object = new SceneDtos.SceneObjectDto(1, asset, 1, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(owner, project, new SceneDtos.SceneDto(List.of(object)));
        var loaded = service.loadScene(owner, project).objects();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).id()).isEqualTo(1);
        assertThat(loaded.get(0).assetId()).isEqualTo(asset);

        service.replaceScene(owner, project, new SceneDtos.SceneDto(List.of()));
        assertThat(service.loadScene(owner, project).objects()).isEmpty();
    }

    @Test
    void rejectsInvalidSceneReplacementsAndPreservesPriorData() {
        var owner = insertUser();
        var project = insertProject(owner);
        var asset = insertAsset(project);
        var original = new SceneDtos.SceneObjectDto(1, asset, 1, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(owner, project, new SceneDtos.SceneDto(List.of(original)));

        var duplicate = new SceneDtos.SceneDto(List.of(original, original));
        assertThatThrownBy(() -> service.replaceScene(owner, project, duplicate)).isInstanceOf(InvalidSceneException.class);

        var tooMany = new SceneDtos.SceneDto(IntStream.rangeClosed(1, 251)
            .mapToObj(id -> new SceneDtos.SceneObjectDto(id, asset, 1, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity()))
            .toList());
        assertThatThrownBy(() -> service.replaceScene(owner, project, tooMany)).isInstanceOf(InvalidSceneException.class);

        var foreignAsset = new SceneDtos.SceneDto(List.of(new SceneDtos.SceneObjectDto(2, UUID.randomUUID(), 1,
            new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity())));
        assertThatThrownBy(() -> service.replaceScene(owner, project, foreignAsset)).isInstanceOf(InvalidSceneException.class);

        var invalidTransform = new SceneDtos.SceneDto(List.of(new SceneDtos.SceneObjectDto(2, asset, 1,
            new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 0, 1}, identity())));
        assertThatThrownBy(() -> service.replaceScene(owner, project, invalidTransform)).isInstanceOf(InvalidSceneException.class);

        var loaded = service.loadScene(owner, project).objects();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).id()).isEqualTo(1);
    }

    @Test
    void rollsBackSceneReplacementWhenAnInsertFailsMidTransaction() {
        var owner = insertUser();
        var project = insertProject(owner);
        var asset = insertAsset(project);
        var original = new SceneObject(SceneObjectId.of(1), project, asset, SceneTransform.of(identity(), new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}));
        repository.replaceScene(project, List.of(original));

        var duplicateWithinBatch = List.of(
            new SceneObject(SceneObjectId.of(2), project, asset, SceneTransform.of(identity(), new double[] {0, 0, 0, 1}, new double[] {1, 1, 1})),
            new SceneObject(SceneObjectId.of(2), project, asset, SceneTransform.of(identity(), new double[] {0, 0, 0, 1}, new double[] {1, 1, 1})));
        assertThatThrownBy(() -> repository.replaceScene(project, duplicateWithinBatch)).isInstanceOf(Exception.class);

        assertThat(repository.findSceneObjects(project)).extracting(sceneObject -> sceneObject.id().value()).containsExactly(1L);
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private UUID insertProject(UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", id).param("owner", owner).update();
        return id;
    }

    private UUID insertAsset(UUID project) {
        var owner = jdbc.sql("select owner_id from projects where id=:project").param("project", project).query(UUID.class).single();
        var id = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:owner,'READY','VALID_VOLUME','asset','" + "a".repeat(64) + "')")
            .param("id", id).param("owner", owner).update();
        return id;
    }

    @Test
    void deniesForeignOwnerSceneReplacementAndPreservesTheOriginalOwnersData() {
        var ownerA = insertUser();
        var ownerB = insertUser();
        var project = insertProject(ownerA);
        var asset = insertAsset(project);
        var original = new SceneDtos.SceneObjectDto(1, asset, 1, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(ownerA, project, new SceneDtos.SceneDto(List.of(original)));

        assertThatThrownBy(() -> service.replaceScene(ownerB, project, new SceneDtos.SceneDto(List.of())))
            .isInstanceOf(OwnedResourceNotFoundException.class);

        assertThat(service.loadScene(ownerA, project).objects()).hasSize(1);
    }

    private static double[] identity() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    @Test
    void appliesCompositeAssetReferenceAndSafeSceneObjectIdConstraint() {
        var owner = UUID.randomUUID();
        var project = UUID.randomUUID();
        var asset = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,:hash)")
            .param("id", owner).param("email", "owner-" + owner + "@example.com").param("hash", "hash").update();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", project).param("owner", owner).update();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:owner,'READY','VALID_VOLUME','asset','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')")
            .param("id", asset).param("owner", owner).update();

        assertThatThrownBy(() -> jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major) values (0,:project,:owner,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}')")
            .param("project", project).param("owner", owner).param("asset", asset).update()).isInstanceOf(Exception.class);

        assertThat(jdbc.sql("select count(*) from assets where owner_id=:owner and id=:asset")
            .param("owner", owner).param("asset", asset).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void rejectsCrossOwnerSceneObjectAssetReferenceViaCompositeForeignKeys() {
        var ownerA = insertUser();
        var ownerB = insertUser();
        var projectB = insertProject(ownerB);
        var assetA = insertAsset(ownerA, "READY", "VALID_VOLUME");

        assertThatThrownBy(() -> jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,"
                + "translation_mm,quaternion_xyzw,scale,matrix_world_column_major) values "
                + "(1,:project,:owner,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}')")
            .param("project", projectB).param("owner", ownerB).param("asset", assetA).update()).isInstanceOf(Exception.class);

        assertThat(jdbc.sql("select count(*) from scene_objects where project_id=:project").param("project", projectB)
            .query(Long.class).single()).isZero();
    }

    @Test
    void checkConstraintAllowsProcessingAndRejectsTheLegacyPendingValue() {
        var owner = insertUser();
        assertThat(insertAsset(owner, "PROCESSING", "UNKNOWN")).isNotNull();
        assertThatThrownBy(() -> insertAsset(owner, "PENDING", "UNKNOWN")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> insertAsset(owner, "READY", "PENDING")).isInstanceOf(Exception.class);
    }

    @Test
    void createsAGeometryJobsQueueWithClaimIndexesAndAMinimalWorkerRole() {
        var owner = insertUser();
        var jobId = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,'ASSET_PROCESSING',:owner,'PENDING','{}'::jsonb,'idem-1')")
            .param("id", jobId).param("owner", owner).update();
        assertThat(jdbc.sql("select status from geometry_jobs where id=:id").param("id", jobId).query(String.class).single())
            .isEqualTo("PENDING");
        var indexNames = jdbc.sql("select indexname from pg_indexes where tablename='geometry_jobs'").query(String.class).list();
        assertThat(indexNames).anyMatch(name -> name.toLowerCase().contains("claimable"));
        assertThat(indexNames).anyMatch(name -> name.toLowerCase().contains("lease"));
        assertThat(Set.copyOf(jdbc.sql("select privilege_type from information_schema.role_table_grants "
                + "where grantee='geometry_worker' and table_name='geometry_jobs'").query(String.class).list()))
            .containsExactlyInAnyOrder("SELECT", "UPDATE");
    }

    private UUID insertAsset(UUID owner, String processing, String geometry) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,:processing,:geometry,'assets/a.stl','" + "a".repeat(64) + "')")
            .param("id", id).param("owner", owner).param("processing", processing).param("geometry", geometry).update();
        return id;
    }

    /**
     * Regression for V6 (Print Preparation Phase 4, PR2): {@code levels} and {@code print_groups} both apply on
     * a populated database (this container already has V1-V5 data from other tests in the class) and a
     * {@code scene_objects} row in the SAME project can reference both new FKs simultaneously.
     */
    @Test
    void appliesTheV6MigrationLettingASceneObjectJoinALevelAndPrintGroupInItsOwnProject() {
        var owner = insertUser();
        var project = insertProject(owner);
        var asset = insertAsset(project);
        var level = insertLevel(project, owner);
        var printGroup = insertPrintGroup(project, owner);

        var object = new SceneDtos.SceneObjectDto(1, asset, 1, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(owner, project, new SceneDtos.SceneDto(List.of(object)));
        jdbc.sql("update scene_objects set level_id=:level, print_group_id=:group where project_id=:project and id=1")
            .param("level", level).param("group", printGroup).param("project", project).update();

        assertThat(jdbc.sql("select level_id, print_group_id from scene_objects where project_id=:project and id=1")
            .param("project", project)
            .query((row, index) -> row.getObject("level_id") + ":" + row.getObject("print_group_id")).single())
            .isEqualTo(level + ":" + printGroup);
    }

    @Test
    void rejectsACrossProjectLevelAssignmentOnSceneObjectsViaTheCompositeForeignKey() {
        var owner = insertUser();
        var projectA = insertProject(owner);
        var projectB = insertProject(owner);
        var asset = insertAsset(projectB);
        var levelInProjectA = insertLevel(projectA, owner);

        var object = new SceneDtos.SceneObjectDto(1, asset, 1, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(owner, projectB, new SceneDtos.SceneDto(List.of(object)));

        assertThatThrownBy(() -> jdbc.sql("update scene_objects set level_id=:level where project_id=:project and id=1")
            .param("level", levelInProjectA).param("project", projectB).update()).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsACrossProjectPrintGroupAssignmentOnSceneObjectsViaTheCompositeForeignKey() {
        var owner = insertUser();
        var projectA = insertProject(owner);
        var projectB = insertProject(owner);
        var asset = insertAsset(projectB);
        var groupInProjectA = insertPrintGroup(projectA, owner);

        var object = new SceneDtos.SceneObjectDto(1, asset, 1, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, new double[] {1, 1, 1}, identity());
        service.replaceScene(owner, projectB, new SceneDtos.SceneDto(List.of(object)));

        assertThatThrownBy(() -> jdbc.sql("update scene_objects set print_group_id=:group where project_id=:project and id=1")
            .param("group", groupInProjectA).param("project", projectB).update()).isInstanceOf(Exception.class);
    }

    @Test
    void widensTheGeometryJobsCheckConstraintToAdmitCombinedExportAlongsideAssetProcessing() {
        var owner = insertUser();
        var jobId = UUID.randomUUID();

        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,'COMBINED_EXPORT',:owner,'PENDING','{}'::jsonb,'idem-combined')")
            .param("id", jobId).param("owner", owner).update();

        assertThat(jdbc.sql("select job_type from geometry_jobs where id=:id").param("id", jobId).query(String.class).single())
            .isEqualTo("COMBINED_EXPORT");
    }

    private UUID insertLevel(UUID project, UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into levels(id,project_id,owner_id,name) values (:id,:project,:owner,'Level 1')")
            .param("id", id).param("project", project).param("owner", owner).update();
        return id;
    }

    private UUID insertPrintGroup(UUID project, UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into print_groups(id,project_id,owner_id,name) values (:id,:project,:owner,'Group 1')")
            .param("id", id).param("project", project).param("owner", owner).update();
        return id;
    }
}
