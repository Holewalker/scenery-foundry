package com.product.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
        var id = UUID.randomUUID();
        jdbc.sql("insert into prepared_assets(id,project_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:project,'READY','VALID_VOLUME','asset','" + "a".repeat(64) + "')")
            .param("id", id).param("project", project).update();
        return id;
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
        jdbc.sql("insert into prepared_assets(id,project_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:project,'READY','VALID_VOLUME','asset','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')")
            .param("id", asset).param("project", project).update();

        assertThatThrownBy(() -> jdbc.sql("insert into scene_objects(id,project_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major) values (0,:project,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}')")
            .param("project", project).param("asset", asset).update()).isInstanceOf(Exception.class);

        assertThat(jdbc.sql("select count(*) from prepared_assets where project_id=:project and id=:asset")
            .param("project", project).param("asset", asset).query(Long.class).single()).isEqualTo(1L);
    }
}
